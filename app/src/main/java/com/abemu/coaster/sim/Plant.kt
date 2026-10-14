package com.abemu.coaster.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

const val G = 9.81

class Train(val id: Int, var s: Double, rows: Int) {
    var v = 0.0
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
    var restraintRow: Int = -1
    val sensorStuckOff = mutableSetOf<Int>()
    val sensorStuckOn = mutableSetOf<Int>()
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
    val tiresCmd = BooleanArray(profile.blocks.size)
    var gatesOpenCmd = false
    var restraintUnlockCmd = false
    var compressorEnabled = true

    // ---- Physical state ----
    var chainSpeed = 0.0
    var gatePos = 0.0 // 0 closed .. 1 open
    var airPsi = 105.0
    var collision = false
    var lastLaunchExitSpeed = 0.0
    var launchExitEvent = 0

    val liftChainSpeed get() = profile.liftSpeed
    val launchSpeed get() = if (faults.launchUnderspeed) profile.rollbackSpeed else profile.launchSpeed

    init {
        val stops = buildList {
            add(profile.blocks[0].stopPos!!)
            add(profile.blocks[3].stopPos!!)
            add(profile.blocks[2].stopPos!!)
        }
        for (i in 0 until activeTrains) trains += Train(i + 1, stops[i], profile.rows)
    }

    // ---- Sensors ----

    /** True if any train body covers track position [pos]. */
    fun bodyAt(pos: Double): Boolean = trains.any { profile.ahead(pos, it.s) <= profile.trainLength }

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
                b.cmdOpen && airPsi > 65.0 -> 1.0
                else -> 0.0
            }
            b.openFrac = approach(b.openFrac, target, dt / 0.4)
        }

        val chainTarget = if (liftRunCmd && !faults.liftVfd && !faults.liftOverload) liftChainSpeed else 0.0
        chainSpeed = approach(chainSpeed, chainTarget, 2.5 * dt)

        gatePos = if (faults.gateStuck) max(gatePos, 0.5)
        else approach(gatePos, if (gatesOpenCmd && airPsi > 50) 1.0 else 0.0, dt / 1.5)

        stationTrain()?.let { t ->
            if (restraintUnlockCmd) t.rowLocked.fill(false)
            else for (r in t.rowLocked.indices) if (!t.rowLocked[r] && Math.random() < dt * 1.5) t.rowLocked[r] = true
        }
    }

    private fun stepTrain(t: Train, dt: Double) {
        val p = profile
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

            // LSM launch firing.
            block.kind == BlockKind.LAUNCH && lsmFireCmd && !faults.lsmFault && t.v >= -0.1 &&
                p.inRange(t.s, p.launchStart - 2, p.launchEnd) -> {
                t.v = min(launchSpeed, t.v + 11.0 * dt)
            }

            // LSM positioning: returns a train on the launch track to the launch position.
            block.kind == BlockKind.LAUNCH && lsmPositionCmd && !faults.lsmFault && !lsmFireCmd &&
                p.inRange(t.s, p.launchStart - 10, p.launchEnd) && abs(t.v) < 2.5 -> {
                val err = signedDist(t.s, p.launchStart)
                t.v = if (abs(err) < 0.05) 0.0 else (err * 0.8).coerceIn(-1.5, 1.5)
            }

            // Permanent-magnet rollback brakes on the launch track.
            block.kind == BlockKind.LAUNCH && t.v < 0 && p.inRange(t.s, block.start, p.launchEnd) -> {
                t.v = min(0.0, t.v + 8.0 * dt)
            }

            // Brake / drive-tire section of a braked block.
            block.brakeStart != null && block.stopPos != null && inBrakeSection(block, t.s) -> {
                val toStop = p.ahead(block.brakeStart, block.stopPos) - p.ahead(block.brakeStart, t.s)
                val tires = tiresCmd[block.index]
                if (!brake.isOpen) {
                    val target = sqrt(2 * 6.0 * max(toStop, 0.0))
                    when {
                        toStop <= 0.05 -> t.v = max(0.0, t.v - 10.0 * dt)
                        t.v > target -> t.v = max(target, t.v - 9.0 * dt)
                        tires && t.v < min(target, 1.5) -> t.v = min(min(target, 1.5), t.v + 1.2 * dt)
                    }
                    if (t.v < 0) t.v = 0.0
                } else {
                    val trim = if (block.kind == BlockKind.BRAKE_RUN) 6.0 else 3.0
                    when {
                        t.v > trim -> t.v = max(trim, t.v - 4.0 * dt)
                        tires && t.v < 2.0 -> t.v = min(2.0, t.v + 1.0 * dt)
                        else -> freeRoll()
                    }
                }
            }

            else -> freeRoll()
        }

        val before = t.s
        t.s = p.wrap(t.s + t.v * dt)

        if (block.kind == BlockKind.LAUNCH && p.inRange(before, p.launchEnd - 3, p.launchEnd) &&
            !p.inRange(t.s, p.launchEnd - 3, p.launchEnd) && t.v > 0
        ) {
            lastLaunchExitSpeed = t.v
            launchExitEvent++
        }
    }

    private fun inBrakeSection(block: Block, s: Double): Boolean {
        val end = if (block.kind == BlockKind.LAUNCH) block.stopPos!! + 5 else block.end
        return profile.inRange(s, block.brakeStart!!, end)
    }

    private fun checkSpacing() {
        if (trains.size < 2) return
        for (a in trains) for (b in trains) {
            if (a === b) continue
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
