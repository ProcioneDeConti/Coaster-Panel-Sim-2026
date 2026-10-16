package com.abemu.coaster.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Made-up scenery for rides without an OSM layout: clustered trees, maybe a pond (preferably inside a loop of
 * the track, sometimes with an island) and maybe a lakeshore with a beach along one edge. Seeded by ride id, so
 * a ride always looks the same. Same plan-box coordinates and [RealScenery.Scenery] model as the real rides.
 */
object FictionalScenery {
    /** Per-ride look. [lake] is the edge the lake lies along: 'N', 'S', 'E', 'W', or null for none. */
    class Look(val trees: Int = 88, val pond: Boolean = true, val island: Boolean = false, val lake: Char? = null)

    /** Hand-tuned looks; rides not listed get a seeded mix. */
    private val looks = mapOf<String, Look>()

    private val cache = HashMap<String, RealScenery.Scenery>()

    fun forProfile(profile: RideProfile): RealScenery.Scenery =
        synchronized(cache) { cache.getOrPut(profile.spec.id) { generate(profile) } }

    // The clip area the scenery may fill (the visible plan box plus margins for wide screens).
    private const val X0 = -30.0
    private const val Y0 = -20.0
    private const val X1 = 130.0
    private const val Y1 = 76.0

    private fun generate(profile: RideProfile): RealScenery.Scenery {
        val rnd = Random(profile.spec.id.hashCode())
        val look = looks[profile.spec.id] ?: Look(
            trees = 75 + rnd.nextInt(26),
            pond = rnd.nextFloat() < 0.6f,
            island = rnd.nextFloat() < 0.5f,
            lake = if (rnd.nextFloat() < 0.35f) "NEW"[rnd.nextInt(3)] else null, // the station sits along the bottom
        )
        val track = ArrayList<Pair<Double, Double>>()
        var s = 0.0
        while (s < profile.length) { track += profile.planPoint(s); s += 2.0 }
        fun clearance(x: Double, y: Double) = track.minOf { hypot(it.first - x, it.second - y) }

        // Lakeshore: a wavy line along one edge, pulled back to stay 3 units clear of the track.
        val lake = ArrayList<List<Pair<Double, Double>>>()
        val lakeLand = ArrayList<List<Pair<Double, Double>>>()
        var inLake: (Double, Double) -> Boolean = { _, _ -> false }
        look.lake?.let { side ->
            val horizontal = side == 'N' || side == 'S'
            // a = position along the edge, d = depth into the box from that edge
            fun toXY(a: Double, d: Double) = when (side) {
                'N' -> a to d
                'S' -> a to 56.0 - d
                'W' -> d to a
                else -> 100.0 - d to a
            }
            val aRange = if (horizontal) X0..X1 else Y0..Y1
            val base = 7.0 + rnd.nextDouble() * 5
            val ph = DoubleArray(3) { rnd.nextDouble() * 2 * PI }
            val shore = ArrayList<Pair<Double, Double>>()
            var a = aRange.start
            while (a <= aRange.endInclusive) {
                var d = base + 2.2 * sin(a / 9 + ph[0]) + 1.2 * sin(a / 4.3 + ph[1]) + 0.5 * sin(a / 1.7 + ph[2])
                // keep the water 3.5 units off the track, easing back out on a gentle slope (no square notches)
                track.forEach { (tx, ty) ->
                    val (ta, td) = when (side) { 'N' -> tx to ty; 'S' -> tx to 56 - ty; 'W' -> ty to tx; else -> ty to 100 - tx }
                    d = minOf(d, td - 3.5 + 0.45 * kotlin.math.abs(ta - a))
                }
                shore += a to d
                a += 1.5
            }
            val far = -40.0
            val back = 120.0
            val line = shore.map { toXY(it.first, it.second) }
            lake += listOf(toXY(aRange.start, far)) + line + listOf(toXY(aRange.endInclusive, far))
            lakeLand += line + listOf(toXY(aRange.endInclusive, back), toXY(aRange.start, back))
            inLake = { x, y ->
                val (pa, pd) = when (side) { 'N' -> x to y; 'S' -> x to 56 - y; 'W' -> y to x; else -> y to 100 - x }
                val i = ((pa - aRange.start) / 1.5).toInt().coerceIn(0, shore.size - 1)
                pd < shore[i].second + 1.0
            }
        }

        // Pond: the roomiest spot, preferring the inside of a loop and the middle of the box.
        val water = ArrayList<List<Pair<Double, Double>>>()
        val land = ArrayList<List<Pair<Double, Double>>>()
        val ponds = ArrayList<List<Pair<Double, Double>>>()
        if (look.pond) {
            var best: Triple<Double, Double, Double>? = null
            var bestScore = Double.NEGATIVE_INFINITY
            var y = 4.0
            while (y <= 52.0) {
                var x = 4.0
                while (x <= 96.0) {
                    if (!inLake(x, y)) {
                        val c = clearance(x, y)
                        val score = minOf(c, 14.0) + (if (inside(x, y, track)) 4.0 else 0.0) -
                            0.04 * hypot(x - 50, y - 28) + rnd.nextDouble() * 1.5
                        if (c >= 6.0 && score > bestScore) { bestScore = score; best = Triple(x, y, c) }
                    }
                    x += 2.0
                }
                y += 2.0
            }
            best?.let { (cx, cy, c) ->
                val r = minOf(c - 2.5, 6.0 + rnd.nextDouble() * 7)
                val ph = DoubleArray(3) { rnd.nextDouble() * 2 * PI }
                val pond = List(48) { k ->
                    val t = k * 2 * PI / 48
                    var rr = r * (1 + 0.2 * sin(2 * t + ph[0]) + 0.1 * sin(3 * t + ph[1]) + 0.05 * sin(5 * t + ph[2]))
                    while (rr > 1 && clearance(cx + cos(t) * rr, cy + sin(t) * rr) < 2.0) rr -= 0.5 // hug the track
                    cx + cos(t) * rr to cy + sin(t) * rr
                }
                water += pond
                ponds += pond
                if (look.island && r > 7.5) {
                    val ir = r * 0.28
                    val ox = cx + (rnd.nextDouble() - 0.5) * r * 0.4
                    val oy = cy + (rnd.nextDouble() - 0.5) * r * 0.4
                    land += List(24) { k ->
                        val t = k * 2 * PI / 24
                        val rr = ir * (1 + 0.25 * sin(2 * t + ph[1]) + 0.1 * sin(3 * t + ph[2]))
                        ox + cos(t) * rr to oy + sin(t) * rr
                    }
                }
            }
        }
        fun wet(x: Double, y: Double) = inLake(x, y) || ponds.any { inside(x, y, it) }

        // Trees: groves around a few centres, plus scattered singles; clear of the track, water and each other.
        val trees = ArrayList<Pair<Double, Double>>()
        fun tryTree(x: Double, y: Double) {
            if (x < -25 || x > 125 || y < -12 || y > 68) return
            if (wet(x, y) || clearance(x, y) < 2.2) return
            if (trees.any { hypot(it.first - x, it.second - y) < 2.5 }) return
            trees += x to y
        }
        // Mostly in the visible box (about -4..104 across on the tablet); wider screens get the stragglers.
        val groves = List(6 + rnd.nextInt(5)) { (-2 + rnd.nextDouble() * 104) to (2 + rnd.nextDouble() * 52) }
        var tries = 0
        while (trees.size < look.trees && tries++ < 4000) {
            if (rnd.nextFloat() < 0.75f) {
                val (gx, gy) = groves[rnd.nextInt(groves.size)]
                val a = rnd.nextDouble() * 2 * PI
                val d = kotlin.math.abs(rnd.nextDouble() + rnd.nextDouble() - 1) * 12 // denser near the centre
                tryTree(gx + cos(a) * d, gy + sin(a) * d)
            } else tryTree(-8 + rnd.nextDouble() * 116, -4 + rnd.nextDouble() * 64)
        }
        return RealScenery.Scenery(trees, lake, lakeLand, water, land)
    }

    /** Even-odd point-in-polygon. */
    private fun inside(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}
