package com.abemu.coaster.sim

import kotlin.math.abs

/** One recorded signal: a ring buffer of samples. */
class TrendChannel(val name: String, val unit: String, val capacity: Int) {
    private val data = FloatArray(capacity)
    private var count = 0
    private var head = 0

    fun add(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    val size get() = count

    /** Sample [i] where 0 is the oldest kept sample. */
    operator fun get(i: Int): Float = data[(head - count + i + capacity * 2) % capacity]
    fun last(): Float = if (count == 0) 0f else get(count - 1)
    fun clear() { count = 0; head = 0 }
}

/** Historian: samples ride signals every [period] seconds of simulated time (10 minutes kept). */
class TrendRecorder(val period: Double = 0.5) {
    private val cap = (600 / period).toInt()
    val trainSpeed = List(3) { TrendChannel("Train ${it + 1}", "m/s", cap) }
    val drive = TrendChannel("Lift chain / LSM", "m/s", cap)
    val air = TrendChannel("Main air", "psi", cap)
    val liftCurrent = TrendChannel("Lift motor", "A", cap)
    private var acc = 0.0

    fun clear() { (trainSpeed + listOf(drive, air, liftCurrent)).forEach { it.clear() } }

    fun update(plc: RidePlc, dt: Double) {
        acc += dt
        if (acc < period) return
        acc -= period
        val p = plc.plant
        val prof = plc.profile
        for (i in 0 until 3) trainSpeed[i].add(p.trains.firstOrNull { it.id == i + 1 }?.let { abs(it.v).toFloat() } ?: 0f)
        drive.add(
            if (prof.type == ProfileType.LIFT) p.chainSpeed.toFloat()
            else p.trains.maxOfOrNull { if (prof.blockAt(it.s).kind == BlockKind.LAUNCH) abs(it.v) else 0.0 }?.toFloat() ?: 0f,
        )
        air.add(p.airPsi.toFloat())
        liftCurrent.add(liftMotorAmps(plc).toFloat())
    }

    /** Simple drive model: magnetising current when running, plus load while hauling a train. */
    private fun liftMotorAmps(plc: RidePlc): Double {
        val p = plc.plant
        val prof = plc.profile
        if (prof.type != ProfileType.LIFT || p.chainSpeed < 0.05) return 0.0
        val frac = (p.chainSpeed / prof.liftSpeed).coerceIn(0.0, 1.0)
        val loaded = p.trains.any { prof.inRange(it.s, prof.liftStart, prof.liftEnd) }
        val noise = (Math.random() - 0.5) * 6
        return 45.0 * frac + (if (loaded) 170.0 * frac * (prof.spec.cars / 7.0) else 0.0) + noise
    }
}
