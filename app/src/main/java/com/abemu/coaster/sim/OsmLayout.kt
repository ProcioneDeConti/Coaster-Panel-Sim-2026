package com.abemu.coaster.sim

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

private typealias P = Pair<Double, Double>

/**
 * On-device version of tools/osm/extract_layouts.py for the ride builder: turns OpenStreetMap
 * `roller_coaster=track` ways (and the ride's `roller_coaster=station` building) into a [RealLayouts.Layout].
 * Swing-launch switch tracks are not handled; the track must be a circuit (or an out-and-back line).
 * Map data © OpenStreetMap contributors, ODbL.
 */
object OsmLayout {
    class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)
    class Data(val nodes: Map<Long, Pair<Double, Double>>, val ways: List<Way>)

    /** [openGapM]: straight-line gap between the ends when the mapped track isn't a closed circuit (0 if closed). */
    class Result(val layout: RealLayouts.Layout, val notes: List<String>, val trackWayIds: List<Long>, val openGapM: Double)

    class ExtractError(msg: String) : Exception(msg)

    /**
     * Parses OSM XML (OSM API `map` call or Overpass `out body`). Streams the file and keeps only coaster ways, so a
     * several-megabyte park download stays cheap.
     */
    fun parse(xml: InputStream): Data {
        val nodes = HashMap<Long, Pair<Double, Double>>()
        val ways = mutableListOf<Way>()
        var wayId = 0L
        var nds: MutableList<Long>? = null
        var tags: HashMap<String, String>? = null
        SAXParserFactory.newInstance().newSAXParser().parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                when (q) {
                    "node" -> {
                        val lat = a.getValue("lat")?.toDoubleOrNull() ?: return
                        val lon = a.getValue("lon")?.toDoubleOrNull() ?: return
                        nodes[a.getValue("id").toLong()] = lat to lon
                    }
                    "way" -> { wayId = a.getValue("id").toLong(); nds = mutableListOf(); tags = HashMap() }
                    "nd" -> nds?.add(a.getValue("ref").toLong())
                    "tag" -> tags?.put(a.getValue("k"), a.getValue("v"))
                }
            }

            override fun endElement(uri: String?, local: String?, q: String) {
                if (q != "way") return
                val t = tags ?: return
                if (t.containsKey("roller_coaster")) ways += Way(wayId, nds!!, t)
                nds = null; tags = null
            }
        })
        return Data(nodes, ways)
    }

    private fun dist(a: P, b: P) = hypot(a.first - b.first, a.second - b.second)

    /**
     * Extracts the coaster track nearest ([lat], [lon]). [name] (optional) picks the station building of that name.
     */
    fun extract(d: Data, lat: Double, lon: Double, name: String? = null): Result {
        val notes = mutableListOf<String>()
        val k = 111320 * cos(Math.toRadians(lat))
        fun xy(n: Long): P? = d.nodes[n]?.let { (la, lo) -> lo * k to -la * 110540.0 }
        val target = lon * k to -lat * 110540.0

        val tracks = d.ways.filter { it.tags["roller_coaster"] == "track" && it.tags["service"] != "siding" && it.nodes.size > 1 }
        if (tracks.isEmpty()) throw ExtractError("No roller_coaster=track ways mapped near this ride.")

        // Connected components of the track ways.
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var a = x
            while (parent.getOrPut(a) { a } != a) { parent[a] = parent[parent[a]!!]!!; a = parent[a]!! }
            return a
        }
        for (w in tracks) for (n in w.nodes) parent[find(-w.id - 1)] = find(n)
        val comps = tracks.groupBy { find(-it.id - 1) }.values
        val nearest = comps.filter { c -> c.sumOf { it.nodes.size } > 10 }
            .minByOrNull { c -> c.flatMap { it.nodes }.mapNotNull { xy(it) }.minOf { dist(it, target) } }
            ?: throw ExtractError("The track mapped here is too short to be a coaster circuit.")
        val gap = nearest.flatMap { it.nodes }.mapNotNull { xy(it) }.minOf { dist(it, target) }
        if (gap > 400) throw ExtractError("No coaster track within 400 m of this place (nearest is ${gap.toInt()} m away).")

        val (loop, used) = chain(nearest, notes)
        val gapM = if (loop.first() == loop.last()) 0.0 else dist(xy(loop.first())!!, xy(loop.last())!!)
        if (gapM > 150) notes += "The mapped track has a ${gapM.toInt()} m gap; it is closed with a straight line."
        val ring = if (loop.first() == loop.last()) loop.dropLast(1) else loop
        var pts0 = ring.mapNotNull { xy(it) }
        if (!forward(ring, used)) pts0 = pts0.reversed()
        if (used.none { it.tags["oneway"] == "yes" }) notes += "Direction of travel isn't tagged (oneway); assumed the mapped direction."
        val pts = densify(pts0)
        val n = pts.size

        // Station building: by name if given, else the one closest to the track.
        val stations = d.ways.filter { it.tags["roller_coaster"] == "station" && it.tags["roller_coaster:station_position"] != "unload" }
            .map { it to it.nodes.mapNotNull { n -> xy(n) } }.filter { it.second.size >= 3 }
        val station = stations.filter { (w, _) -> name != null && w.tags["name"].equals(name, ignoreCase = true) }.firstOrNull()
            ?: stations.minByOrNull { (_, poly) -> pts.minOf { p -> poly.minOf { dist(it, p) } } }
                ?.takeIf { (_, poly) -> pts.minOf { p -> poly.minOf { dist(it, p) } } < 40 }
        val ins = BooleanArray(n)
        if (station != null) {
            for (i in 0 until n) ins[i] = inside(pts[i], station.second)
            if (ins.none { it }) ins[(0 until n).minBy { i -> station.second.minOf { dist(it, pts[i]) } }] = true
        } else {
            notes += "No station building (roller_coaster=station) mapped; the station is placed at the track nearest the search result."
            ins[(0 until n).minBy { dist(pts[it], target) }] = true
        }
        // Station run = longest consecutive inside run (cyclic).
        var st0 = 0; var st1 = 0
        for (i in 0 until n) {
            if (ins[i] && !ins[(i - 1 + n) % n]) {
                var j = i
                while (ins[j % n] && j - i < n) j++
                if (j - i > st1 - st0) { st0 = i; st1 = j }
            }
        }
        if (st1 - st0 < 3) { st0 -= 8; st1 = st0 + 16 } // tiny overlap: assume a ~60 m platform
        val s0 = ((st0 % n) + n) % n
        val p = pts.subList(s0, n) + pts.subList(0, s0)
        val stEnd = st1 - st0

        // Lift / launch: tagged way if any, else the longest straight starting near the station exit.
        var liftStart = stEnd
        var liftEnd = -1
        var tagged = false
        for (w in used) {
            val kind = w.tags["roller_coaster:track"]
            if (kind == "lift_hill" || kind == "launch") {
                val ends = listOfNotNull(xy(w.nodes.first()), xy(w.nodes.last()))
                val window = stEnd until min(n, stEnd + 120)
                if (window.isEmpty() || ends.size < 2) continue
                val cand = ends.map { e -> window.minBy { dist(p[it], e) } }
                liftStart = cand.min(); liftEnd = cand.max(); tagged = true
                notes += "Lift/launch section from the ${kind.replace('_', ' ')} tag."
                break
            }
        }
        if (liftEnd < 0) {
            fun straightFrom(i0: Int): Int {
                val a = p[i0]; val b = p[min(n - 1, i0 + 3)]
                val dx = b.first - a.first; val dy = b.second - a.second
                val l0 = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
                var j = i0
                while (j < n - 1) {
                    val px = p[j + 1].first - a.first; val py = p[j + 1].second - a.second
                    if (abs(px * dy - py * dx) / l0 > 3.0) break
                    j++
                }
                return j
            }
            var best = -1
            for (i0 in stEnd until min(n - 4, stEnd + 30)) {
                val j = straightFrom(i0)
                if (j - i0 > best) { best = j - i0; liftStart = i0; liftEnd = j }
            }
            if (liftEnd < 0) liftEnd = min(n - 1, stEnd + 10)
            notes += "Lift/launch isn't tagged; used the longest straight after the station (${((liftEnd - liftStart) * 4)} m)."
        }

        val cum = DoubleArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + dist(p[i], p[(i + 1) % n])
        val total = cum[n]

        // Rotate to fill the 100 x 56 plan box, preferring the station along the bottom.
        var bestKey = Double.NEGATIVE_INFINITY to Double.NEGATIVE_INFINITY
        var rp = p; var sc = 1.0
        for (deg in 0 until 360 step 3) {
            val r = Math.toRadians(deg.toDouble())
            val q = p.map { (x, y) -> x * cos(r) - y * sin(r) to x * sin(r) + y * cos(r) }
            val w = q.maxOf { it.first } - q.minOf { it.first }
            val h = q.maxOf { it.second } - q.minOf { it.second }
            val s = min(92 / w.coerceAtLeast(1.0), 50 / h.coerceAtLeast(1.0))
            val sy = (0 until max(1, stEnd)).sumOf { q[it % n].second } / max(1, stEnd)
            val key = round(s * 10) / 10 to sy
            if (key.first > bestKey.first || key.first == bestKey.first && key.second > bestKey.second) { bestKey = key; rp = q; sc = s }
        }
        val x0 = rp.minOf { it.first }; val y0 = rp.minOf { it.second }
        val ox = (100 - (rp.maxOf { it.first } - x0) * sc) / 2
        val oy = (56 - (rp.maxOf { it.second } - y0) * sc) / 2
        val step = max(1, n / 160)
        val sel = ((0 until n step step).toSortedSet() + setOf(stEnd, liftStart, liftEnd).filter { it in 0 until n }).sorted()
        fun r5(v: Double) = round(v * 1e5) / 1e5
        fun r2(v: Double) = round(v * 100) / 100
        val plan = sel.map { i -> Triple(r5(cum[i] / total), r2(ox + (rp[i].first - x0) * sc), r2(oy + (rp[i].second - y0) * sc)) }
        return Result(
            RealLayouts.Layout(total, cum[stEnd] / total, cum[liftStart] / total, cum[liftEnd] / total, tagged, plan),
            notes, used.map { it.id }, gapM,
        )
    }

    /** Joins the ways end to end into one circuit, dropping dead-end spurs (unmapped transfer/storage tracks) if needed. */
    private fun chain(ways0: List<Way>, notes: MutableList<String>): Pair<List<Long>, List<Way>> {
        var ways = ways0
        while (true) {
            tryChain(ways)?.let { return it to ways }
            // Drop the shortest way that dead-ends (an endpoint no other way touches) and try again.
            val ends = ways.flatMap { listOf(it.nodes.first(), it.nodes.last()) }.groupingBy { it }.eachCount()
            val spur = ways.filter { ends[it.nodes.first()] == 1 || ends[it.nodes.last()] == 1 }.minByOrNull { it.nodes.size }
            if (spur == null || ways.size <= 1) throw ExtractError("The mapped track branches in a way the builder can't follow (switch or transfer track).")
            ways = ways - spur
            notes += "Ignored a dead-end track piece (way ${spur.id}), probably a transfer or storage spur."
        }
    }

    private fun tryChain(ways: List<Way>): List<Long>? {
        val segs = ways.map { it.nodes.toMutableList() }.toMutableList()
        var loop = segs.removeAt(0).toList()
        while (segs.isNotEmpty()) {
            val i = segs.indexOfFirst { it.first() == loop.last() || it.last() == loop.last() || it.last() == loop.first() || it.first() == loop.first() }
            if (i < 0) return null
            val sg = segs.removeAt(i)
            loop = when {
                sg.first() == loop.last() -> loop + sg.drop(1)
                sg.last() == loop.last() -> loop + sg.reversed().drop(1)
                sg.last() == loop.first() -> sg.dropLast(1) + loop
                else -> sg.reversed().dropLast(1) + loop
            }
        }
        return loop // closed circuits repeat the first node at the end; open ends have an unmapped gap (usually the station)
    }

    private fun forward(loop: List<Long>, ways: List<Way>): Boolean {
        val pos = HashMap<Long, Int>().apply { loop.forEachIndexed { i, nd -> put(nd, i) } }
        var v = 0
        for (w in ways) {
            if (w.tags["oneway"] != "yes") continue
            val a = pos[w.nodes[0]] ?: continue
            val b = pos[w.nodes[1]] ?: continue
            v += if (((b - a) % loop.size + loop.size) % loop.size == 1) 1 else -1
        }
        return v >= 0
    }

    private fun densify(pts: List<P>, step: Double = 4.0): List<P> {
        val out = mutableListOf<P>()
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val kk = max(1, (dist(a, b) / step).toInt())
            for (t in 0 until kk) out += a.first + (b.first - a.first) * t / kk to a.second + (b.second - a.second) * t / kk
        }
        return out
    }

    private fun inside(pt: P, poly: List<P>): Boolean {
        val (x, y) = pt
        var c = false
        for (i in poly.indices) {
            val (x1, y1) = poly[i]; val (x2, y2) = poly[(i + 1) % poly.size]
            if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) c = !c
        }
        return c
    }
}
