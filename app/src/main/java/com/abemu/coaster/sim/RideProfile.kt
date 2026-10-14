package com.abemu.coaster.sim

enum class ProfileType(val label: String) { LIFT("Lift Hill"), LAUNCH("LSM Launch") }

enum class BlockKind { STATION, LIFT, LAUNCH, BRAKE_RUN }

/**
 * A block zone on the circuit. Positions are metres along the track, measured at the train's
 * front. A block may wrap past the end of the circuit (start > end).
 *
 * @param stopPos where the block's exit brake stops a train (null for the lift).
 * @param brakeStart where the brake / drive-tire section begins.
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
    val maxTransitSec: Double,
)

class RideProfile(
    val type: ProfileType,
    val length: Double,
    /** Elevation profile as (s, h) points, ascending s, first at 0 and last at [length]. */
    private val elevation: List<Pair<Double, Double>>,
    val blocks: List<Block>,
    val liftStart: Double = 0.0,
    val liftEnd: Double = 0.0,
    val liftCrestSensor: Double = 0.0,
    val launchStart: Double = 0.0,
    val launchEnd: Double = 0.0,
    val crestSensor: Double = 0.0,
    /** Plan (top-down) layout anchors: (s, x, y) in a 100 x 56 box, ascending s, closed loop. */
    private val plan: List<Triple<Double, Double, Double>> = emptyList(),
) {
    /** Top-down position of track point [s] in the 100 x 56 plan box (Catmull-Rom through the anchors). */
    fun planPoint(s: Double): Pair<Double, Double> {
        val p = wrap(s)
        val n = plan.size
        var i = plan.indexOfLast { it.first <= p }
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

    val trainLength = 16.0
    val rows = 8

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

    val maxHeight: Double = elevation.maxOf { it.second }

    fun elevationPoints() = elevation

    companion object {
        fun create(type: ProfileType): RideProfile = when (type) {
            ProfileType.LIFT -> lift()
            ProfileType.LAUNCH -> launch()
        }

        private fun lift() = RideProfile(
            type = ProfileType.LIFT,
            length = 900.0,
            elevation = listOf(
                0.0 to 2.0, 45.0 to 2.0, 55.0 to 4.0, 140.0 to 45.0, 150.0 to 45.5, 160.0 to 44.0,
                210.0 to 3.0, 235.0 to 20.0, 260.0 to 32.0, 280.0 to 22.0, 300.0 to 8.0,
                325.0 to 20.0, 345.0 to 26.0, 365.0 to 18.0, 390.0 to 10.0, 430.0 to 18.0,
                490.0 to 18.0, 540.0 to 2.0, 570.0 to 9.0, 600.0 to 12.0, 650.0 to 4.0,
                710.0 to 9.0, 760.0 to 3.0, 880.0 to 3.0, 900.0 to 2.0,
            ),
            blocks = listOf(
                Block(0, "B1", "Station", BlockKind.STATION, 880.0, 45.0, 30.0, 880.0, 60.0),
                Block(1, "B2", "Lift Hill", BlockKind.LIFT, 45.0, 150.0, null, null, 50.0),
                Block(2, "B3", "Mid-Course Brake", BlockKind.BRAKE_RUN, 150.0, 490.0, 485.0, 440.0, 45.0),
                Block(3, "B4", "Final Brake / Transfer", BlockKind.BRAKE_RUN, 490.0, 880.0, 875.0, 830.0, 75.0),
            ),
            liftStart = 45.0, liftEnd = 150.0, liftCrestSensor = 130.0,
            plan = listOf(
                Triple(0.0, 26.0, 52.0), Triple(45.0, 42.0, 52.0), Triple(150.0, 82.0, 52.0), Triple(168.0, 91.0, 48.0),
                Triple(188.0, 95.0, 39.0), Triple(210.0, 92.0, 30.0), Triple(238.0, 82.0, 25.0), Triple(262.0, 72.0, 28.0),
                Triple(288.0, 64.0, 33.0), Triple(312.0, 58.0, 25.0), Triple(332.0, 62.0, 17.0), Triple(352.0, 74.0, 13.0),
                Triple(376.0, 88.0, 12.0), Triple(398.0, 93.0, 6.0), Triple(420.0, 84.0, 3.5), Triple(440.0, 72.0, 4.0),
                Triple(490.0, 46.0, 4.0), Triple(515.0, 33.0, 6.0), Triple(540.0, 22.0, 11.0), Triple(570.0, 11.0, 18.0),
                Triple(600.0, 8.0, 28.0), Triple(630.0, 16.0, 34.0), Triple(660.0, 28.0, 30.0), Triple(690.0, 36.0, 20.0),
                Triple(720.0, 48.0, 18.0), Triple(750.0, 57.0, 24.0), Triple(780.0, 60.0, 33.0), Triple(805.0, 58.0, 41.0),
                Triple(830.0, 50.0, 44.0), Triple(875.0, 22.0, 44.0), Triple(884.0, 13.0, 45.5), Triple(892.0, 12.0, 50.0),
            ),
        )

        private fun launch() = RideProfile(
            type = ProfileType.LAUNCH,
            length = 900.0,
            elevation = listOf(
                0.0 to 2.0, 165.0 to 2.0, 180.0 to 8.0, 200.0 to 34.0, 210.0 to 38.0, 220.0 to 34.0,
                245.0 to 10.0, 260.0 to 3.0, 290.0 to 25.0, 320.0 to 10.0, 360.0 to 20.0,
                390.0 to 8.0, 430.0 to 15.0, 515.0 to 15.0, 560.0 to 2.0, 610.0 to 10.0,
                660.0 to 4.0, 720.0 to 8.0, 770.0 to 3.0, 870.0 to 3.0, 900.0 to 2.0,
            ),
            blocks = listOf(
                Block(0, "B1", "Station", BlockKind.STATION, 870.0, 45.0, 30.0, 870.0, 60.0),
                Block(1, "B2", "Launch / Top Hat", BlockKind.LAUNCH, 45.0, 215.0, 70.0, 45.0, 40.0),
                Block(2, "B3", "Mid-Course Brake", BlockKind.BRAKE_RUN, 215.0, 520.0, 512.0, 470.0, 40.0),
                Block(3, "B4", "Final Brake / Transfer", BlockKind.BRAKE_RUN, 520.0, 870.0, 862.0, 820.0, 75.0),
            ),
            launchStart = 70.0, launchEnd = 165.0, crestSensor = 212.0,
            plan = listOf(
                Triple(0.0, 26.0, 52.0), Triple(45.0, 40.0, 52.0), Triple(70.0, 47.0, 52.0), Triple(165.0, 80.0, 52.0),
                Triple(192.0, 90.0, 50.0), Triple(212.0, 95.0, 44.0), Triple(232.0, 96.0, 34.0), Triple(260.0, 90.0, 24.0),
                Triple(290.0, 80.0, 30.0), Triple(320.0, 70.0, 22.0), Triple(355.0, 80.0, 12.0), Triple(388.0, 92.0, 6.0),
                Triple(425.0, 82.0, 3.5), Triple(470.0, 66.0, 4.0), Triple(515.0, 44.0, 4.0), Triple(540.0, 31.0, 7.0),
                Triple(562.0, 20.0, 13.0), Triple(590.0, 10.0, 20.0), Triple(620.0, 8.0, 30.0), Triple(650.0, 18.0, 34.0),
                Triple(680.0, 30.0, 28.0), Triple(710.0, 40.0, 20.0), Triple(740.0, 52.0, 20.0), Triple(770.0, 59.0, 28.0),
                Triple(798.0, 59.0, 38.0), Triple(820.0, 52.0, 44.0), Triple(862.0, 24.0, 44.0), Triple(876.0, 13.0, 45.5),
                Triple(888.0, 12.0, 50.0),
            ),
        )
    }
}
