package com.abemu.coaster

import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.FictionalScenery
import com.abemu.coaster.sim.RealScenery
import com.abemu.coaster.sim.RideProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class SceneryTest {
    @Test
    fun fictionalRidesGetSceneryClearOfTheTrack() {
        CoasterLibrary.builtIn.filter { RealScenery.byId(it.id) == null }.forEach { spec ->
            val profile = RideProfile.create(spec)
            val sc = FictionalScenery.forProfile(profile)
            val track = (0 until profile.length.toInt() step 2).map { profile.planPoint(it.toDouble()) }
            assertTrue("${spec.id}: trees", sc.trees.size >= 40)
            sc.trees.forEach { (x, y) ->
                assertTrue("${spec.id}: tree on track", track.minOf { hypot(it.first - x, it.second - y) } >= 2.2)
            }
            preview(spec.id, profile, sc, track)
        }
    }

    /** Writes build/scenery-preview/<id>.txt (track + scenery) for tools/osm/preview_scenery.py to render. */
    private fun preview(id: String, profile: RideProfile, sc: RealScenery.Scenery, track: List<Pair<Double, Double>>) {
        fun line(tag: String, pts: List<Pair<Double, Double>>) = tag + " " + pts.joinToString(" ") { "%.2f %.2f".format(java.util.Locale.ROOT, it.first, it.second) }
        val out = buildList {
            add("# ${profile.name}")
            add(line("R", track))
            sc.trees.forEach { add(line("T", listOf(it))) }
            sc.lake.forEach { add(line("E", it)) }
            sc.lakeLand.forEach { add(line("K", it)) }
            sc.water.forEach { add(line("W", it)) }
            sc.land.forEach { add(line("L", it)) }
        }
        File("build/scenery-preview").apply { mkdirs() }.resolve("$id.txt").writeText(out.joinToString("\n"))
    }
}
