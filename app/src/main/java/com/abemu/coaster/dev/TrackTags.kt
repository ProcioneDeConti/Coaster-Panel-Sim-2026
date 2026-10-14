package com.abemu.coaster.dev

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.abemu.coaster.sim.LayoutTag
import com.abemu.coaster.sim.RideProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Kinds of feature that can be marked on a track in the dev editor. */
enum class TagType(val label: String, val color: Color, val point: Boolean = false) {
    LOAD_STATION("Load station", Color(0xFF42A5F5)),
    UNLOAD_STATION("Unload station", Color(0xFF26C6DA)),
    LIFT("Lift hill", Color(0xFFFFB300)),
    LAUNCH("LSM launch", Color(0xFF40C4FF)),
    BLOCK_BRAKE("Block brake run", Color(0xFFFF1744)),
    TRIM_BRAKE("Trim brake", Color(0xFFFF9100)),
    TUNNEL("Tunnel / MCBR", Color(0xFFAB47BC)),
    TRANSFER("Transfer track", Color(0xFF00897B)),
    BLOCK_BOUNDARY("Block boundary", Color(0xFFFFFFFF), point = true),
    SENSOR("Prox sensor", Color(0xFFFFEB3B), point = true),
}

/**
 * A feature marked on the track. Positions are kept three ways so tags survive changes to the
 * generator: simulated metres, fraction of the circuit, and plan-view (x, y) in the 100 x 56 box.
 */
data class TrackTag(
    val type: TagType,
    val sStart: Double,
    val sEnd: Double,
    val name: String = "",
    val heightM: Double? = null,
    val fStart: Double,
    val fEnd: Double,
    val xyStart: Pair<Double, Double>,
    val xyEnd: Pair<Double, Double>,
) {
    fun lengthM(profile: RideProfile) = profile.ahead(sStart, sEnd)

    /** This tag in the generator's form. */
    fun toLayout() = LayoutTag(type.name, fStart, fEnd, name)

    companion object {
        fun create(profile: RideProfile, type: TagType, a: Double, b: Double, name: String, height: Double?) =
            TrackTag(
                type, a, b, name.trim(), height,
                fStart = a / profile.length, fEnd = b / profile.length,
                xyStart = profile.planPoint(a), xyEnd = profile.planPoint(b),
            )
    }
}

/** Saves tags per coaster in app storage and exports them as JSON for pulling into the source. */
object TagStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "tags").apply { mkdirs() }

    fun load(ctx: Context, coasterId: String): List<TrackTag> {
        val f = File(dir(ctx), "$coasterId.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("tags")
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Tags in the generator's form (circuit fractions). */
    fun loadLayout(ctx: Context, coasterId: String) =
        load(ctx, coasterId).map { it.toLayout() }

    fun save(ctx: Context, coasterId: String, profile: RideProfile, tags: List<TrackTag>) {
        File(dir(ctx), "$coasterId.json").writeText(toJson(coasterId, profile, tags).toString(2))
    }

    /** Writes to the app's external files dir (adb-pullable) and returns the file. */
    fun export(ctx: Context, coasterId: String, profile: RideProfile, tags: List<TrackTag>): File {
        val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tags").apply { mkdirs() }
        return File(out, "$coasterId.json").apply { writeText(toJson(coasterId, profile, tags).toString(2)) }
    }

    private fun toJson(coasterId: String, profile: RideProfile, tags: List<TrackTag>) = JSONObject().apply {
        put("coaster", coasterId)
        put("name", profile.name)
        put("simLengthM", profile.length)
        put("tags", JSONArray().apply {
            tags.forEach { t ->
                put(JSONObject().apply {
                    put("type", t.type.name)
                    put("name", t.name)
                    t.heightM?.let { put("heightM", it) }
                    put("sStart", t.sStart); put("sEnd", t.sEnd)
                    put("fStart", t.fStart); put("fEnd", t.fEnd)
                    put("xyStart", JSONArray(listOf(t.xyStart.first, t.xyStart.second)))
                    put("xyEnd", JSONArray(listOf(t.xyEnd.first, t.xyEnd.second)))
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): TrackTag {
        fun xy(k: String) = o.getJSONArray(k).let { it.getDouble(0) to it.getDouble(1) }
        return TrackTag(
            type = TagType.valueOf(o.getString("type")),
            sStart = o.getDouble("sStart"), sEnd = o.getDouble("sEnd"),
            name = o.optString("name"),
            heightM = if (o.has("heightM")) o.getDouble("heightM") else null,
            fStart = o.getDouble("fStart"), fEnd = o.getDouble("fEnd"),
            xyStart = xy("xyStart"), xyEnd = xy("xyEnd"),
        )
    }
}
