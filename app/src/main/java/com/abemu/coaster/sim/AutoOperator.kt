package com.abemu.coaster.sim

import kotlin.random.Random

/**
 * Desk-display mode: a virtual crew that runs the ride on its own in real time. Operators cycle the
 * station (restraints, gates, walk check, ready, dispatch); when the ride faults, maintenance is called,
 * "works" for a while, clears the field faults and recovers the ride. Optionally throws random faults so
 * there is something to watch. Stepped once per PLC scan in simulated seconds.
 */
class AutoOperator(seed: Int = Random.nextInt()) {
    private val rnd = Random(seed)

    enum class Phase(val label: String) {
        POWER_UP("Powering up"), START("Starting ride"), WAIT("Waiting for a train"),
        UNLOAD("Unloading"), BOARD("Boarding"), SECURE("Closing gates and restraints"),
        CHECK("Restraint walk check"), READY("Stands ready"), DISPATCH("Waiting to dispatch"),
        MAINT_CALLED("Maintenance called"), MAINT_WORKING("Maintenance on the job"), RECOVER("Recovering the ride"),
        JOG("Jogging a stalled train in MANUAL"),
    }

    var phase = Phase.POWER_UP; private set
    /** One-line description of what the crew is doing, for the HMI banner and web pages. */
    var status = ""; private set
    /** Inject a random fault every few minutes for maintenance to deal with. */
    var randomFaults = true
    var repairs = 0; private set
    var lastFault = ""; private set

    private var t = 0.0
    private var phaseT = 0.0
    private var repairSec = 0.0
    private var row = 0
    private var jogBlock = -1
    private var jogTrain = -1
    private var pushed = false
    private var nextFaultIn = 300.0
    private val releases = mutableListOf<Pair<Double, () -> Unit>>()
    private var plcRef: RidePlc? = null

    private fun go(p: Phase) { phase = p; phaseT = 0.0 }

    private fun press(set: (Boolean) -> Unit) { set(true); releases += (t + 0.2) to { set(false) } }

    fun reset() { go(Phase.POWER_UP); releases.clear(); nextFaultIn = 240.0 + rnd.nextDouble() * 360 }

    fun update(dt: Double, plc: RidePlc) {
        if (plc !== plcRef) { plcRef = plc; reset() }
        t += dt; phaseT += dt
        releases.removeAll { (at, f) -> (at <= t).also { if (it) f() } }
        val i = plc.inputs
        i.loadFootSwitch = true; i.unloadFootSwitch = true

        if (randomFaults && plc.state == RideState.RUNNING && phase !in MAINT) {
            nextFaultIn -= dt
            if (nextFaultIn <= 0) { injectRandom(plc); nextFaultIn = 360.0 + rnd.nextDouble() * 540 }
        }

        // Anything that stops the ride, or a station cycle that has stalled, brings maintenance out.
        val faulted = plc.state == RideState.ESTOP || plc.state == RideState.CSTOP ||
            plc.alarms.active.any { it.severity.ordinal <= Severity.CSTOP.ordinal }
        val stalled = !plc.weather.hold && (phase in STATION && phaseT > 45 || phase == Phase.DISPATCH && phaseT > 180)
        if (phase !in MAINT && (faulted || stalled)) {
            lastFault = plc.alarms.firstOut?.text ?: plc.alarms.active.firstOrNull()?.text ?: "${phase.label} stalled"
            go(Phase.MAINT_CALLED)
        }

        when (phase) {
            Phase.POWER_UP -> {
                i.mode = KeyMode.AUTO
                i.estopPressed = false; i.loadStandEstop = false; i.unloadStandEstop = false
                if (plc.state == RideState.POWER_OFF) { if (phaseT > 1) { press { i.powerOn = it }; phaseT = -2.0 } }
                else go(Phase.START)
            }
            Phase.START -> when {
                plc.state == RideState.RUNNING -> go(Phase.WAIT)
                plc.state == RideState.STARTING -> Unit
                plc.state == RideState.POWER_OFF -> go(Phase.POWER_UP)
                phaseT > 2 && plc.state == RideState.STOPPED -> {
                    if (plc.resetNeeded) press { i.faultReset = it } else press { i.rideStart = it }
                    phaseT = 0.0
                }
            }
            Phase.WAIT -> when {
                plc.state != RideState.RUNNING && plc.state != RideState.STARTING -> go(Phase.START)
                plc.relaunchHold && !plc.relaunchArmed -> if (phaseT > 3) { plc.authorizeRelaunch(); phaseT = 0.0 }
                plc.dispatchArmed -> phaseT = 0.0 // held for the course block; the train leaves on its own
                plc.trainInStation && !plc.releaseLatch[0] && phaseT > 1.5 -> { press { i.restraintOpen = it }; go(Phase.UNLOAD) }
            }
            Phase.UNLOAD -> if (phaseT > 3) { press { i.gatesOpen = it }; go(Phase.BOARD) }
            Phase.BOARD -> if (phaseT > 12) { press { i.gatesClose = it }; press { i.restraintClose = it }; go(Phase.SECURE) }
            Phase.SECURE -> if (plc.allRowsLocked && plc.plant.gateClosedSensor && phaseT > 2) { row = 0; go(Phase.CHECK) }
            Phase.CHECK -> if (phaseT > 1.2) {
                if (row < plc.profile.rows) { plc.checkCar(row); row++; phaseT = 0.0 }
                else if (plc.carChecked.all { it } || !plc.restraintWalk) {
                    if (!plc.loadReady) press { i.loadReady = it }
                    if (!plc.unloadReady) press { i.unloadReady = it }
                    go(Phase.READY)
                }
            }
            Phase.READY -> if (phaseT > 1) go(Phase.DISPATCH)
            Phase.DISPATCH -> when {
                plc.dispatchReady && phaseT > 1.5 -> {
                    i.dispatchL = true; i.dispatchR = true
                    releases += (t + 0.3) to { i.dispatchL = false; i.dispatchR = false }
                    go(Phase.WAIT)
                }
                plc.weather.hold -> phaseT = 0.0 // hold for weather as long as it takes
                !plc.loadReady || !plc.unloadReady -> if (phaseT > 5) go(Phase.CHECK).also { row = plc.profile.rows }
            }
            Phase.MAINT_CALLED -> if (phaseT > 5) { repairSec = 20.0 + rnd.nextDouble() * 40; go(Phase.MAINT_WORKING) }
            Phase.MAINT_WORKING -> {
                // A train rolling back has to come to rest before anyone can go out to it.
                if (plc.launchState == LaunchState.ROLLBACK && !plc.rollbackAtRest && !plc.rollbackTrainClear && phaseT < 600) phaseT = minOf(phaseT, repairSec - 1)
                if (phaseT > repairSec) { repair(plc); go(Phase.RECOVER) }
            }
            Phase.RECOVER -> when {
                phaseT < 1 -> Unit
                stalledBlock(plc) >= 0 -> {
                    jogBlock = stalledBlock(plc); jogTrain = plc.occ[jogBlock]; pushed = false
                    i.mode = KeyMode.MANUAL
                    go(Phase.JOG)
                }
                plc.resetNeeded && phaseT < 20 -> if (phaseT % 3 < dt) { plc.alarms.acknowledgeAll(); press { i.faultReset = it } }
                plc.resetNeeded -> { lastFault = "reset would not clear"; go(Phase.MAINT_CALLED) }
                else -> { repairs++; go(Phase.POWER_UP) }
            }
            Phase.JOG -> {
                // Stalled short of its stop: in MANUAL, hold the block release (and lift jog) until the train is
                // carried into the next block, or over the lift if that is where it went.
                val tr = plc.plant.trains.firstOrNull { it.id == jogTrain }
                val at = tr?.let { plc.profile.blockAt(it.s) }
                val done = tr == null || at!!.index != jogBlock && at.kind != BlockKind.LIFT ||
                    pushed && plc.plant.trainAtStop(jogBlock)?.id == tr.id
                if (plc.state == RideState.STOPPED && !done && phaseT < 120) {
                    // Re-secure the train first (low air drops the restraint locks); station release needs it.
                    if (phaseT < 1 && phaseT + dt >= 1) { press { i.gatesClose = it }; press { i.restraintClose = it } }
                    if (phaseT > 2 && phaseT < 3 && plc.trainInStation) (0 until plc.profile.rows).forEach { plc.checkCar(it) }
                    if (phaseT > 2 && plc.resetNeeded && phaseT % 3 < dt) { plc.alarms.acknowledgeAll(); press { i.faultReset = it } }
                    i.manualRelease[jogBlock] = phaseT > 3
                    i.liftJog = phaseT > 3
                    // Off the drive tires and short of the next device: the crew winches it back onto the block's stop
                    // (or pushes it on by hand where there is no stop) so the ride can carry on from there.
                    if (phaseT > 15 && tr != null && kotlin.math.abs(tr.v) < 0.05 && at!!.index == jogBlock && !pushed) {
                        pushed = true
                        val stop = plc.profile.blocks[jogBlock].stopPos
                        if (stop != null) { tr.s = stop; tr.v = 0.0; plc.alarms.info("MNT", "Maintenance winched train ${tr.id} back to the ${plc.profile.blocks[jogBlock].tag} stop") }
                        else { tr.v = 1.2; plc.alarms.info("MNT", "Maintenance pushing train ${tr.id} by hand") }
                    }
                } else if (plc.state != RideState.STOPPED && phaseT < 10) {
                    if (phaseT % 3 < dt) { plc.alarms.acknowledgeAll(); press { i.faultReset = it } }
                } else {
                    i.manualRelease.fill(false); i.liftJog = false
                    i.mode = KeyMode.AUTO
                    if (plc.plant.trains.all { kotlin.math.abs(it.v) < 0.05 }) plc.resyncTracking()
                    if (!done) { lastFault = "train would not move in MANUAL"; go(Phase.MAINT_CALLED) } else go(Phase.RECOVER)
                }
            }
        }
        status = when (phase) {
            Phase.MAINT_CALLED, Phase.MAINT_WORKING -> "${phase.label}: $lastFault"
            Phase.DISPATCH -> if (plc.weather.hold) "Weather hold – waiting it out" else phase.label
            else -> phase.label
        }
    }

    /** Maintenance fixes everything on the field side, puts the trains back in sync and signs off. */
    private fun repair(plc: RidePlc) {
        val f = plc.plant.faults
        f.safetyCircuit = false; f.airLeak = false; f.liftVfd = false; f.liftOverload = false; f.lsmFault = false
        f.launchUnderspeed = false; f.lsmDerate = false; f.gateStuck = false; f.switchStuck = false
        f.restraintRow = -1; f.restraintSeat = -1; f.estopWeldedB = -1; f.safetyIoFault = false
        f.sensorStuckOn.clear(); f.sensorStuckOff.clear()
        plc.plant.brakes.forEach { it.stuckOpen = false; it.stuckClosed = false }
        plc.removeAllForces()
        val i = plc.inputs
        i.estopPressed = false; i.loadStandEstop = false; i.unloadStandEstop = false
        if (plc.launchState == LaunchState.ROLLBACK) plc.signOffRollbackInspection()
        if (plc.plant.trains.all { kotlin.math.abs(it.v) < 0.05 }) {
            plc.resyncTracking()
            plc.alarms.info("TRK", "Block tracking re-synchronised by maintenance")
        }
        plc.alarms.info("MNT", "Maintenance: repair complete – $lastFault")
    }

    private fun stalledBlock(plc: RidePlc) = plc.alarms.active.firstOrNull { it.code.startsWith("STALL") }
        ?.code?.removePrefix("STALL")?.toIntOrNull()?.takeIf { plc.occ.getOrNull(it) != null && plc.occ[it] != -1 } ?: -1

    private fun injectRandom(plc: RidePlc) {
        val f = plc.plant.faults
        val stops = plc.profile.blocks.filter { it.stopPos != null && it.index != 0 }
        val options = buildList<Pair<String, () -> Unit>> {
            add("Air leak" to { f.airLeak = true })
            add("Gate stuck open" to { f.gateStuck = true })
            add("Restraint won't lock" to { f.restraintSeat = (0 until plc.profile.rows * plc.profile.spec.ridersPerCar).random(rnd) })
            add("Block sensor failed" to { f.sensorStuckOff += plc.profile.blocks.indices.random(rnd) })
            if (stops.isNotEmpty()) add("Brake stuck closed" to { plc.plant.brakes[stops.random(rnd).index].stuckClosed = true })
            if (plc.profile.type == ProfileType.LIFT) add("Lift VFD fault" to { f.liftVfd = true })
            else add("LSM drive fault" to { f.lsmFault = true })
        }
        val (name, act) = options.random(rnd)
        act()
        plc.alarms.info("SIM", "Random fault (desk mode): $name")
    }

    private companion object {
        val MAINT = setOf(Phase.MAINT_CALLED, Phase.MAINT_WORKING, Phase.RECOVER, Phase.JOG)
        val STATION = setOf(Phase.UNLOAD, Phase.BOARD, Phase.SECURE, Phase.CHECK, Phase.READY)
    }
}
