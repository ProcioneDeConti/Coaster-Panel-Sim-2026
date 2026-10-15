package com.abemu.coaster.sim

import kotlin.math.hypot

enum class ProfileType(val label: String) { LIFT("Lift Hill"), LAUNCH("LSM Launch") }

enum class BlockKind { STATION, LIFT, LAUNCH, BRAKE_RUN, UNLOAD }

/**
 * A block zone on the circuit. Positions are metres along the track, measured at the train's
 * front. A block may wrap past the end of the circuit (start > end).
 *
 * @param stopPos where the block's exit brake stops a train (null for the lift).
 * @param brakeStart where the brake / drive-tire section begins.
 * @param exitLaunch mid-course LSM launch speed at the block exit, or 0.
 */
data class Block(
    val index: Int,
    val tag: String,
    val name: String,
    val kind: BlockKind,
    val start: Double,
    val end: Double,
    val stopPos: Double?,
    val brakeStart: Double?,
    /** If > 0, an LSM past the stop point launches a released train to this speed (m/s). */
    val exitLaunch: Double = 0.0,
    /** Short stacking block between the final brake run and the station (holds one train waiting for the station). */
    val approach: Boolean = false,
)

/** Trim brake: fins that cap the train's speed through [start, end] at [maxSpeed] (m/s). */
data class Trim(val name: String, val start: Double, val end: Double, val maxSpeed: Double)

/** Extra proximity sensor (field input) at track position [s]. */
data class Prox(val name: String, val s: Double)

/** A fully built ride: track geometry, block layout and train/drive parameters. */
class RideProfile(
    val spec: CoasterSpec,
    val length: Double,
    /** Elevation profile as (s, h) points, ascending s, first at 0 and last at [length]. */
    private val elevation: List<Pair<Double, Double>>,
    val blocks: List<Block>,
    /** Plan (top-down) layout anchors: (s, x, y) in a 100 x 56 box, ascending s, closed loop. */
    private val plan: List<Triple<Double, Double, Double>>,
    val liftStart: Double = 0.0,
    val liftEnd: Double = 0.0,
    val liftCrestSensor: Double = 0.0,
    val launchStart: Double = 0.0,
    val launchEnd: Double = 0.0,
    val crestSensor: Double = 0.0,
    /** Speed that only reaches ~80 % of the top hat, used by the "launch underspeed" fault. */
    val rollbackSpeed: Double = 0.0,
    /** Transfer block set by an editor tag, else the last brake run. */
    private val transferOverride: Int? = null,
    val trims: List<Trim> = emptyList(),
    val sensors: List<Prox> = emptyList(),
) {
    val type get() = spec.type
    val name get() = spec.name
    val liftSpeed get() = spec.liftSpeed
    val launchSpeed get() = spec.launchSpeed
    /** Swing launch speeds (forward, backward), or null for a single launch. */
    val swing get() = spec.swing
    val spikeHeight get() = spec.spikeHeight
    /**
     * Back end of the sliding switch track, where the spike line leaves. The switch table spans
     * [spikeJunction, switchEnd], clear of a train standing at the launch position.
     */
    val spikeJunction get() = launchStart - trainLength - 11.0
    val switchEnd get() = spikeJunction + 8.0

    /** Plan-view spike line from the switch back to the spike tower (real layouts only). */
    val planSpike: List<Pair<Double, Double>> get() = CustomRides.layout(spec.id)?.spike.orEmpty()

    /** Plan-view position [d] metres up the rear spike: along [planSpike], scaled so the tower top is [spikeLength]. */
    fun spikePlanPoint(d: Double): Pair<Double, Double> {
        val pts = listOf(planPoint(spikeJunction)) + planSpike.drop(1)
        if (pts.size < 2) return pts[0]
        val seg = pts.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }
        var want = (d / spikeLength).coerceIn(0.0, 1.0) * seg.sum()
        for (i in seg.indices) {
            if (want <= seg[i] || i == seg.lastIndex) {
                val t = if (seg[i] > 0) (want / seg[i]).coerceIn(0.0, 1.0) else 0.0
                return pts[i].first + (pts[i + 1].first - pts[i].first) * t to pts[i].second + (pts[i + 1].second - pts[i].second) * t
            }
            want -= seg[i]
        }
        return pts.last()
    }

    /** Sine of the climb angle [d] metres up the rear spike: a short curve into the vertical. */
    fun spikeSin(d: Double): Double = (0.05 + d / 45.0).coerceAtMost(0.99)

    /** Height above the launch track [d] metres up the rear spike. */
    fun spikeHeightAt(d: Double): Double {
        var x = 0.0; var h = 0.0
        while (x < d) { val step = minOf(0.5, d - x); h += spikeSin(x) * step; x += step }
        return h
    }

    /** Length of spike track before it runs out at [spikeHeight]. */
    val spikeLength: Double by lazy {
        var d = 0.0; var h = 0.0
        while (h < spikeHeight) { h += spikeSin(d) * 0.5; d += 0.5 }
        d
    }
    val rows get() = spec.cars
    val ridersPerTrain get() = spec.cars * spec.ridersPerCar
    val trainLength get() = spec.cars * 2.0 + 2.0
    /**
     * Lowest launch exit speed that carries a train over the top hat against a steady [headwind] (m/s), found by
     * running the climb with the plant's free-rolling physics (gravity, rolling resistance, drag on the airspeed).
     */
    fun crestSpeedNeeded(headwind: Double = 0.0): Double = crestCache.getOrPut(headwind) {
        fun crests(v0: Double): Boolean {
            var s = launchEnd; var v = v0; val dt = 0.01
            while (ahead(launchEnd, s) < ahead(launchEnd, crestSensor)) {
                val sinA = slope(s).coerceIn(-0.95, 0.95)
                val air = v + headwind
                v += (-G * sinA - 0.012 * G - 0.0004 * air * kotlin.math.abs(air)) * dt
                if (v <= 0) return false
                s = wrap(s + v * dt)
            }
            return true
        }
        var lo = 0.0; var hi = launchSpeed * 1.5
        repeat(30) { val mid = (lo + hi) / 2; if (crests(mid)) hi = mid else lo = mid }
        hi
    }
    private val crestCache = HashMap<Double, Double>()

    /** Seconds allowed after launch for the train to reach the MCBR block before declaring rollback. */
    val launchVerifySec get() = 7.0 + spec.height / 15.0

    fun wrap(s: Double): Double = ((s % length) + length) % length

    /** Forward distance from a to b around the circuit. */
    fun ahead(a: Double, b: Double): Double = wrap(b - a)

    fun inRange(s: Double, a: Double, b: Double): Boolean {
        val p = wrap(s)
        return if (a <= b) p >= a && p < b else p >= a || p < b
    }

    fun height(s: Double): Double {
        val p = wrap(s)
        for (i in 1 until elevation.size) {
            val (s1, h1) = elevation[i]
            if (p <= s1) {
                val (s0, h0) = elevation[i - 1]
                return h0 + (h1 - h0) * (p - s0) / (s1 - s0)
            }
        }
        return elevation.last().second
    }

    fun slope(s: Double): Double = (height(s + 0.5) - height(s - 0.5))

    fun blockAt(s: Double): Block = blocks.first { inRange(s, it.start, it.end) }

    /** Block holding the transfer track: the last brake run before the station(s). */
    val transferBlock: Int get() = transferOverride ?: blocks.last { it.kind == BlockKind.BRAKE_RUN && !it.approach }.index

    /** Most trains the ride can run: one block must always be free for trains to move. */
    val maxTrains: Int get() = minOf(initialStops.size, 4).let { if (blocks.size <= 4) minOf(it, 3) else it }

    /** Where trains sit at power-up: load station first, then working back from the station. */
    val initialStops: List<Double>
        get() = listOf(blocks[0].stopPos!!) + blocks.drop(2).reversed().mapNotNull { it.stopPos }

    val maxHeight: Double = elevation.maxOf { it.second }

    fun elevationPoints() = elevation

    private val planSorted = plan.zipWithNext().all { (a, b) -> a.first <= b.first }

    /** Last plan anchor at or before [p] (binary search when anchors are ordered, as they normally are). */
    private fun planIndex(p: Double): Int {
        if (!planSorted) return plan.indexOfLast { it.first <= p }
        var lo = 0
        var hi = plan.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (plan[mid].first <= p) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Top-down position of track point [s] in the 100 x 56 plan box (Catmull-Rom through the anchors). */
    fun planPoint(s: Double): Pair<Double, Double> {
        val p = wrap(s)
        val n = plan.size
        var i = planIndex(p)
        if (i < 0) i = n - 1
        val a = plan[i]
        val b = plan[(i + 1) % n]
        val s0 = a.first
        var s1 = b.first
        if (s1 <= s0) s1 += length
        var q = p
        if (q < s0) q += length
        val t = ((q - s0) / (s1 - s0)).coerceIn(0.0, 1.0)
        val p0 = plan[(i - 1 + n) % n]
        val p3 = plan[(i + 2) % n]
        fun cr(v0: Double, v1: Double, v2: Double, v3: Double) =
            0.5 * (2 * v1 + (-v0 + v2) * t + (2 * v0 - 5 * v1 + 4 * v2 - v3) * t * t + (-v0 + 3 * v1 - 3 * v2 + v3) * t * t * t)
        return cr(p0.second, a.second, b.second, p3.second) to cr(p0.third, a.third, b.third, p3.third)
    }

    /** Same track with a different block layout (used by editor tags). */
    fun withBlocks(
        blocks: List<Block>, transfer: Int? = null, elevation: List<Pair<Double, Double>>? = null,
        trims: List<Trim> = emptyList(), sensors: List<Prox> = emptyList(),
    ) = RideProfile(
        spec, length, elevation ?: this.elevation, blocks, plan, liftStart, liftEnd, liftCrestSensor,
        launchStart, launchEnd, crestSensor, rollbackSpeed, transfer, trims, sensors,
    )

    companion object {
        fun create(spec: CoasterSpec): RideProfile = CoasterLibrary.build(spec)
        fun create(type: ProfileType): RideProfile = create(CoasterLibrary.default(type))
    }
}
