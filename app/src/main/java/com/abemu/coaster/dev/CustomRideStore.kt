package com.abemu.coaster.dev

import android.content.Context
import com.abemu.coaster.sim.CoasterSpec
import com.abemu.coaster.sim.CustomRides
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RealLayouts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the ride builder's rides (spec + OSM layout) as JSON in app storage and loads them into [CustomRides]. */
object CustomRideStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "custom_rides.json")

    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("rides")
            for (i in 0 until arr.length()) runCatching { CustomRides.put(fromJson(arr.getJSONObject(i))) }
        }
    }

    fun save(ctx: Context) {
        val arr = JSONArray().apply { CustomRides.rides.forEach { put(toJson(it)) } }
        file(ctx).writeText(JSONObject().put("rides", arr).toString())
    }

    private fun doubles(l: List<Double>) = JSONArray().apply { l.forEach { put(it) } }
    private fun JSONArray.doubles() = (0 until length()).map { getDouble(it) }

    private fun toJson(r: CustomRides.Ride) = JSONObject().apply {
        val s = r.spec
        put("id", s.id); put("name", s.name); put("type", s.type.name); put("height", s.height)
        put("launchSpeed", s.launchSpeed); put("liftSpeed", s.liftSpeed)
        put("hills1", doubles(s.hills1)); put("mcbrFrac", s.mcbrFrac); put("hills2", doubles(s.hills2))
        put("cars", s.cars); put("ridersPerCar", s.ridersPerCar); put("seed", s.seed)
        put("park", s.park ?: ""); put("note", s.note); put("hasMcbr", s.hasMcbr); put("unloadStation", s.unloadStation)
        put("source", r.source)
        val l = r.layout
        put("layout", JSONObject().apply {
            put("osmLengthM", l.osmLengthM); put("stationEnd", l.stationEnd); put("liftStart", l.liftStart)
            put("liftEnd", l.liftEnd); put("liftTagged", l.liftTagged)
            put("points", JSONArray().apply { l.points.forEach { (u, x, y) -> put(u); put(x); put(y) } })
        })
    }

    private fun fromJson(o: JSONObject): CustomRides.Ride {
        val spec = CoasterSpec(
            id = o.getString("id"), name = o.getString("name"), type = ProfileType.valueOf(o.getString("type")),
            height = o.getDouble("height"), launchSpeed = o.getDouble("launchSpeed"), liftSpeed = o.getDouble("liftSpeed"),
            hills1 = o.getJSONArray("hills1").doubles(), mcbrFrac = o.getDouble("mcbrFrac"), hills2 = o.getJSONArray("hills2").doubles(),
            cars = o.getInt("cars"), ridersPerCar = o.getInt("ridersPerCar"), layout = 0, seed = o.getInt("seed"),
            park = o.optString("park").ifEmpty { null }, note = o.optString("note"),
            hasMcbr = o.getBoolean("hasMcbr"), unloadStation = o.optBoolean("unloadStation"),
        )
        val l = o.getJSONObject("layout")
        val p = l.getJSONArray("points")
        val pts = (0 until p.length() / 3).map { Triple(p.getDouble(it * 3), p.getDouble(it * 3 + 1), p.getDouble(it * 3 + 2)) }
        val layout = RealLayouts.Layout(l.getDouble("osmLengthM"), l.getDouble("stationEnd"), l.getDouble("liftStart"),
            l.getDouble("liftEnd"), l.getBoolean("liftTagged"), pts)
        return CustomRides.Ride(spec, layout, o.optString("source"))
    }
}
