package com.abemu.coaster.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

const val G = 9.81
/** Station / brake-run drive tire transport speed, m/s. */
const val TIRE_SPEED = 4.0

class Train(val id: Int, var s: Double, rows: Int) {
    var v = 0.0
    /** Metres up the rear spike of a swing launch (0 = on the circuit). While > 0, [s] stays at the junction. */
    var spike = 0.0
    /** Part of the train is on the spike line (behind the junction), so its body stops at the junction. */
    var tailOnSpike = false
    /** Physical lock state of each restraint row. */
    val rowLocked = BooleanArray(rows) { true }
}

/** Pneumatic brake: spring-applied, air-released (fail-safe closed). */
class BrakeUnit {
    var cmdOpen = false
    var openFrac = 0.0
    var stuckOpen = false
    var stuckClosed = false
    val closedProx get() = openFrac < 0.05
    val openProx get() = openFrac > 0.95
    val isOpen get() = openFrac > 0.5
}

/** Faults the instructor can inject from the Sim screen. */
class InjectedFaults {
    var safetyCircuit = false
    var airLeak = false
    var liftVfd = false
    var liftOverload = false
    var lsmFault = false
    var launchUnderspeed = false
    var gateStuck = false
    /** Swing-launch switch track jammed where it is. */
    var switchStuck = false
    var restraintRow: Int = -1
    val sensorStuckOff = mutableSetOf<Int>()
    val sensorStuckOn = mutableSetOf<Int>()
    /** Station index (see [SafetyController.stations]) whose E-Stop channel B is welded closed, or -1. */
    var estopWeldedB: Int = -1
    var safetyIoFault = false
}

/**
 * The physical ride: trains, track, and field devices. The PLC drives it through output fields
 * and reads it only through the sensor functions.
 */
class Plant(val profile: RideProfile, activeTrains: Int, totalTrains: Int = 3) {
    val trains = mutableListOf<Train>()
    var stored = totalTrains - activeTrains
    val brakes = List(profile.blocks.size) { BrakeUnit() }
    val faults = InjectedFaults()

    // ---- Outputs written by the PLC ----
    var liftRunCmd = false
    var lsmFireCmd = false
    var lsmPositionCmd = false
    /** LSM speed set-point for the current launch stage, m/s; negative launches backward. */
    var lsmTarget = 0.0
    /** Swing launch in progress: rollback magnets and launch brakes are bypassed so the train can swing. */
    var swingActive = false
    /** Switch track drive: true = slide to the spike line, false = station line. */
    var switchToSpikeCmd = false
    val tiresCmd = BooleanArray(profile.blocks.size)
    var gatesOpenCmd = false
    var restraintUnlockCmd = false
    /** Seconds since the restraints were commanded closed (-1 = not closing), and each row's lock time. */
    private var lockT = -1.0
    private val lockAt = DoubleArray(profile.rows)
    var compressorEnabled = true
    /** Safety output power to brake valves and drive enables (STO). Off = brakes close, drives stop. */
    var safetyPower = true

    // ---- Physical state ----
    var chainSpeed = 0.0
    var gatePos = 0.0 // 0 closed .. 1 open
    /** Switch track table: 0 = aligned to the station line, 1 = aligned to the spike line. */
    var switchPos = 0.0
    /** A train reached the switch track while it was not aligned to its line. */
    var switchOverrun = false
    var airPsi = 105.0
    var collision = false
    var lastLaunchExitSpeed = 0.0
    var launchExitEvent = 0

    val liftChainSpeed get() = profile.liftSpeed
    val launchSpeed get() = if (faults.launchUnderspeed && !profile.spec.lsmLift) profile.rollbackSpeed else profile.launchSpeed
    /** LSM thrust, m/s². An underspeed fault on an LSM lift leaves too little thrust to hold the incline. */
    private val launchAccel get() = if (faults.launchUnderspeed && profile.spec.lsmLift) 5.0 else 11.0

    init {
        val stops = profile.initialStops
        for (i in 0 until minOf(activeTrains, stops.size)) trains += Train(i + 1, stops[i], profile.rows)
    }

    // ---- Sensors ----

    /** True if any train body covers track position [pos]. */
    fun bodyAt(pos: Double): Boolean = trains.any {
        it.spike <= 0 && profile.ahead(pos, it.s) <= profile.trainLength &&
            (!it.tailOnSpike || profile.ahead(profile.spikeJunction, pos) <= profile.ahead(profile.spikeJunction, it.s))
    }

    fun proxSensor(i: Int): Boolean = bodyAt(profile.sensors[i].s)

    fun entrySensor(block: Int): Boolean {
        if (block in faults.sensorStuckOn) return true
        if (block in faults.sensorStuckOff) return false
        return bodyAt(profile.blocks[block].start)
    }

    fun trainAtStop(block: Int): Train? {
        val stop = profile.blocks[block].stopPos ?: return null
        return trains.firstOrNull { abs(signedDist(it.s, stop)) < 1.5 }
    }

    fun trainNear(pos: Double, window: Double): Train? =
        trains.firstOrNull { abs(signedDist(it.s, pos)) < window }

    fun stationTrain(): Train? = trainAtStop(0)?.takeIf { abs(it.v) < 0.05 }

    fun rowLockSensor(row: Int): Boolean {
        val t = stationTrain() ?: return false
        if (row == faults.restraintRow) return false
        return t.rowLocked[row]
    }

    val gateClosedSensor get() = gatePos < 0.03 && !faults.gateStuck
    val switchStationLs get() = switchPos < 0.01
    val switchSpikeLs get() = switchPos > 0.99

    /** Any part of a train on the switch table (including the first train-length of the spike line). */
    val switchOccupied: Boolean get() {
        if (profile.spikeHeight <= 0) return false
        val a = profile.spikeJunction
        return trains.any { t ->
            if (t.spike > 0) t.spike < profile.trainLength + 0.5
            else profile.inRange(t.s, a, a + 8.0 + profile.trainLength)
        }
    }

    /** Signed distance from a to b (b - a), wrapped into (-L/2, L/2]. */
    fun signedDist(a: Double, b: Double): Double {
        val d = profile.ahead(a, b)
        return if (d > profile.length / 2) d - profile.length else d
    }

    // ---- Simulation step ----

    fun step(dt: Double) {
        updateDevices(dt)
        for (t in trains) stepTrain(t, dt)
        checkSpacing()
    }

    private fun updateDevices(dt: Double) {
        // Air: compressor tops up; leak fault bleeds faster than the compressor can supply.
        val supply = if (compressorEnabled) 6.0 else 0.0
        val leak = if (faults.airLeak) 14.0 else 0.4
        airPsi = (airPsi + (supply - leak) * dt).coerceIn(0.0, 110.0)
        if (airPsi >= 110.0 && !faults.airLeak) airPsi = 110.0

        for (b in brakes) {
            val target = when {
                b.stuckOpen -> 1.0
                b.stuckClosed -> 0.0
                b.cmdOpen && safetyPower && airPsi > 65.0 -> 1.0
                else -> 0.0
            }
            b.openFrac = approach(b.openFrac, target, dt / 0.4)
        }

        val chainTarget = if (liftRunCmd && safetyPower && !faults.liftVfd && !faults.liftOverload) liftChainSpeed else 0.0
        chainSpeed = approach(chainSpeed, chainTarget, 2.5 * dt)

        if (profile.spikeHeight > 0 && safetyPower && !faults.switchStuck && !switchOccupied) {
            switchPos = approach(switchPos, if (switchToSpikeCmd) 1.0 else 0.0, dt / 5.0)
        }

        gatePos = if (faults.gateStuck) max(gatePos, 0.5)
        else approach(gatePos, if (gatesOpenCmd && airPsi > 50) 1.0 else 0.0, dt / 1.5)

        stationTrain()?.let { t ->
            if (restraintUnlockCmd) { t.rowLocked.fill(false); lockT = -1.0 }
            else if (t.rowLocked.any { !it }) {
                // Restraints close: rows lock one after another in a random order, as riders pull them down.
                if (lockT < 0) {
                    var at = 0.2
                    for (r in t.rowLocked.indices.shuffled()) { at += 0.15 + Math.random() * 0.45; lockAt[r] = at }
                    lockT = 0.0
                }
                lockT += dt
                for (r in t.rowLocked.indices) if (lockT >= lockAt[r]) t.rowLocked[r] = true
            } else lockT = -1.0
        }
    }

    private fun stepTrain(t: Train, dt: Double) {
        val p = profile
        if (t.spike > 0) { stepSpike(t, dt); return }
        val block = p.blockAt(t.s)
        val brake = brakes[block.index]
        val sinA = p.slope(t.s).coerceIn(-0.95, 0.95)
        val gravityA = -G * sinA
        val resist = 0.012 * G + 0.0004 * t.v * t.v

        fun freeRoll() {
            if (abs(t.v) < 0.05 && abs(gravityA) <= 0.012 * G) {
                t.v = 0.0
            } else {
                t.v += (gravityA - sign(t.v.takeIf { it != 0.0 } ?: gravityA) * resist) * dt
            }
        }

        when {
            // Lift chain engaged: train moves at chain speed, anti-rollbacks prevent reverse.
            p.type == ProfileType.LIFT && block.kind == BlockKind.LIFT &&
                p.inRange(t.s, p.liftStart, p.liftEnd) -> {
                t.v = chainSpeed
            }

            // LSM launch firing (backward for the middle stage of a swing launch).
            block.kind == BlockKind.LAUNCH && lsmFireCmd && safetyPower && !faults.lsmFault &&
                p.inRange(t.s, p.launchStart - 2, p.launchEnd) && (if (lsmTarget >= 0) t.v >= -0.1 else t.v <= 0.1) -> {
                val target = if (lsmTarget >= p.launchSpeed - 0.01) launchSpeed else lsmTarget
                // Net of gravity, so an incline (LSM lift) climbs on thrust minus weight.
                t.v = if (target >= 0) min(target, t.v + (launchAccel + gravityA) * dt) else max(target, t.v - (launchAccel - gravityA) * dt)
            }

            // LSM positioning: returns a train on the launch track to the launch position.
            block.kind == BlockKind.LAUNCH && lsmPositionCmd && safetyPower && !faults.lsmFault && !lsmFireCmd &&
                p.inRange(t.s, p.launchStart - 10, p.launchEnd) && abs(t.v) < 2.5 -> {
                val err = signedDist(t.s, p.launchStart)
                t.v = if (abs(err) < 0.05) 0.0 else (err * 0.8).coerceIn(-1.5, 1.5)
            }

            // Permanent-magnet rollback brakes on the launch track.
            // LSM lift incline: anti-rollback dogs hold the train like a chain lift.
            p.spec.lsmLift && block.kind == BlockKind.LAUNCH && t.v <= 0 && p.slope(t.s) > 0.1 &&
                p.inRange(t.s, p.launchStart, p.launchEnd) -> t.v = 0.0

            block.kind == BlockKind.LAUNCH && !swingActive && t.v < 0 && p.inRange(t.s, block.start, p.launchEnd) -> {
                t.v = min(0.0, t.v + 8.0 * dt)
            }

            // Brake / drive-tire section of a braked block.
            block.brakeStart != null && block.stopPos != null && inBrakeSection(block, t.s) &&
                !(block.kind == BlockKind.LAUNCH && swingActive) -> {
                val toStop = p.ahead(block.brakeStart, block.stopPos) - p.ahead(block.brakeStart, t.s)
                val tires = tiresCmd[block.index]
                if (!brake.isOpen) {
                    val target = sqrt(2 * 6.0 * max(toStop, 0.0))
                    // Drive tires carry the train in at transport speed, easing off into the stop.
                    val tireTarget = min(TIRE_SPEED, max(0.4, sqrt(2 * 1.5 * max(toStop, 0.0))))
                    when {
                        toStop <= 0.05 -> t.v = max(0.0, t.v - 10.0 * dt)
                        t.v > target -> t.v = max(target, t.v - 9.0 * dt)
                        tires && t.v < tireTarget -> t.v = min(tireTarget, t.v + 1.5 * dt)
                    }
                    if (t.v < 0) t.v = 0.0
                } else {
                    val trim = if (block.kind == BlockKind.BRAKE_RUN) 6.0 else 3.0
                    when {
                        // Mid-course LSM launch past the stop point (e.g. a tunnel launch).
                        block.exitLaunch > 0 && toStop <= 0.5 && safetyPower && !faults.lsmFault ->
                            t.v = min(block.exitLaunch, t.v + 11.0 * dt)
                        t.v > trim -> t.v = max(trim, t.v - 4.0 * dt)
                        tires && t.v < TIRE_SPEED -> t.v = min(TIRE_SPEED, t.v + 1.5 * dt)
                        else -> freeRoll()
                    }
                }
            }

            else -> freeRoll()
        }
        // Trim brake fins (passive, always active).
        p.trims.firstOrNull { p.inRange(t.s, it.start, it.end) }?.let { tr ->
            if (t.v > tr.maxSpeed) t.v = max(tr.maxSpeed, t.v - 5.0 * dt)
        }

        val before = t.s
        t.s = p.wrap(t.s + t.v * dt)
        if (p.spikeHeight > 0) {
            val j = p.spikeJunction
            val fromJ = p.ahead(j, t.s)
            if (t.spike > 0 || (t.v < -1 && switchSpikeLs && fromJ < p.trainLength && block.kind == BlockKind.LAUNCH)) t.tailOnSpike = true
            else if (fromJ >= p.trainLength && fromJ < p.length / 2) t.tailOnSpike = false
            fun crossed(x: Double) = abs(signedDist(x, before)) < 10 && signedDist(x, before) * signedDist(x, t.s) < 0
            // Switch table must be aligned to the line the train is coming from: forward from the station
            // needs the station line, forward off the spike or backward from the launch track needs the spike line. Otherwise the
            // train runs into the end of the table.
            val blocked = when {
                t.v > 0 && crossed(j) -> if (t.tailOnSpike) !switchSpikeLs else !switchStationLs
                t.v < 0 && crossed(p.switchEnd) -> !switchSpikeLs
                else -> false
            }
            if (blocked) {
                switchOverrun = true
                t.s = before
                t.v = 0.0
                return
            }
            // Rolling backward off the switch table: onto the rear spike.
            if (t.v < -1 && block.kind == BlockKind.LAUNCH && signedDist(j, before) >= 0 && signedDist(j, t.s) < 0) {
                t.spike = -signedDist(j, t.s)
                t.s = j
                return
            }
        }

        if (block.kind == BlockKind.LAUNCH && p.inRange(before, p.launchEnd - 3, p.launchEnd) &&
            !p.inRange(t.s, p.launchEnd - 3, p.launchEnd) && t.v > 0
        ) {
            lastLaunchExitSpeed = t.v
            launchExitEvent++
        }
    }

    /** On the rear spike: negative v climbs; gravity pulls the train back down to the launch track. */
    private fun stepSpike(t: Train, dt: Double) {
        val resist = 0.012 * G + 0.0004 * t.v * t.v
        t.v += (G * profile.spikeSin(t.spike) - sign(t.v) * resist) * dt
        t.spike -= t.v * dt
        if (t.spike >= profile.spikeLength) { t.spike = profile.spikeLength; t.v = max(t.v, 0.0) }
        if (t.spike <= 0) {
            t.s = profile.wrap(profile.spikeJunction - t.spike)
            t.spike = 0.0
        }
    }

    private fun inBrakeSection(block: Block, s: Double): Boolean {
        val end = if (block.kind == BlockKind.LAUNCH) block.stopPos!! + 5 else block.end
        return profile.inRange(s, block.brakeStart!!, end)
    }

    private fun checkSpacing() {
        if (trains.size < 2) return
        for (a in trains) for (b in trains) {
            if (a === b || a.spike > 0 || b.spike > 0) continue
            val gap = profile.ahead(a.s, b.s) // b ahead of a
            if (gap < profile.trainLength + 0.3) {
                collision = true
                a.s = profile.wrap(b.s - profile.trainLength - 0.3)
                a.v = min(a.v, b.v)
            }
        }
    }

    // ---- Transfer track / storage ----

    fun storeTrainFrom(block: Int): Boolean {
        val t = trainAtStop(block)?.takeIf { abs(it.v) < 0.05 } ?: return false
        trains.remove(t)
        stored++
        return true
    }

    fun retrieveTrainTo(block: Int): Boolean {
        if (stored <= 0) return false
        val pos = profile.blocks[block].stopPos ?: return false
        val id = (1..9).first { n -> trains.none { it.id == n } }
        trains += Train(id, pos, profile.rows)
        stored--
        return true
    }

    private fun approach(x: Double, target: Double, rate: Double): Double =
        if (x < target) min(target, x + rate) else max(target, x - rate)
}
