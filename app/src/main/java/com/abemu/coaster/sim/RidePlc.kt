package com.abemu.coaster.sim

import kotlin.math.abs

enum class KeyMode(val label: String) { OFF("OFF"), MAINT("MAINT"), MANUAL("MANUAL"), AUTO("AUTO") }

enum class RideState(val label: String) {
    POWER_OFF("POWER OFF"), STOPPED("STOPPED"), STARTING("STARTING"), RUNNING("AUTO RUNNING"),
    CSTOP("CONTROLLED STOP"), ESTOP("EMERGENCY STOP")
}

enum class LaunchState(val label: String) {
    IDLE("IDLE"), CHARGING("CHARGING"), FIRING("LAUNCH"), VERIFY("VERIFY CREST"), ROLLBACK("ROLLBACK")
}

enum class TransferState(val label: String) {
    IDLE("LOCKED – MAIN"), UNLOCK("UNLOCKING PINS"), SHIFT_OUT("SHIFTING"), MOVE("MOVING TRAIN"),
    SHIFT_BACK("RETURNING"), LOCK("LOCKING PINS")
}

/** Console and HMI inputs. Momentary pushbuttons are true while held. */
class Inputs(blocks: Int) {
    var mode = KeyMode.OFF
    var estopPressed = false
    var powerOn = false
    var powerOff = false
    var rideStart = false
    var controlledStop = false
    var faultReset = false
    var silence = false
    var dispatchL = false
    var dispatchR = false
    var restraintOpen = false
    var restraintClose = false
    var gatesOpen = false
    var gatesClose = false
    var loadReady = false
    var unloadReady = false
    var liftJog = false
    /** Emulator convenience: either dispatch button acts as a valid two-hand press. */
    var singleButtonDispatch = false
    val manualRelease = BooleanArray(blocks)
}

data class Permissive(val label: String, val ok: Boolean)

/** Simulated ControlLogix ride controller. [scan] runs one 20 ms program scan. */
class RidePlc(val plant: Plant) {
    val profile = plant.profile
    private val n = profile.blocks.size
    val inputs = Inputs(n)
    val alarms = AlarmManager()

    var state = RideState.POWER_OFF
        private set
    var launchState = LaunchState.IDLE
        private set
    var transferState = TransferState.IDLE
        private set
    private var transferStoring = true

    /** Block occupancy as tracked by the PLC: train number, or -1 when clear. */
    val occ = IntArray(n) { -1 }
    private val leaving = BooleanArray(n)
    private val prevSensor = BooleanArray(n)
    val releaseLatch = BooleanArray(n)
    private val latchedTrain = IntArray(n) { -1 }
    val maintBrakeOpen = BooleanArray(n)
    private val brakeMismatchT = DoubleArray(n)
    private val stallT = DoubleArray(n)
    private val rowFaultT = DoubleArray(profile.rows)

    var loadReady = false; private set
    var unloadReady = false; private set
    var permissives: List<Permissive> = emptyList(); private set
    var dispatchReady = false; private set
    var dispatchCount = 0; private set
    var statusText = ""; private set

    private var t = 0.0
    private var stateT = 0.0
    private var launchT = 0.0
    private var transferT = 0.0
    private var launchTrain = -1
    private var launchExitSeen = 0
    private var tDispL = -9.0
    private var tDispR = -9.0
    private var twoHandSpent = false
    private var gateFaultT = 0.0
    private var stoppedT = 0.0
    private val prev = HashMap<String, Boolean>()

    var scanCount = 0L; private set

    /** Two-tone "clear the track" warning, seconds remaining. */
    private var trackClearT = 0.0
    val trackClearActive get() = powered && trackClearT > 0
    private var lastMode = KeyMode.OFF
    val trackClearSeconds = 6.0

    private fun trackClear(reason: String) {
        if (trackClearT <= 0) alarms.info("TRKCLR", "Track clear warning – $reason")
        trackClearT = trackClearSeconds
    }
    val dispatchTimes = ArrayDeque<Double>()
    val time get() = t

    private fun rise(key: String, v: Boolean): Boolean {
        val r = v && prev[key] != true
        prev[key] = v
        return r
    }

    val powered get() = state != RideState.POWER_OFF
    val eStopped get() = state == RideState.ESTOP
    fun next(i: Int) = (i + 1) % n
    fun nextClear(i: Int) = occ[next(i)] == -1 && !plant.entrySensor(next(i))
    fun trainSpeed(id: Int) = plant.trains.firstOrNull { it.id == id }?.v ?: 0.0

    val trainInStation get() = occ[0] != -1 && plant.stationTrain() != null
    val allRowsLocked get() = (0 until profile.rows).all { plant.rowLockSensor(it) }
    val hornActive get() = powered && alarms.hornDemand
    val resetNeeded get() = alarms.active.any { !it.condition } || (eStopped && !inputs.estopPressed)

    fun scan(dt: Double) {
        t += dt
        stateT += dt
        scanCount++
        val i = inputs
        val m = i.mode

        // ---------- Power ----------
        if (m == KeyMode.OFF || rise("pwrOff", i.powerOff)) {
            if (state != RideState.POWER_OFF) alarms.info("PWR", "Ride power OFF")
            setState(RideState.POWER_OFF)
        } else if (rise("pwrOn", i.powerOn) && state == RideState.POWER_OFF) {
            alarms.info("PWR", "Ride power ON – block tracking verified")
            resyncTracking()
            setState(if (i.estopPressed) RideState.ESTOP else RideState.STOPPED)
        }
        trackClearT = (trackClearT - dt).coerceAtLeast(0.0)
        if (powered && (m == KeyMode.MANUAL) != (lastMode == KeyMode.MANUAL)) {
            trackClear(if (m == KeyMode.MANUAL) "entering MANUAL" else "leaving MANUAL")
        }
        lastMode = m
        if (!powered) {
            trackClearT = 0.0
            allOutputsOff()
            rise("reset", i.faultReset); rise("start", i.rideStart); rise("cs", i.controlledStop)
            statusText = if (m == KeyMode.OFF) "Turn key to select mode" else "Press RIDE POWER ON"
            return
        }

        // ---------- Block tracking ----------
        for (b in 0 until n) {
            val s = plant.entrySensor(b)
            if (s && !prevSensor[b]) onEntry(b)
            if (!s && prevSensor[b]) onCleared(b)
            prevSensor[b] = s
        }

        // ---------- Fault conditions ----------
        val f = plant.faults
        alarms.condition("ESTOP_PB", "Operator console E-Stop actuated", Severity.ESTOP, i.estopPressed)
        alarms.condition("SAFETY", "Safety relay MSR1: remote E-Stop chain open", Severity.ESTOP, f.safetyCircuit)
        alarms.condition("AIR", "Main air pressure low (${plant.airPsi.toInt()} psi)", Severity.CSTOP, plant.airPsi < 75)
        if (profile.type == ProfileType.LIFT) {
            alarms.condition("LIFT_VFD", "Lift VFD faulted – F0005 DC bus overvoltage", Severity.CSTOP, f.liftVfd)
            alarms.condition("LIFT_OL", "Lift motor overload relay OL1 tripped", Severity.CSTOP, f.liftOverload)
        } else {
            alarms.condition("LSM", "LSM drive fault – launch inhibited", Severity.CSTOP, f.lsmFault)
        }
        if (plant.collision) alarms.raise("COLLIDE", "Train spacing violation – contact detected", Severity.ESTOP)

        for (b in 0 until n) {
            val brk = plant.brakes[b]
            val mismatch = (brk.cmdOpen && !brk.openProx) || (!brk.cmdOpen && !brk.closedProx)
            brakeMismatchT[b] = if (mismatch) brakeMismatchT[b] + dt else 0.0
            val blk = profile.blocks[b]
            if (blk.stopPos != null) {
                val txt = "${blk.tag} brake position fault – cmd ${if (brk.cmdOpen) "OPEN" else "CLOSED"}, " +
                    "feedback ${if (brk.openProx) "OPEN" else if (brk.closedProx) "CLOSED" else "MID"}"
                alarms.condition("BRK$b", txt, Severity.ESTOP, brakeMismatchT[b] > 1.2)
            }
            // Stall / valley detection via block speed sensors.
            val id = occ[b]
            val stalled = id != -1 && abs(trainSpeed(id)) < 0.05 && plant.trainAtStop(b)?.id != id &&
                blk.kind != BlockKind.LIFT && blk.kind != BlockKind.LAUNCH && state != RideState.ESTOP
            stallT[b] = if (stalled) stallT[b] + dt else 0.0
            alarms.condition("STALL$b", "${blk.tag} train not at expected position – possible valley/stall",
                Severity.CSTOP, stallT[b] > 8.0)
        }

        val stTrain = plant.stationTrain()
        for (r in 0 until profile.rows) {
            val bad = stTrain != null && !plant.restraintUnlockCmd && !plant.rowLockSensor(r)
            rowFaultT[r] = if (bad) rowFaultT[r] + dt else 0.0
            alarms.condition("ROW$r", "Station: row ${r + 1} restraint not locked", Severity.WARNING, rowFaultT[r] > 2.5)
        }
        gateFaultT = if (!plant.gatesOpenCmd && !plant.gateClosedSensor) gateFaultT + dt else 0.0
        alarms.condition("GATE", "Station air gates – closed switch not made", Severity.WARNING, gateFaultT > 3.0)

        val hasE = alarms.anyActive(Severity.ESTOP)
        val hasC = alarms.anyActive(Severity.CSTOP)

        // ---------- Reset / silence ----------
        if (rise("silence", i.silence)) { alarms.silence(); alarms.acknowledgeAll() }
        if (rise("reset", i.faultReset)) {
            plant.collision = false
            val stopFaults = alarms.active.count { !it.condition && it.severity.ordinal <= Severity.CSTOP.ordinal }
            val left = alarms.reset()
            if (stopFaults > 0) trackClear("fault cleared")
            alarms.acknowledgeAll()
            if (launchState == LaunchState.ROLLBACK && !alarms.isActive("ROLLBACK")) launchState = LaunchState.IDLE
            if (state == RideState.ESTOP && !alarms.anyActive(Severity.ESTOP)) setState(RideState.STOPPED)
            alarms.info("RESET", if (left == 0) "Fault reset – all faults cleared" else "Fault reset – $left fault(s) still present")
        }

        // ---------- Ride state machine ----------
        if (hasE) setState(RideState.ESTOP)
        val start = rise("start", i.rideStart)
        val cs = rise("cs", i.controlledStop)
        when (state) {
            RideState.STOPPED -> if (start) {
                when {
                    m != KeyMode.AUTO -> alarms.info("START", "Ride start refused – key not in AUTO")
                    hasC || hasE -> alarms.info("START", "Ride start refused – faults active")
                    transferState != TransferState.IDLE -> alarms.info("START", "Ride start refused – transfer not locked")
                    else -> { setState(RideState.STARTING); trackClear("ride start") }
                }
            }
            RideState.STARTING -> when {
                cs || m != KeyMode.AUTO || hasC -> setState(RideState.STOPPED)
                stateT > trackClearSeconds -> { setState(RideState.RUNNING); alarms.info("RUN", "Ride in automatic operation") }
            }
            RideState.RUNNING -> if (cs || m != KeyMode.AUTO || hasC) {
                setState(RideState.CSTOP)
                alarms.info("CS", "Controlled stop initiated")
            }
            RideState.CSTOP -> {
                val still = plant.trains.all { abs(it.v) < 0.05 } && launchState != LaunchState.FIRING
                stoppedT = if (still) stoppedT + dt else 0.0
                if (stoppedT > 1.5) { setState(RideState.STOPPED); alarms.info("CS", "Controlled stop complete") }
            }
            else -> Unit
        }
        val running = state == RideState.RUNNING
        val manualOk = m == KeyMode.MANUAL && state == RideState.STOPPED && !hasE
        val maint = m == KeyMode.MAINT && !eStopped

        // ---------- Station ----------
        val inStation = trainInStation
        if (rise("rOpen", i.restraintOpen)) {
            if (inStation && !releaseLatch[0] && !eStopped) {
                plant.restraintUnlockCmd = true
                loadReady = false; unloadReady = false
            } else alarms.info("STN", "Restraint open refused – no train stopped in station")
        }
        if (rise("rClose", i.restraintClose)) plant.restraintUnlockCmd = false
        if (rise("gOpen", i.gatesOpen)) {
            if (inStation && !releaseLatch[0] && !eStopped) {
                plant.gatesOpenCmd = true
                loadReady = false; unloadReady = false
            } else alarms.info("STN", "Gate open refused – no train stopped in station")
        }
        if (rise("gClose", i.gatesClose)) plant.gatesOpenCmd = false
        if (rise("ldRdy", i.loadReady)) loadReady = !loadReady
        if (rise("unRdy", i.unloadReady)) unloadReady = !unloadReady
        val restraintsOk = allRowsLocked && !plant.restraintUnlockCmd
        val gatesOk = plant.gateClosedSensor && !plant.gatesOpenCmd

        permissives = listOf(
            Permissive("Key switch in AUTO", m == KeyMode.AUTO),
            Permissive("Ride running", running),
            Permissive("No active faults", !hasE && !hasC),
            Permissive("Train stopped at station", inStation),
            Permissive("Restraints locked – all rows", restraintsOk),
            Permissive("Station gates closed", gatesOk),
            Permissive("Load side operator ready", loadReady),
            Permissive("Unload side operator ready", unloadReady),
            Permissive("${profile.blocks[1].tag} ${profile.blocks[1].name} clear", nextClear(0)),
            Permissive("Air pressure ≥ 75 psi", plant.airPsi >= 75),
        )
        dispatchReady = permissives.all { it.ok } && !releaseLatch[0]

        val single = i.singleButtonDispatch && (i.dispatchL || i.dispatchR)
        val dL = i.dispatchL || single
        val dR = i.dispatchR || single
        if (rise("dL", dL)) tDispL = t
        if (rise("dR", dR)) tDispR = t
        if (!dL && !dR) twoHandSpent = false
        if (dL && dR && !twoHandSpent) {
            twoHandSpent = true
            when {
                abs(tDispL - tDispR) > 0.5 -> alarms.info("DISP", "Dispatch refused – two-hand timing exceeded 0.5 s")
                !dispatchReady -> alarms.info("DISP", "Dispatch refused – permissives not made")
                else -> {
                    latch(0)
                    dispatchCount++
                    dispatchTimes.addLast(t)
                    if (dispatchTimes.size > 20) dispatchTimes.removeFirst()
                    loadReady = false; unloadReady = false
                    alarms.info("DISP", "Train ${occ[0]} dispatched")
                }
            }
        }

        // ---------- Block brakes ----------
        val manualOpen = BooleanArray(n)
        for (b in 0 until n) {
            val blk = profile.blocks[b]
            if (releaseLatch[b] && (occ[next(b)] == latchedTrain[b] || occ[b] == -1)) releaseLatch[b] = false
            if (blk.kind == BlockKind.BRAKE_RUN && running && occ[b] != -1 && nextClear(b) && !releaseLatch[b] &&
                transferState == TransferState.IDLE
            ) latch(b)
            if (manualOk && i.manualRelease[b] && occ[b] != -1 && nextClear(b)) {
                manualOpen[b] = when (blk.kind) {
                    BlockKind.STATION -> restraintsOk && gatesOk
                    BlockKind.BRAKE_RUN -> true
                    else -> false
                }
            }
        }
        if (eStopped) releaseLatch.fill(false)
        if (m != KeyMode.MAINT) maintBrakeOpen.fill(false)

        for (b in 0 until n) {
            val blk = profile.blocks[b]
            val open = when (blk.kind) {
                BlockKind.LAUNCH -> launchState == LaunchState.FIRING || launchState == LaunchState.VERIFY
                BlockKind.LIFT -> false
                else -> releaseLatch[b] || manualOpen[b]
            } || (maint && maintBrakeOpen[b])
            plant.brakes[b].cmdOpen = open && !eStopped && !(b == 3 && transferState != TransferState.IDLE)
            plant.tiresCmd[b] = !eStopped
        }

        // ---------- Lift ----------
        if (profile.type == ProfileType.LIFT) {
            val crestHold = plant.bodyAt(profile.liftCrestSensor) && !nextClear(1)
            plant.liftRunCmd = !eStopped && when {
                running -> !crestHold
                state == RideState.CSTOP -> occ[1] != -1 && !crestHold
                manualOk -> i.liftJog && !crestHold
                maint -> i.liftJog
                else -> false
            }
        } else {
            plant.liftRunCmd = false
            val launchRelease = rise("mRel1", i.manualRelease[1])
            runLaunch(dt, running, manualOk && launchRelease)
        }

        // ---------- Transfer ----------
        runTransfer(dt)

        statusText = when {
            eStopped -> if (inputs.estopPressed) "E-STOP – release E-Stop, then FAULT RESET" else "Press FAULT RESET"
            hasC && state != RideState.RUNNING -> "Fault active – correct and reset"
            state == RideState.STARTING -> "Start warning – ride starting"
            state == RideState.CSTOP -> "Controlled stop – trains stopping in blocks"
            state == RideState.STOPPED && m == KeyMode.AUTO -> "Ready – press RIDE START"
            state == RideState.STOPPED && m == KeyMode.MANUAL -> "Manual – hold block release to move trains"
            state == RideState.STOPPED && m == KeyMode.MAINT -> "Maintenance – interlocks bypassed"
            releaseLatch[0] -> "Train departing station"
            dispatchReady -> "READY TO DISPATCH"
            inStation -> "Loading – " + (permissives.firstOrNull { !it.ok }?.label?.let { "waiting: $it" } ?: "")
            else -> "Waiting for train"
        }
    }

    private fun runLaunch(dt: Double, running: Boolean, manualRelease: Boolean) {
        launchT += dt
        val ready = occ[1] != -1 && plant.trainAtStop(1)?.let { abs(it.v) < 0.05 } == true
        if (eStopped && launchState != LaunchState.ROLLBACK) launchState = LaunchState.IDLE
        when (launchState) {
            LaunchState.IDLE -> if (ready && nextClear(1) && !plant.faults.lsmFault && (running || manualRelease)) {
                launchState = LaunchState.CHARGING; launchT = 0.0
            }
            LaunchState.CHARGING -> if (launchT > 3.0) {
                launchState = LaunchState.FIRING; launchT = 0.0
                launchTrain = occ[1]; launchExitSeen = plant.launchExitEvent
                alarms.info("LSM", "Launch – train $launchTrain")
            }
            LaunchState.FIRING -> if (plant.launchExitEvent != launchExitSeen || launchT > 4.0) {
                launchState = LaunchState.VERIFY; launchT = 0.0
                val v = plant.lastLaunchExitSpeed
                if (v < 28.0) alarms.raise("LSM_SPD", "Launch exit speed low: ${"%.1f".format(v)} m/s (min 28.0)", Severity.WARNING)
            }
            LaunchState.VERIFY -> when {
                occ[2] == launchTrain -> launchState = LaunchState.IDLE
                launchT > 7.0 -> {
                    launchState = LaunchState.ROLLBACK
                    alarms.raise("ROLLBACK", "Launch rollback – train $launchTrain failed to crest top hat", Severity.CSTOP)
                }
            }
            LaunchState.ROLLBACK -> Unit
        }
        plant.lsmFireCmd = launchState == LaunchState.FIRING && !eStopped
        plant.lsmPositionCmd = !eStopped && launchState != LaunchState.FIRING && launchState != LaunchState.ROLLBACK
    }

    // ---------- Transfer track (maintenance) ----------

    fun requestTransfer(store: Boolean): String {
        if (inputs.mode != KeyMode.MAINT) return "Transfer requires MAINT mode"
        if (state != RideState.STOPPED) return "Transfer requires ride STOPPED"
        if (transferState != TransferState.IDLE) return "Transfer already in progress"
        if (store) {
            if (occ[3] == -1 || plant.trainAtStop(3) == null) return "No train stopped on transfer section"
        } else {
            if (plant.stored == 0) return "No trains in storage"
            if (occ[3] != -1 || plant.trains.any { profile.blockAt(it.s).index == 3 }) return "Transfer section not clear"
        }
        transferStoring = store
        transferState = TransferState.UNLOCK
        transferT = 0.0
        alarms.info("XFER", if (store) "Transfer: storing train ${occ[3]}" else "Transfer: retrieving train from storage")
        return "Transfer started"
    }

    private fun runTransfer(dt: Double) {
        if (transferState == TransferState.IDLE) return
        if (eStopped) return // hold position
        transferT += dt
        fun nextStep(s: TransferState, after: Double) { if (transferT > after) { transferState = s; transferT = 0.0 } }
        when (transferState) {
            TransferState.UNLOCK -> nextStep(TransferState.SHIFT_OUT, 2.0)
            TransferState.SHIFT_OUT -> nextStep(TransferState.MOVE, 4.0)
            TransferState.MOVE -> if (transferT > 1.0) {
                if (transferStoring) {
                    if (plant.storeTrainFrom(3)) occ[3] = -1
                } else if (plant.retrieveTrainTo(3)) {
                    occ[3] = plant.trains.last().id
                }
                transferState = TransferState.SHIFT_BACK; transferT = 0.0
            }
            TransferState.SHIFT_BACK -> nextStep(TransferState.LOCK, 4.0)
            TransferState.LOCK -> if (transferT > 2.0) {
                transferState = TransferState.IDLE
                alarms.info("XFER", "Transfer complete – track locked MAIN")
            }
            TransferState.IDLE -> Unit
        }
    }

    // ---------- Helpers ----------

    private fun latch(b: Int) {
        releaseLatch[b] = true
        latchedTrain[b] = occ[b]
    }

    private fun onEntry(b: Int) {
        val p = (b - 1 + n) % n
        val blk = profile.blocks[b]
        when {
            occ[p] == -1 ->
                alarms.raise("UNEXP$b", "${blk.tag} entry prox: unexpected train detected", Severity.ESTOP)
            occ[b] != -1 ->
                alarms.raise("OVR$b", "${blk.tag} block overrun – entered while occupied", Severity.ESTOP)
            else -> {
                occ[b] = occ[p]
                leaving[p] = true
            }
        }
    }

    private fun onCleared(b: Int) {
        val p = (b - 1 + n) % n
        if (leaving[p]) {
            occ[p] = -1
            leaving[p] = false
        }
    }

    /** Re-derive block occupancy from train positions (maintenance "train verify"). */
    fun resyncTracking() {
        occ.fill(-1)
        leaving.fill(false)
        releaseLatch.fill(false)
        for (tr in plant.trains) {
            val b = profile.blockAt(tr.s).index
            occ[b] = tr.id
        }
        for (b in 0 until n) prevSensor[b] = plant.entrySensor(b)
    }

    private fun setState(s: RideState) {
        if (state != s) {
            state = s
            stateT = 0.0
            stoppedT = 0.0
        }
    }

    private fun allOutputsOff() {
        plant.brakes.forEach { it.cmdOpen = false }
        plant.tiresCmd.fill(false)
        plant.liftRunCmd = false
        plant.lsmFireCmd = false
        plant.lsmPositionCmd = false
        releaseLatch.fill(false)
        launchState = LaunchState.IDLE
        loadReady = false; unloadReady = false
    }
}
