package com.abemu.coaster.sim

import kotlin.math.abs

enum class KeyMode(val label: String) { OFF("OFF"), MAINT("MAINT"), MANUAL("MANUAL"), AUTO("AUTO") }

enum class RideState(val label: String) {
    POWER_OFF("POWER OFF"), STOPPED("STOPPED"), STARTING("STARTING"), RUNNING("AUTO RUNNING"),
    CSTOP("CONTROLLED STOP"), ESTOP("EMERGENCY STOP")
}

enum class LaunchState(val label: String) {
    IDLE("IDLE"), CHARGING("CHARGING"), FIRING("LAUNCH"), SWING("SWING"), VERIFY("VERIFY CREST"), ROLLBACK("ROLLBACK")
}

enum class TransferState(val label: String) {
    IDLE("LOCKED – MAIN"), UNLOCK("UNLOCKING PINS"), SHIFT_OUT("SHIFTING"), MOVE("MOVING TRAIN"),
    SHIFT_BACK("RETURNING"), LOCK("LOCKING PINS")
}

/** Console and HMI inputs. Momentary pushbuttons are true while held. */
class Inputs(blocks: Int) {
    var mode = KeyMode.OFF
    var estopPressed = false
    var loadStandEstop = false
    var unloadStandEstop = false
    val anyEstop get() = estopPressed || loadStandEstop || unloadStandEstop
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
    /** Stand foot switches: held down while an operator is standing at the stand. */
    var loadFootSwitch = false
    var unloadFootSwitch = false
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
    val forces = Forces()
    val safety = SafetyController()
    /** Weather hold from the anemometer and lightning detector; taller rides have a lower wind limit. */
    val weather = WeatherInterlock(if (profile.maxHeight >= 60) 35.0 else 40.0)

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
    /** Moving-block station: train that has followed the departing train into the block, -1 if none. */
    val pending = IntArray(n) { -1 }
    private val prevSensor = BooleanArray(n)
    val releaseLatch = BooleanArray(n)
    private val latchedTrain = IntArray(n) { -1 }
    val maintBrakeOpen = BooleanArray(n)
    private val brakeMismatchT = DoubleArray(n)
    private val stallT = DoubleArray(n)
    private val rowFaultT = DoubleArray(profile.rows)
    private val dwellT = DoubleArray(n)
    /** Seconds the train in each block has been held at its stop waiting for the block ahead (stacking). */
    val holdT = DoubleArray(n)
    /** Trains currently held at a block brake or lift crest because the block ahead is occupied. */
    val stackedTrains get() = holdT.count { it > 0 }
    /** Current stack-up (2+ trains held) duration, seconds, and how many stack-ups there have been. */
    var stackT = 0.0; private set
    var stackEvents = 0; private set

    // ---------- Restraint walk check ----------
    /** Operator must check every car (push-pull test, belts) before the load side may go ready. */
    var restraintWalk = false
    /** Cars checked by the operator since the restraints last opened. */
    val carChecked = BooleanArray(profile.rows)
    val walkComplete get() = carChecked.all { it }

    // ---------- Launch rollback recovery ----------
    var rollbacks = 0; private set
    /** Maintenance has inspected the train and launch track after a rollback. */
    var rollbackInspected = false; private set
    /** Recovered from a rollback: the next launch waits for an operator to authorize it. */
    var relaunchHold = false; private set
    var relaunchArmed = false; private set
    private var rollbackText = ""
    /** Seconds a train dwells in an unload station before it is released to load. */
    val unloadDwellSec = 8.0

    var loadReady = false; private set
    var unloadReady = false; private set
    var permissives: List<Permissive> = emptyList(); private set
    var dispatchReady = false; private set
    var dispatchCount = 0; private set
    /** Dispatch accepted but held in the station until [courseBlock] clears ([CoasterSpec.dispatchHoldForCourse]). */
    var dispatchArmed = false; private set
    /** Block after the lift, which must be clear before a held dispatch releases; -1 if the ride has no such hold. */
    val courseBlock = if (profile.spec.dispatchHoldForCourse)
        profile.blocks.indexOfFirst { it.kind == BlockKind.LIFT }.let { if (it < 0) -1 else (it + 1) % profile.blocks.size } else -1
    var statusText = ""; private set

    private var t = 0.0
    private var stateT = 0.0
    private var launchT = 0.0
    private var transferT = 0.0
    var launchTrain = -1
        private set
    private var launchExitSeen = 0
    /** Swing launch stage: 0 forward, 1 backward, 2 final forward (single launches go straight to 2). */
    var launchStage = 2; private set
    private var tDispL = -9.0
    private var tDispR = -9.0
    private var twoHandSpent = false
    private var gateFaultT = 0.0
    private var stoppedT = 0.0
    private val prev = HashMap<String, Boolean>()

    var scanCount = 0L; private set

    // Standard outputs as solved by the logic, before forces.
    private var gateCmd = false
    private var restraintCmd = false
    /** Lift crest hold (train at crest, next block occupied); exposed for the ladder view. */
    var liftCrestHold = false; private set
    private var safeStdT = 0.0
    /** Manual station departure permitted: checks were made at the stop and RELEASE is still held. */
    private var manualDepart = false

    // ---------- Input image (forces override field values) ----------
    private val entryTag = Array(n) { "${profile.blocks[it].tag}_PX_ENTRY" }
    private val brkClTag = Array(n) { "${profile.blocks[it].tag}_BRK_CLOSED_PX" }
    private val brkOpTag = Array(n) { "${profile.blocks[it].tag}_BRK_OPEN_PX" }
    private val brkSolTag = Array(n) { "${profile.blocks[it].tag}_BRK_SOL" }
    private val tiresTag = Array(n) { "${profile.blocks[it].tag}_DRIVE_TIRES" }
    private fun fx(tag: String, raw: Boolean) = forces[tag] ?: raw
    fun entry(b: Int) = fx(entryTag[b], plant.entrySensor(b))
    fun brkOpenPx(b: Int) = fx(brkOpTag[b], plant.brakes[b].openProx)
    fun brkClosedPx(b: Int) = fx(brkClTag[b], plant.brakes[b].closedProx)
    fun rowLocked(r: Int) = fx("ROW${r + 1}_LOCKED_LS", plant.rowLockSensor(r))
    val gatesClosed get() = fx("GATES_CLOSED_LS", plant.gateClosedSensor)
    val airOk get() = fx("AIR_PRESS_SW", plant.airPsi >= 75)
    val crestPx get() = fx("LIFT_CREST_PX", plant.bodyAt(profile.liftCrestSensor))
    val lsmReady get() = fx("LSM_DRIVE_READY", !plant.faults.lsmFault)
    val hasSwitch get() = profile.spikeHeight > 0
    val switchStationLs get() = fx("SWITCH_STATION_LS", plant.switchStationLs)
    val switchSpikeLs get() = fx("SWITCH_SPIKE_LS", plant.switchSpikeLs)
    /** Switch track commanded to the spike line (exposed for the HMI and ladder). */
    var switchCmd = false; private set
    private var switchTravelT = 0.0
    val estopPbOk get() = fx("ESTOP_PB_NC", !inputs.estopPressed)
    val loadAtStand get() = fx("LOAD_FOOT_SW", inputs.loadFootSwitch)
    val unloadAtStand get() = fx("UNLOAD_FOOT_SW", inputs.unloadFootSwitch)
    /** Seconds allowed for every row to lock after a restraint close, before a row is flagged. */
    val restraintLockTimeout get() = profile.rows * 0.6 + 2.5
    val loadEstopOk get() = fx("LOAD_STAND_ESTOP_NC", !inputs.loadStandEstop)
    val unloadEstopOk get() = fx("UNLOAD_STAND_ESTOP_NC", !inputs.unloadStandEstop)
    val remoteEstopOk get() = fx("REMOTE_ESTOP_OK", !plant.faults.safetyCircuit)

    /**
     * Operator restraint check of car [r] in the station: push-pull every restraint and look at the belts. Belts found
     * undone are fastened; a restraint that is not down is pushed down (it locks unless it is faulty). Returns a message.
     */
    fun checkCar(r: Int): String {
        val t = plant.stationTrain() ?: return "No train in the station"
        if (restraintCmd) return "Close the restraints first"
        val per = t.seatsPerRow
        val notLocked = (0 until per).filter { !t.seatLocked[r * per + it] }
        val belts = (0 until per).filter { t.seatTaken[r * per + it] && !t.beltOn[r * per + it] }
        belts.forEach { t.beltOn[r * per + it] = true }
        notLocked.forEach { k -> if (r * per + k != plant.faults.restraintSeat) t.seatLocked[r * per + k] = true }
        val stillBad = (0 until per).filter { !t.seatLocked[r * per + it] }
        carChecked[r] = stillBad.isEmpty() && rowLocked(r)
        val msg = buildList {
            if (belts.isNotEmpty()) add("seatbelt fastened (seat ${belts.joinToString { "${it + 1}" }})")
            if (notLocked.isNotEmpty() && stillBad.size < notLocked.size) add("restraint pushed down")
            if (stillBad.isNotEmpty()) add("seat ${stillBad.joinToString { "${it + 1}" }} restraint WILL NOT LOCK")
            if (!rowLocked(r) && stillBad.isEmpty()) add("row lock switch not made")
        }
        return "Car ${r + 1}: " + if (msg.isEmpty()) "checked OK" else msg.joinToString(", ")
    }

    /** Train is stopped somewhere on the launch track after a rollback. */
    val rollbackAtRest: Boolean get() {
        val tr = plant.trains.firstOrNull { it.id == launchTrain } ?: return false
        return launchState == LaunchState.ROLLBACK && tr.spike <= 0 && abs(tr.v) < 0.05 && profile.blockAt(tr.s).kind == BlockKind.LAUNCH
    }

    /** The rollback train is stopped somewhere past the launch track (it crested; tracking missed it). */
    val rollbackTrainClear: Boolean get() {
        val tr = plant.trains.firstOrNull { it.id == launchTrain } ?: return false
        val k = profile.blockAt(tr.s).kind
        return launchState == LaunchState.ROLLBACK && tr.spike <= 0 && abs(tr.v) < 0.05 && k != BlockKind.LAUNCH && k != BlockKind.STATION
    }
    private var rollbackFalse = false

    /** Maintenance sign-off of the post-rollback inspection (train, LSM, rollback brakes). */
    fun signOffRollbackInspection(): String {
        if (launchState != LaunchState.ROLLBACK) return "No rollback to recover"
        if (rollbackTrainClear && !rollbackInspected) {
            // Tracking lost the crest (e.g. a stop mid-launch) but the train is parked downstream: nothing to recover.
            rollbackInspected = true; rollbackFalse = true
            alarms.info("RB", "Inspection: train $launchTrain found at rest clear of the launch track – launch completed, no rollback")
            return "Train is clear of the launch track – FAULT RESET to clear the rollback"
        }
        if (!rollbackAtRest) return "Train not yet at rest on the launch track"
        if (rollbackInspected) return "Inspection already signed off"
        rollbackInspected = true
        alarms.info("RB", "Rollback inspection signed off – train $launchTrain, launch track and rollback brakes OK")
        return "Inspection signed off – FAULT RESET to recover the train"
    }

    /** Operator authorizes the first launch after a rollback recovery. */
    fun authorizeRelaunch(): String {
        if (!relaunchHold) return "No relaunch pending"
        if (plant.trainAtStop(1)?.let { abs(it.v) < 0.05 } != true) return "Train not at the launch position yet"
        if (!lsmReady) return "LSM drive not ready"
        relaunchArmed = true
        alarms.info("RB", "Relaunch authorized")
        return "Relaunch authorized"
    }

    /** Rollback recovery checklist for the HMI. */
    val rollbackSteps: List<Permissive> get() = listOf(
        Permissive("Train at rest on the launch track", rollbackAtRest || relaunchHold),
        Permissive("Maintenance inspection signed off", rollbackInspected || relaunchHold),
        Permissive("Fault reset – rollback cleared", relaunchHold),
        Permissive("Train returned to the launch position", relaunchHold && plant.trainAtStop(1)?.let { abs(it.v) < 0.05 } == true),
        Permissive("Relaunch authorized", relaunchArmed),
    )

    /** Install (true/false) or remove (null) a force, logged like Logix does. */
    fun force(tag: String, v: Boolean?) {
        if (forces[tag] == v) return
        forces[tag] = v
        alarms.info("FORCE", if (v == null) "Force removed: $tag" else "Force installed: $tag = ${if (v) 1 else 0}")
    }

    fun removeAllForces() {
        if (!forces.any) return
        forces.clear()
        alarms.info("FORCE", "All I/O forces removed")
    }

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
    fun nextClear(i: Int) = occ[next(i)] == -1 && !entry(next(i))
    private fun courseClear() = nextClear(courseBlock - 1)

    private fun dispatch() {
        latch(0)
        dispatchCount++
        dispatchTimes.addLast(t)
        if (dispatchTimes.size > 20) dispatchTimes.removeFirst()
        loadReady = false; unloadReady = false
        alarms.info("DISP", "Train ${occ[0]} dispatched")
        plant.stationTrain()?.let { st ->
            val open = st.seatTaken.indices.filter { st.seatTaken[it] && !st.beltOn[it] }
            if (open.isNotEmpty()) alarms.info("CHECK", "Train ${st.id} dispatched with a seatbelt undone – " +
                open.joinToString { "car ${it / st.seatsPerRow + 1} seat ${it % st.seatsPerRow + 1}" })
        }
    }
    val movingBlock get() = profile.spec.movingBlockStation
    /** Station zone of a moving-block ride: a following train may enter while the train ahead is leaving. */
    fun stationZone(b: Int) = movingBlock && profile.blocks[b].kind.let { it == BlockKind.STATION || it == BlockKind.UNLOAD }
    /** The train in [b] is released and on its way out, with no follower already behind it. */
    fun departing(b: Int) = occ[b] != -1 && pending[b] == -1 &&
        (leaving[b] || (releaseLatch[b] && latchedTrain[b] == occ[b] && trainSpeed(occ[b]) > 0.3))
    /**
     * Next block clear, or (moving-block station) its train departing so this one, standing at its stop, can follow it
     * in. A train still arriving is never waved through on a departing train; it stops first.
     */
    fun followClear(i: Int) = nextClear(i) || (stationZone(next(i)) && departing(next(i)) && !entry(next(i)) &&
        plant.trainAtStop(i)?.let { it.id == occ[i] && abs(it.v) < 0.05 } == true)
    fun trainSpeed(id: Int) = plant.trains.firstOrNull { it.id == id }?.v ?: 0.0

    /** HMI status of one train: GOOD running normally, CHECKING while the PLC verifies it, BAD when stopped on a fault. */
    fun trainStatus(t: Train): TrainStatus {
        val launching = t.id == launchTrain
        return when {
            eStopped || (launching && launchState == LaunchState.ROLLBACK) -> TrainStatus.BAD
            state == RideState.CSTOP -> TrainStatus.CHECKING
            launching && launchState == LaunchState.VERIFY -> TrainStatus.CHECKING
            occ[1] == t.id && profile.type == ProfileType.LAUNCH && launchState in setOf(LaunchState.IDLE, LaunchState.CHARGING) &&
                plant.trainAtStop(1)?.id == t.id -> TrainStatus.CHECKING // at the launch: switch / LSM checks
            plant.stationTrain()?.id == t.id -> TrainStatus.CHECKING // restraint, gate and operator checks
            profile.blocks.any { it.kind == BlockKind.UNLOAD && occ[it.index] == t.id && plant.trainAtStop(it.index)?.id == t.id } ->
                TrainStatus.CHECKING
            else -> TrainStatus.GOOD
        }
    }

    val trainInStation get() = occ[0] != -1 && plant.stationTrain() != null
    val allRowsLocked get() = (0 until profile.rows).all { rowLocked(it) }
    val hornActive get() = powered && alarms.hornDemand
    val resetNeeded get() = alarms.active.any { !it.condition } || (eStopped && !inputs.anyEstop)

    /** Target dispatch interval for the operator timer, seconds. */
    var dispatchTarget = 45.0
    val sinceDispatch: Double? get() = dispatchTimes.lastOrNull()?.let { t - it }
    val lastInterval: Double? get() = if (dispatchTimes.size >= 2) dispatchTimes.last() - dispatchTimes[dispatchTimes.size - 2] else null
    val avgInterval: Double? get() = if (dispatchTimes.size >= 2) (dispatchTimes.last() - dispatchTimes.first()) / (dispatchTimes.size - 1) else null

    /** One controller scan: standard task, then the safety task, then outputs (with forces) to the field. */
    fun scan(dt: Double) {
        standardTask(dt)
        val f = plant.faults
        safety.scan(
            dt, booleanArrayOf(inputs.estopPressed, inputs.loadStandEstop, inputs.unloadStandEstop, f.safetyCircuit),
            f.estopWeldedB, f.safetyIoFault, reset = inputs.faultReset || inputs.powerOn,
            stopRequest = state == RideState.ESTOP || !powered,
        )
        plant.safetyPower = safety.outputsOn
        writeOutputs()
    }

    private fun writeOutputs() {
        for (b in 0 until n) {
            plant.brakes[b].cmdOpen = forces[brkSolTag[b]] ?: plant.brakes[b].cmdOpen
            plant.tiresCmd[b] = forces[tiresTag[b]] ?: plant.tiresCmd[b]
        }
        plant.liftRunCmd = forces["LIFT_VFD_RUN"] ?: plant.liftRunCmd
        plant.lsmFireCmd = forces["LSM_FIRE"] ?: plant.lsmFireCmd
        plant.lsmPositionCmd = forces["LSM_POSITION"] ?: plant.lsmPositionCmd
        plant.switchToSpikeCmd = forces["SWITCH_TO_SPIKE"] ?: plant.switchToSpikeCmd
        plant.gatesOpenCmd = forces["GATE_OPEN_SOL"] ?: gateCmd
        plant.restraintUnlockCmd = forces["RESTR_UNLOCK_SOL"] ?: restraintCmd
    }

    private fun standardTask(dt: Double) {
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
            setState(if (i.anyEstop) RideState.ESTOP else RideState.STOPPED)
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
            val s = entry(b)
            if (s && !prevSensor[b]) onEntry(b)
            if (!s && prevSensor[b]) onCleared(b)
            prevSensor[b] = s
        }

        // ---------- Fault conditions ----------
        val f = plant.faults
        alarms.condition("ESTOP_PB", "Operator console E-Stop actuated", Severity.ESTOP, !estopPbOk)
        alarms.condition("ESTOP_LOAD", "Load stand E-Stop actuated", Severity.ESTOP, !loadEstopOk)
        alarms.condition("ESTOP_UNLOAD", "Unload stand E-Stop actuated", Severity.ESTOP, !unloadEstopOk)
        alarms.condition("SAFETY", "Safety relay MSR1: remote E-Stop chain open", Severity.ESTOP, !remoteEstopOk)
        weather.scan(dt)
        alarms.condition("WX", "Weather hold – ${weather.reason}", Severity.WARNING, weather.hold)
        alarms.condition("AIR", "Main air pressure low (${plant.airPsi.toInt()} psi)", Severity.CSTOP, !airOk)
        if (profile.type == ProfileType.LIFT) {
            alarms.condition("LIFT_VFD", "Lift VFD faulted – F0005 DC bus overvoltage", Severity.CSTOP, !fx("LIFT_VFD_READY", !f.liftVfd))
            alarms.condition("LIFT_OL", "Lift motor overload relay OL1 tripped", Severity.CSTOP, !fx("LIFT_OL1_OK", !f.liftOverload))
        } else {
            alarms.condition("LSM", "LSM drive fault – launch inhibited", Severity.CSTOP, !lsmReady)
        }
        // Safety task status, read back by the standard task.
        val disc = safety.stations.indices.filter { safety.discrepancy[it] }.joinToString { safety.stations[it] }
        alarms.condition("SAFE_DISC", "Safety: E-Stop channel A/B discrepancy – $disc", Severity.ESTOP, safety.anyDiscrepancy)
        alarms.condition("SAFE_IO", "Safety: 1756-IB16S safety input module connection faulted", Severity.ESTOP, safety.ioFault)
        val mismatch = safety.tripped && state != RideState.ESTOP && !safety.anyDiscrepancy && !safety.ioFault
        safeStdT = if (mismatch) safeStdT + dt else 0.0
        if (safeStdT > 1.0) alarms.raise("SAFE_STD", "Safety/standard discrepancy – safety outputs OFF, standard task not stopped", Severity.ESTOP)
        if (plant.collision) alarms.raise("COLLIDE", "Train spacing violation – contact detected", Severity.ESTOP)
        if (movingBlock) {
            // Moving-block anti-collision: a following train must keep a clear gap to the tail of the train ahead.
            val close = plant.trains.any { a ->
                a.spike <= 0 && plant.trains.any { b ->
                    b !== a && b.spike <= 0 && profile.ahead(a.s, b.s) < profile.trainLength + 3.0 && a.v > 0.2
                }
            }
            if (close) alarms.raise("MB_GAP", "Moving block – following train inside minimum spacing", Severity.ESTOP)
        }
        // A rollback stays latched until maintenance has inspected the train.
        if (launchState == LaunchState.ROLLBACK) alarms.condition("ROLLBACK", rollbackText, Severity.CSTOP, !rollbackInspected)
        if (hasSwitch) {
            if (plant.switchOverrun) alarms.raise("SW_OVR", "Switch track not aligned – train stopped at the end of the switch table", Severity.ESTOP)
            val there = if (switchCmd) switchSpikeLs else switchStationLs
            switchTravelT = if (!there) switchTravelT + dt else 0.0
            alarms.condition("SW_POS", "Switch track failed to reach ${if (switchCmd) "SPIKE" else "STATION"} position",
                Severity.CSTOP, switchTravelT > 9.0)
        }

        for (b in 0 until n) {
            val brk = plant.brakes[b]
            val mismatch = (brk.cmdOpen && !brkOpenPx(b)) || (!brk.cmdOpen && !brkClosedPx(b))
            brakeMismatchT[b] = if (mismatch) brakeMismatchT[b] + dt else 0.0
            val blk = profile.blocks[b]
            if (blk.stopPos != null) {
                val txt = "${blk.tag} brake position fault – cmd ${if (brk.cmdOpen) "OPEN" else "CLOSED"}, " +
                    "feedback ${if (brkOpenPx(b)) "OPEN" else if (brkClosedPx(b)) "CLOSED" else "MID"}"
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
            val bad = stTrain != null && !restraintCmd && !rowLocked(r)
            rowFaultT[r] = if (bad) rowFaultT[r] + dt else 0.0
            alarms.condition("ROW$r", "Station: row ${r + 1} restraint not locked", Severity.WARNING, rowFaultT[r] > restraintLockTimeout)
        }
        gateFaultT = if (!gateCmd && !gatesClosed) gateFaultT + dt else 0.0
        alarms.condition("GATE", "Station air gates – closed switch not made", Severity.WARNING, gateFaultT > 3.0)


        // ---------- Reset / silence ----------
        if (rise("silence", i.silence)) { alarms.silence(); alarms.acknowledgeAll() }
        if (rise("reset", i.faultReset)) {
            plant.collision = false
            plant.switchOverrun = false
            val stopFaults = alarms.active.count { !it.condition && it.severity.ordinal <= Severity.CSTOP.ordinal }
            val left = alarms.reset()
            if (stopFaults > 0) trackClear("fault cleared")
            alarms.acknowledgeAll()
            if (launchState == LaunchState.ROLLBACK && !alarms.isActive("ROLLBACK") && rollbackFalse) {
                launchState = LaunchState.IDLE
                rollbackFalse = false
                resyncTracking()
                alarms.info("RB", "Rollback cleared – train $launchTrain completed its launch; tracking re-synchronised")
            } else if (launchState == LaunchState.ROLLBACK && !alarms.isActive("ROLLBACK")) {
                launchState = LaunchState.IDLE
                relaunchHold = true; relaunchArmed = false
                alarms.info("RB", "Rollback recovery – LSM returning train $launchTrain to the launch position; relaunch needs authorization")
            }
            if (state == RideState.ESTOP && !alarms.anyActive(Severity.ESTOP)) setState(RideState.STOPPED)
            alarms.info("RESET", if (left == 0) "Fault reset – all faults cleared" else "Fault reset – $left fault(s) still present")
        }

        // Evaluated after the reset so a successful reset takes effect in the same scan.
        val hasE = alarms.anyActive(Severity.ESTOP)
        val hasC = alarms.anyActive(Severity.CSTOP)

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
                restraintCmd = true
                loadReady = false; unloadReady = false
            } else alarms.info("STN", "Restraint open refused – no train stopped in station")
        }
        if (rise("rClose", i.restraintClose)) restraintCmd = false
        if (rise("gOpen", i.gatesOpen)) {
            if (inStation && !releaseLatch[0] && !eStopped) {
                gateCmd = true
                loadReady = false; unloadReady = false
            } else alarms.info("STN", "Gate open refused – no train stopped in station")
        }
        if (rise("gClose", i.gatesClose)) gateCmd = false
        // An operator's ready is only accepted (and only held) while their foot switch shows them at the stand.
        if (rise("ldRdy", i.loadReady)) {
            when {
                !loadAtStand -> alarms.info("STN", "Load ready refused – no operator at the load stand (foot switch)")
                restraintWalk && !loadReady && !walkComplete -> alarms.info("STN", "Load ready refused – restraint check not complete (car " +
                    carChecked.indices.filter { !carChecked[it] }.joinToString { "${it + 1}" } + ")")
                else -> loadReady = !loadReady
            }
        }
        if (rise("unRdy", i.unloadReady)) {
            if (unloadAtStand) unloadReady = !unloadReady else alarms.info("STN", "Unload ready refused – no operator at the unload stand (foot switch)")
        }
        if (!loadAtStand) loadReady = false
        if (!unloadAtStand) unloadReady = false
        if (restraintCmd || !inStation) carChecked.fill(false)
        val restraintsOk = allRowsLocked && !restraintCmd
        val gatesOk = gatesClosed && !gateCmd

        permissives = listOf(
            Permissive("Key switch in AUTO", m == KeyMode.AUTO),
            Permissive("Ride running", running),
            Permissive("No active faults", !hasE && !hasC),
            Permissive("Train stopped at station", inStation),
            Permissive("Restraints locked – all rows", restraintsOk),
            Permissive("Station gates closed", gatesOk),
            Permissive("Load operator at stand (foot switch)", loadAtStand),
            Permissive("Unload operator at stand (foot switch)", unloadAtStand),
            Permissive("Load side operator ready", loadReady),
            Permissive("Unload side operator ready", unloadReady),
            Permissive("${profile.blocks[1].tag} ${profile.blocks[1].name} clear", nextClear(0)),
            Permissive("Air pressure ≥ 75 psi", airOk),
            Permissive("No weather hold", !weather.hold),
        ) + if (hasSwitch) listOf(Permissive("Switch track aligned to station", switchStationLs && !switchCmd)) else emptyList()
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
                courseBlock >= 0 && !courseClear() -> if (!dispatchArmed) {
                    dispatchArmed = true
                    alarms.info("DISP", "Dispatch armed – holding train ${occ[0]} until ${profile.blocks[courseBlock].tag} ${profile.blocks[courseBlock].name} clear")
                }
                else -> dispatch()
            }
        }
        if (dispatchArmed) when {
            !dispatchReady -> { dispatchArmed = false; alarms.info("DISP", "Held dispatch cancelled – permissives lost") }
            courseClear() -> { dispatchArmed = false; dispatch() }
        }

        // ---------- Block brakes ----------
        val manualOpen = BooleanArray(n)
        if (!i.manualRelease[0] || occ[0] == -1 || !manualOk) manualDepart = false
        for (b in 0 until n) {
            val blk = profile.blocks[b]
            // The release ends once the train reaches the next block (as its owner, or following in behind a departing train).
            val nb = next(b)
            if (releaseLatch[b] && (occ[nb] == latchedTrain[b] || pending[nb] == latchedTrain[b] || occ[b] != latchedTrain[b])) {
                releaseLatch[b] = false
            }
            // Unload station: train must dwell at the stop (passengers exit) before release.
            val atStop = occ[b] != -1 && plant.trainAtStop(b)?.let { it.id == occ[b] && abs(it.v) < 0.05 } == true
            dwellT[b] = if (blk.kind == BlockKind.UNLOAD && atStop) dwellT[b] + dt else 0.0
            val unloaded = blk.kind != BlockKind.UNLOAD || dwellT[b] >= unloadDwellSec
            if ((blk.kind == BlockKind.BRAKE_RUN || blk.kind == BlockKind.UNLOAD) && running && occ[b] != -1 &&
                followClear(b) && unloaded && !releaseLatch[b] && transferState == TransferState.IDLE
            ) latch(b)
            if (manualOk && i.manualRelease[b] && occ[b] != -1 && nextClear(b)) {
                manualOpen[b] = when (blk.kind) {
                    BlockKind.STATION -> { if (restraintsOk && gatesOk) manualDepart = true; manualDepart }
                    BlockKind.BRAKE_RUN, BlockKind.UNLOAD -> true
                    else -> false
                }
            }
        }
        // Stacking: a train standing at its stop (or the lift crest) waiting for the block ahead.
        for (b in 0 until n) {
            val blk = profile.blocks[b]
            val held = occ[b] != -1 && !releaseLatch[b] && !followClear(b) && when (blk.kind) {
                BlockKind.LIFT -> crestPx && occ[b] != -1 && plant.chainSpeed < 0.05 && running
                BlockKind.BRAKE_RUN, BlockKind.UNLOAD -> plant.trainAtStop(b)?.let { it.id == occ[b] && abs(it.v) < 0.05 } == true
                else -> false
            }
            if (held && holdT[b] == 0.0 && blk.kind == BlockKind.BRAKE_RUN && running) {
                alarms.info("STACK", "${blk.tag} ${blk.name} holding train ${occ[b]} – ${profile.blocks[next(b)].tag} occupied")
            }
            holdT[b] = if (held) holdT[b] + dt else 0.0
        }
        if (stackedTrains >= 2) { if (stackT == 0.0) stackEvents++; stackT += dt } else stackT = 0.0

        // An E-Stop cancels releases for trains still at their stop. A train that has already left the stop
        // keeps its release so it can complete the move after a restart instead of being stranded.
        if (eStopped) for (b in 0 until n) {
            if (plant.trainAtStop(b)?.id == occ[b] || occ[b] == -1) releaseLatch[b] = false
        }
        val autoRelease = state == RideState.RUNNING || state == RideState.CSTOP
        if (m != KeyMode.MAINT) maintBrakeOpen.fill(false)

        for (b in 0 until n) {
            val blk = profile.blocks[b]
            val open = when (blk.kind) {
                BlockKind.LAUNCH -> launchState == LaunchState.FIRING || launchState == LaunchState.SWING ||
                    launchState == LaunchState.VERIFY
                BlockKind.LIFT -> false
                else -> (releaseLatch[b] && autoRelease) || manualOpen[b]
            } || (maint && maintBrakeOpen[b])
            plant.brakes[b].cmdOpen = open && !eStopped && !(b == profile.transferBlock && transferState != TransferState.IDLE)
            plant.tiresCmd[b] = !eStopped
        }

        // ---------- Lift ----------
        if (profile.type == ProfileType.LIFT) {
            val crestHold = crestPx && !nextClear(1)
            liftCrestHold = crestHold
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
            runLaunch(dt, running, manualOk, manualOk && launchRelease)
        }

        // ---------- Transfer ----------
        runTransfer(dt)

        statusText = when {
            eStopped -> when {
                inputs.loadStandEstop -> "E-STOP at LOAD STAND – twist-release (press and hold), then FAULT RESET"
                inputs.unloadStandEstop -> "E-STOP at UNLOAD STAND – twist-release (press and hold), then FAULT RESET"
                inputs.estopPressed -> "E-STOP at CONSOLE – twist-release (press and hold), then FAULT RESET"
                else -> "Press FAULT RESET"
            }
            hasC && state != RideState.RUNNING -> "Fault active – correct and reset"
            state == RideState.STARTING -> "Start warning – ride starting"
            state == RideState.CSTOP -> "Controlled stop – trains stopping in blocks"
            state == RideState.STOPPED && m == KeyMode.AUTO -> "Ready – press RIDE START"
            state == RideState.STOPPED && m == KeyMode.MANUAL -> "Manual – hold block release to move trains"
            state == RideState.STOPPED && m == KeyMode.MAINT -> "Maintenance – interlocks bypassed"
            releaseLatch[0] -> "Train departing station"
            dispatchArmed -> "Dispatch held – waiting for ${profile.blocks[courseBlock].tag} ${profile.blocks[courseBlock].name} clear"
            dispatchReady -> "READY TO DISPATCH"
            inStation -> "Loading – " + (permissives.firstOrNull { !it.ok }?.label?.let { "waiting: $it" } ?: "")
            else -> "Waiting for train"
        }
    }

    private var launchRequest = false

    private fun runLaunch(dt: Double, running: Boolean, manual: Boolean, manualRelease: Boolean) {
        launchT += dt
        val ready = occ[1] != -1 && plant.trainAtStop(1)?.let { abs(it.v) < 0.05 } == true
        if (eStopped && launchState != LaunchState.ROLLBACK) launchState = LaunchState.IDLE
        when (launchState) {
            LaunchState.IDLE -> {
                if (ready && nextClear(1) && lsmReady && (running || manualRelease) && (!relaunchHold || relaunchArmed)) launchRequest = true
                if (!ready || !lsmReady || (!running && !manual)) launchRequest = false
                // Swing launch: slide the switch track to the spike line first; charge once it is locked there.
                if (launchRequest) {
                    if (!hasSwitch || switchSpikeLs) { launchState = LaunchState.CHARGING; launchT = 0.0; launchRequest = false }
                    else if (!switchCmd) { switchCmd = true; alarms.info("SW", "Switch track to SPIKE line") }
                }
            }
            LaunchState.CHARGING -> if (launchT > 3.0) {
                if (relaunchHold) { relaunchHold = false; relaunchArmed = false }
                launchState = LaunchState.FIRING; launchT = 0.0
                launchTrain = occ[1]; launchExitSeen = plant.launchExitEvent
                launchStage = if (swing != null) 0 else 2
                alarms.info("LSM", "Launch – train $launchTrain" + if (swing != null) " (swing launch, forward)" else "")
            }
            // Swing launch: fire forward, let the train fall back off the top hat, fire backward up the spike,
            // then fire forward again as it swings back through the launch track.
            LaunchState.SWING -> {
                val tr = plant.trains.firstOrNull { it.id == launchTrain }
                val onLsm = tr != null && tr.spike <= 0 && profile.inRange(tr.s, profile.launchStart - 2, profile.launchEnd)
                val back = launchStage == 0 && onLsm && tr!!.v < -1
                val fwd = launchStage == 1 && onLsm && tr!!.v > 1
                when {
                    back || fwd -> {
                        launchStage++; launchState = LaunchState.FIRING; launchT = 0.0
                        launchExitSeen = plant.launchExitEvent
                        alarms.info("LSM", if (back) "Swing launch – backward" else "Swing launch – final forward")
                    }
                    launchT > 45.0 -> {
                        enterRollback("Swing launch – train $launchTrain did not return to the launch track")
                    }
                }
            }
            LaunchState.FIRING -> if (launchStage < 2) {
                val v = trainSpeed(launchTrain)
                val reached = abs(v) >= abs(stageTarget()) - 0.3
                if (reached || plant.launchExitEvent != launchExitSeen || launchT > abs(stageTarget()) / 11.0 + 3.0) {
                    launchState = LaunchState.SWING; launchT = 0.0
                }
            } else if (plant.launchExitEvent != launchExitSeen ||
                launchT > profile.launchSpeed / 11.0 + profile.ahead(profile.launchStart, profile.launchEnd) / profile.launchSpeed + 2.0
            ) {
                launchState = LaunchState.VERIFY; launchT = 0.0
                val v = plant.lastLaunchExitSpeed
                val min = profile.launchSpeed * 0.92
                if (v < min) alarms.raise("LSM_SPD", "Launch exit speed low: ${"%.0f".format(Units.mph(v))} mph (min ${"%.0f".format(Units.mph(min))})", Severity.WARNING)
            }
            LaunchState.VERIFY -> when {
                occ[next(1)] == launchTrain -> launchState = LaunchState.IDLE
                launchT > profile.launchVerifySec || trainSpeed(launchTrain) < -0.5 -> {
                    enterRollback("Launch rollback – train $launchTrain failed to crest top hat")
                }
            }
            LaunchState.ROLLBACK -> Unit
        }
        // Return the switch to the station line once the launch is over and the table is clear.
        if (hasSwitch && switchCmd && launchState == LaunchState.IDLE && !ready && !plant.switchOccupied && !eStopped) {
            switchCmd = false
            alarms.info("SW", "Switch track to STATION line")
        }
        plant.switchToSpikeCmd = hasSwitch && switchCmd
        plant.lsmFireCmd = launchState == LaunchState.FIRING && !eStopped
        plant.lsmTarget = stageTarget()
        plant.swingActive = swing != null && !eStopped &&
            (launchState == LaunchState.FIRING || launchState == LaunchState.SWING || launchState == LaunchState.VERIFY)
        plant.lsmPositionCmd = !eStopped && launchState != LaunchState.FIRING && launchState != LaunchState.SWING &&
            launchState != LaunchState.ROLLBACK
    }

    private fun enterRollback(text: String) {
        launchState = LaunchState.ROLLBACK
        rollbacks++
        rollbackInspected = false
        rollbackFalse = false
        rollbackText = text
        alarms.raise("ROLLBACK", text, Severity.CSTOP)
    }

    private val swing get() = profile.swing

    /** LSM set-point for the current launch stage, m/s (negative = backward). */
    private fun stageTarget(): Double = when (launchStage) {
        0 -> swing?.first ?: profile.launchSpeed
        1 -> -(swing?.second ?: profile.launchSpeed)
        else -> profile.launchSpeed
    }

    // ---------- Transfer track (maintenance) ----------

    fun requestTransfer(store: Boolean): String {
        val tb = profile.transferBlock
        if (inputs.mode != KeyMode.MAINT) return "Transfer requires MAINT mode"
        if (state != RideState.STOPPED) return "Transfer requires ride STOPPED"
        if (transferState != TransferState.IDLE) return "Transfer already in progress"
        if (store) {
            if (occ[tb] == -1 || plant.trainAtStop(tb) == null) return "No train stopped on transfer section"
        } else {
            if (plant.stored == 0) return "No trains in storage"
            if (occ[tb] != -1 || plant.trains.any { profile.blockAt(it.s).index == tb }) return "Transfer section not clear"
        }
        transferStoring = store
        transferState = TransferState.UNLOCK
        transferT = 0.0
        alarms.info("XFER", if (store) "Transfer: storing train ${occ[tb]}" else "Transfer: retrieving train from storage")
        return "Transfer started"
    }

    private fun runTransfer(dt: Double) {
        if (transferState == TransferState.IDLE) return
        val tb = profile.transferBlock
        if (eStopped) return // hold position
        transferT += dt
        fun nextStep(s: TransferState, after: Double) { if (transferT > after) { transferState = s; transferT = 0.0 } }
        when (transferState) {
            TransferState.UNLOCK -> nextStep(TransferState.SHIFT_OUT, 2.0)
            TransferState.SHIFT_OUT -> nextStep(TransferState.MOVE, 4.0)
            TransferState.MOVE -> if (transferT > 1.0) {
                if (transferStoring) {
                    if (plant.storeTrainFrom(tb)) occ[tb] = -1
                } else if (plant.retrieveTrainTo(tb)) {
                    occ[tb] = plant.trains.last().id
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
            occ[b] != -1 && stationZone(b) && occ[p] != -1 && pending[b] == -1 &&
                (leaving[b] || (releaseLatch[b] && latchedTrain[b] == occ[b])) -> {
                // Moving block: following the departing train in; it takes over the block when that train clears.
                pending[b] = occ[p]
                leaving[p] = true
            }
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
            occ[p] = pending[p]
            pending[p] = -1
            leaving[p] = false
        }
    }

    /** Re-derive block occupancy from train positions (maintenance "train verify"). */
    fun resyncTracking() {
        occ.fill(-1)
        pending.fill(-1)
        leaving.fill(false)
        releaseLatch.fill(false)
        for (tr in plant.trains) {
            val blk = profile.blockAt(tr.s)
            val b = blk.index
            val other = plant.trains.firstOrNull { it.id == occ[b] }
            when {
                other == null -> occ[b] = tr.id
                // Two trains in one moving-block station zone: the one further along owns it, the other follows.
                profile.ahead(blk.start, tr.s) > profile.ahead(blk.start, other.s) -> { pending[b] = other.id; occ[b] = tr.id }
                else -> pending[b] = tr.id
            }
        }
        for (b in 0 until n) prevSensor[b] = entry(b)
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

enum class TrainStatus { GOOD, CHECKING, BAD }
