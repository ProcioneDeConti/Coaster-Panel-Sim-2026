package com.abemu.coaster.web

import com.abemu.coaster.sim.OsmLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap lookups for the ride builder. Search uses Nominatim (OSM's geocoder), falling back to Overpass; track
 * geometry comes from the OSM API `map` call (complete ways in a box), falling back to Overpass. The public Overpass
 * servers are often overloaded, so they are only the backup. Blocking: call off the main thread.
 */
object OsmClient {
    private val overpass = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")
    private const val UA = "BallinRadleyEmulator/1.0 (ride builder; Android)"

    /** A search hit: a coaster station, track or attraction carrying the name. */
    class Hit(val name: String, val lat: Double, val lon: Double, val what: String, val where: String, val tags: Map<String, String>)

    fun search(query: String): List<Hit> {
        require(query.trim().length >= 2) { "Type at least two letters." }
        val hits = runCatching { nominatim(query.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: overpassSearch(query.trim())
        // One entry per ride: merge hits with the same name within ~600 m, preferring the station (best start point).
        val rank = mapOf("station" to 0, "attraction" to 1, "track" to 2, "place" to 3)
        val out = mutableListOf<Hit>()
        for (h in hits.sortedBy { rank[it.what] ?: 9 }) {
            if (out.none { it.name.equals(h.name, true) && Math.abs(it.lat - h.lat) < 0.006 && Math.abs(it.lon - h.lon) < 0.008 }) out += h
        }
        return out.take(30)
    }

    private fun nominatim(q: String): List<Hit> {
        val arr = org.json.JSONArray(String(get("https://nominatim.openstreetmap.org/search?format=jsonv2&extratags=1&addressdetails=1&limit=15&q=" +
            URLEncoder.encode(q, "UTF-8"))))
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.getJSONObject(i)
            val x = e.optJSONObject("extratags")
            val tags = x?.keys()?.asSequence()?.associateWith { x.getString(it) }.orEmpty()
            val what = when {
                tags["roller_coaster"] == "station" -> "station"
                tags["roller_coaster"] == "track" -> "track"
                tags["attraction"] == "roller_coaster" -> "attraction"
                else -> "place"
            }
            val a = e.optJSONObject("address")
            val where = listOfNotNull(
                tags["operator"],
                a?.let { it.optString("city").ifEmpty { it.optString("town") }.ifEmpty { it.optString("village") } }?.ifEmpty { null },
                a?.optString("state")?.ifEmpty { null },
            ).joinToString(" · ").ifEmpty { "%.4f, %.4f".format(e.getString("lat").toDouble(), e.getString("lon").toDouble()) }
            Hit(e.optString("name").ifEmpty { return@mapNotNull null }, e.getString("lat").toDouble(), e.getString("lon").toDouble(), what, where, tags)
        }
    }

    private fun overpassSearch(query: String): List<Hit> {
        val q = Regex("[\\\\\"^$.|?*+()\\[\\]{}]").replace(query) { "\\\\" + it.value }
        val ql = """
            [out:json][timeout:25];
            (
              nwr["roller_coaster"~"^(station|track)$"]["name"~"$q",i];
              nwr["attraction"="roller_coaster"]["name"~"$q",i];
            );
            out center tags 60;
        """.trimIndent()
        val els = JSONObject(String(overpassPost(ql))).getJSONArray("elements")
        val hits = mutableListOf<Hit>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: continue
            val tags = t.keys().asSequence().associateWith { t.getString(it) }
            val c = e.optJSONObject("center")
            val lat = c?.getDouble("lat") ?: e.optDouble("lat", Double.NaN)
            val lon = c?.getDouble("lon") ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val what = when (tags["roller_coaster"]) { "station" -> "station"; "track" -> "track"; else -> "attraction" }
            val where = listOfNotNull(tags["operator"], tags["addr:city"]).joinToString(" · ").ifEmpty { "%.4f, %.4f".format(lat, lon) }
            hits += Hit(tags["name"] ?: continue, lat, lon, what, where, tags)
        }
        return hits
    }

    /**
     * Coaster track around the hit, extracted. Starts with a ~1.2 km box; if the track runs out of it (open ends far
     * apart, or no track found) it retries with a ~2.2 km box.
     */
    fun fetchLayout(hit: Hit): OsmLayout.Result {
        fun tryBox(dLat: Double, dLon: Double) = runCatching { OsmLayout.extract(fetchBox(hit.lat, hit.lon, dLat, dLon), hit.lat, hit.lon, hit.name) }
        val small = tryBox(0.0055, 0.0075)
        small.getOrNull()?.let { if (it.openGapM < 150) return it }
        return tryBox(0.01, 0.0135).getOrElse { e -> small.getOrNull() ?: throw (small.exceptionOrNull() ?: e) }
    }

    private fun fetchBox(lat: Double, lon: Double, dLat: Double, dLon: Double): OsmLayout.Data {
        val bytes = runCatching {
            get("https://api.openstreetmap.org/api/0.6/map?bbox=${lon - dLon},${lat - dLat},${lon + dLon},${lat + dLat}")
        }.getOrElse {
            val r = (dLat * 110540 * 0.8).toInt()
            overpassPost("""
                [timeout:40];
                (way(around:$r,$lat,$lon)["roller_coaster"="track"]; way(around:$r,$lat,$lon)["roller_coaster"="station"];);
                (._;>;);
                out body;
            """.trimIndent())
        }
        return OsmLayout.parse(ByteArrayInputStream(bytes))
    }

    private fun overpassPost(ql: String): ByteArray {
        var last: Exception? = null
        for (url in overpass) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 45000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(ql, "UTF-8")).toByteArray()) }
                if (c.responseCode != 200) throw RuntimeException("Overpass ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } catch (e: Exception) { last = e }
        }
        throw RuntimeException("OpenStreetMap search servers are busy or unreachable (${last?.message}). Try again in a minute.")
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", UA)
        if (c.responseCode != 200) throw RuntimeException("OSM API ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}
