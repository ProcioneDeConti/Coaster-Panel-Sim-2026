package com.abemu.coaster

import com.abemu.coaster.sim.Autopilot
import com.abemu.coaster.sim.CoasterSpec
import com.abemu.coaster.sim.CustomRides
import com.abemu.coaster.sim.Demand
import com.abemu.coaster.sim.GuestQueue
import com.abemu.coaster.sim.OsmLayout
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.Severity
import com.abemu.coaster.sim.Weather
import com.abemu.coaster.sim.WeatherPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsTest {
    private val dt = 0.02

    private fun scan(plc: RidePlc, secs: Double, each: () -> Unit = {}) {
        repeat((secs / dt).toInt()) { each(); plc.scan(dt); plc.plant.step(dt) }
    }

    private fun weatherOk(plc: RidePlc) = plc.permissives.first { it.label == "No weather hold" }.ok

    @Test fun lightningHoldsDispatchUntilAllClear() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        Autopilot.warmUp(plc, 0, { true }) // powered and running: permissives are live
        assertTrue(weatherOk(plc))
        plc.weather.strikeMi = 5.0
        scan(plc, 1.0)
        plc.weather.strikeMi = null
        assertFalse(weatherOk(plc))
        assertTrue(plc.alarms.isActive("WX"))
        assertEquals(Severity.WARNING, plc.alarms.active.first { it.code == "WX" }.severity)
        scan(plc, plc.weather.lightningClearSec - 10)
        assertFalse("hold must last the full all-clear time", weatherOk(plc))
        scan(plc, 15.0)
        assertTrue(weatherOk(plc))
        // A distant strike doesn't hold.
        plc.weather.strikeMi = 12.0
        scan(plc, 1.0)
        assertTrue(weatherOk(plc))
    }

    @Test fun windHoldNeedsWindToDropBelowLimitMinusFive() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        Autopilot.warmUp(plc, 0, { true })
        val limit = plc.weather.windLimitMph
        plc.weather.windMph = limit + 1
        scan(plc, 1.0)
        assertFalse(weatherOk(plc))
        plc.weather.windMph = limit - 2 // under the limit but not by 5 mph: timer doesn't run
        scan(plc, plc.weather.windClearSec + 30)
        assertFalse(weatherOk(plc))
        plc.weather.windMph = limit - 8
        scan(plc, plc.weather.windClearSec + 1)
        assertTrue(weatherOk(plc))
    }

    @Test fun stormCellComesAndGoes() {
        val w = Weather()
        w.preset = WeatherPreset.STORM
        var closest = 99.0
        var maxWind = 0.0
        var t = 0.0
        while (t < 3 * 3600) {
            w.update(0.1); t += 0.1
            w.strikeMi?.let { closest = minOf(closest, it) }
            maxWind = maxOf(maxWind, w.windMph)
        }
        assertTrue("storm brings lightning inside the hold radius (closest $closest)", closest < 8)
        assertTrue("storm is gusty ($maxWind)", maxWind > 20)
        w.preset = WeatherPreset.CLEAR
        t = 0.0
        while (t < 600) { w.update(0.1); t += 0.1 }
        assertEquals(null, w.strikeMi)
        assertTrue(w.windMph < 20)
    }

    @Test fun queueFillsBoardsAndClosesForWeather() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        val q = GuestQueue()
        q.demand = Demand.BUSY
        Autopilot.warmUp(plc, 1, { false }, maxSecs = 1200.0)
        // warmUp doesn't feed the queue; run a live hour with the queue attached.
        repeat(3600 * 5) { q.update(0.2, plc) }
        assertTrue("guests arrive (${q.inLine})", q.inLine in 700..1400)
        val before = q.inLine
        plc.weather.strikeMi = 3.0
        plc.scan(dt)
        repeat(600 * 5) { q.update(0.2, plc) }
        assertEquals("WEATHER", q.closedReason)
        assertTrue("guests leave a held ride (${q.inLine} of $before)", q.inLine < before)
    }

    @Test fun queueBoardsOnDispatch() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        val q = GuestQueue()
        q.demand = Demand.PEAK
        repeat(600 * 5) { q.update(0.2, plc) } // a 10-minute line before opening
        val waiting = q.inLine
        Autopilot.warmUp(plc, 3, { it.dispatchCount >= 3 })
        q.update(dt, plc)
        assertEquals(3, plc.dispatchCount)
        assertTrue("riders boarded (${q.riders})", q.riders > 0 && q.riders <= 3 * plc.profile.ridersPerTrain)
        assertTrue(q.inLine < waiting + 50)
    }

    // ---------------- OSM ride builder ----------------

    /** A 400 x 150 m oval circuit (two oneway ways), a station building on the south side, and a dead-end spur. */
    private val osm = buildString {
        append("<osm>")
        val lat0 = 41.48; val lon0 = -82.68
        val mLat = 1 / 110540.0; val mLon = 1 / (111320 * Math.cos(Math.toRadians(lat0)))
        // Oval: 40 points counter-clockwise starting at the south-west.
        val pts = (0 until 40).map { i ->
            val a = Math.PI + 2 * Math.PI * i / 40
            (lat0 + 75 * Math.sin(a) * mLat) to (lon0 + 200 * Math.cos(a) * mLon)
        }
        pts.forEachIndexed { i, (la, lo) -> append("<node id=\"${i + 1}\" lat=\"$la\" lon=\"$lo\"/>") }
        // Station building around the southernmost points (indices ~8..12).
        val st = listOf(-70.0 to -95.0, -70.0 to -60.0, 70.0 to -60.0, 70.0 to -95.0)
        st.forEachIndexed { i, (x, y) -> append("<node id=\"${100 + i}\" lat=\"${lat0 + y * mLat}\" lon=\"${lon0 + x * mLon}\"/>") }
        append("<node id=\"200\" lat=\"${lat0 + 140 * mLat}\" lon=\"${lon0}\"/>")
        fun way(id: Int, nds: List<Int>, tags: Map<String, String>) {
            append("<way id=\"$id\">")
            nds.forEach { append("<nd ref=\"$it\"/>") }
            tags.forEach { (k, v) -> append("<tag k=\"$k\" v=\"$v\"/>") }
            append("</way>")
        }
        val track = mapOf("roller_coaster" to "track", "oneway" to "yes")
        way(1, (1..21).toList(), track)
        way(2, (21..40).toList() + 1, track)
        way(3, listOf(31, 200), mapOf("roller_coaster" to "track")) // spur off the north side
        way(4, listOf(100, 101, 102, 103, 100), mapOf("roller_coaster" to "station", "name" to "Test Coaster"))
        append("</osm>")
    }

    @Test fun extractsOvalWithStationAndIgnoresSpur() {
        val data = OsmLayout.parse(osm.byteInputStream())
        val r = OsmLayout.extract(data, 41.48 - 80 / 110540.0, -82.68, "Test Coaster")
        val l = r.layout
        // Ellipse 200 x 75 m semi-axes: perimeter ≈ 943 m.
        assertTrue("length ${l.osmLengthM}", l.osmLengthM in 900.0..990.0)
        assertTrue("station ${l.stationEnd}", l.stationEnd in 0.05..0.25)
        assertTrue(l.liftEnd > l.liftStart)
        assertTrue(l.points.all { (u, x, y) -> u in 0.0..1.0 && x in 0.0..100.0 && y in 0.0..56.0 })
        assertTrue(r.notes.any { "dead-end" in it })
        assertEquals(listOf(1L, 2L), r.trackWayIds.sorted())
    }

    @Test fun builtCustomRideRuns() {
        val data = OsmLayout.parse(osm.byteInputStream())
        val r = OsmLayout.extract(data, 41.48 - 80 / 110540.0, -82.68, "Test Coaster")
        val spec = CoasterSpec("osm_test", "Test Coaster", ProfileType.LIFT, 40.0, liftSpeed = 4.0,
            hills1 = listOf(0.65, 0.5, 0.55), mcbrFrac = 0.4, hills2 = listOf(0.55, 0.4, 0.3),
            cars = 6, ridersPerCar = 4, layout = 0, seed = 1, park = "OpenStreetMap")
        CustomRides.put(CustomRides.Ride(spec, r.layout, "test"))
        try {
            val p = RideProfile.create(spec)
            assertTrue("course stretched toward the real length (${p.length})", p.length > 800)
            val plc = RidePlc(Plant(p, 3))
            Autopilot.warmUp(plc, 4, { it.dispatchCount >= 4 }, maxSecs = 1500.0)
            assertEquals(4, plc.dispatchCount)
            assertTrue(plc.alarms.active.map { it.text }.toString(), plc.alarms.active.none { it.severity.ordinal <= Severity.CSTOP.ordinal })
        } finally {
            CustomRides.remove("osm_test")
        }
    }
}
