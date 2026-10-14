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
) {
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
        )
    }
}
