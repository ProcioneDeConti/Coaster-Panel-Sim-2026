package com.abemu.coaster.ui

import com.abemu.coaster.sim.Units

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.dev.CustomRideStore
import com.abemu.coaster.sim.CoasterSpec
import com.abemu.coaster.sim.CustomRides
import com.abemu.coaster.sim.OsmLayout
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RealLayouts
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.web.OsmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Editable ride parameters for the builder form. */
private data class Draft(
    val name: String, val type: ProfileType, val heightM: Int, val liftSpeed: Double, val launchMph: Int,
    val cars: Int, val perCar: Int, val mcbr: Boolean,
)

/**
 * RIDE BUILDER: find a real coaster on OpenStreetMap, pull its top-down track, set its specs and save it as a
 * custom ride (listed under CUSTOM on the INSTRUCTOR page).
 */
@Composable
fun BuilderPage(vm: SimViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<OsmClient.Hit>>(emptyList()) }
    var picked by remember { mutableStateOf<OsmClient.Hit?>(null) }
    var result by remember { mutableStateOf<OsmLayout.Result?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var saved by remember { mutableStateOf(0) } // bump to refresh the saved list
    saved

    fun search() {
        error = null; busy = "Searching OpenStreetMap…"; hits = emptyList()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { OsmClient.search(query) } }
                .onSuccess { hits = it; if (it.isEmpty()) error = "No coaster named \"$query\" is mapped on OpenStreetMap." }
                .onFailure { error = it.message }
            busy = null
        }
    }

    fun pick(h: OsmClient.Hit) {
        picked = h; result = null; draft = null; error = null; busy = "Downloading track for ${h.name}…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { OsmClient.fetchLayout(h) } }.onSuccess { r ->
                result = r
                val launch = r.notes.any { "launch tag" in it } || h.tags["roller_coaster:type"]?.contains("launch") == true
                val height = parseMetres(h.tags["height"]) ?: 40.0
                draft = Draft(
                    h.name, if (launch) ProfileType.LAUNCH else ProfileType.LIFT, height.roundToInt().coerceIn(10, 140), 4.0,
                    (sqrt(2 * 9.81 * (height * 1.15 + 6)) * 2.237).roundToInt().coerceIn(30, 130), 6, 4, r.layout.osmLengthM > 900,
                )
            }.onFailure { error = it.message ?: it.toString() }
            busy = null
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("RIDE BUILDER – FIND A COASTER ON OPENSTREETMAP") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditField(query, { query = it }, Modifier.weight(1f))
                    HmiButton("SEARCH", enabled = busy == null && query.trim().length >= 2, activeColor = Hmi.Info) { search() }
                    HmiButton("CLOSE", activeColor = Hmi.Info) { onClose() }
                }
                HmiLabel("Search by ride name, e.g. \"Steel Vengeance\" or \"Fury 325\". Needs an internet connection.", 10.sp, color = Hmi.Dim)
                busy?.let { HmiLabel(it, 11.sp, bold = true, color = Hmi.Info) }
                error?.let { HmiLabel(it, 11.sp, bold = true, color = Hmi.Bad) }
                hits.forEach { h ->
                    HmiButton("${h.name}  ·  ${h.what}  ·  ${h.where}", Modifier.fillMaxWidth(), enabled = busy == null,
                        active = picked === h, activeColor = Hmi.Info, fontSize = 10.sp) { pick(h) }
                }
            }
            SavedRides(vm, saved) { id ->
                CustomRides.remove(id)
                CustomRideStore.save(ctx)
                saved++
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val r = result
            val d = draft
            val h = picked
            if (r == null || d == null || h == null) {
                HmiFrame("PREVIEW") {
                    HmiLabel("Pick a search result to download its track and preview the layout.", 11.sp, color = Hmi.Dim)
                }
                return@Column
            }
            HmiFrame("PREVIEW – ${h.name.uppercase()}") {
                PlanPreview(r.layout, Modifier.fillMaxWidth().aspectRatio(100f / 56f))
                HmiLabel("Track ${r.layout.osmLengthM.roundToInt()} m · station ${(r.layout.stationEnd * r.layout.osmLengthM).roundToInt()} m · " +
                    "${if (r.layout.liftTagged) "lift tagged" else "lift guessed"}", 11.sp, bold = true)
                HmiLabel("Green: station · amber: lift / launch · white dot: station entry", 10.sp, color = Hmi.Dim)
                r.notes.forEach { HmiLabel("• $it", 10.sp, color = Hmi.Dim) }
                HmiLabel("Map data © OpenStreetMap contributors (ODbL)", 9.sp, color = Hmi.Dim)
            }
            HmiFrame("RIDE SPECS") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Name", 11.sp, modifier = Modifier.width(110.dp))
                    EditField(d.name, { draft = d.copy(name = it.take(28)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Type", 11.sp, modifier = Modifier.width(110.dp))
                    ProfileType.entries.forEach { t ->
                        HmiButton(t.label.uppercase(), active = d.type == t, activeColor = Hmi.Info) { draft = d.copy(type = t) }
                    }
                }
                Stepper(if (d.type == ProfileType.LIFT) "Lift height" else "Top hat height", "${(d.heightM * Units.FT).roundToInt()} ft",
                    { draft = d.copy(heightM = (d.heightM - 5).coerceAtLeast(10)) }, { draft = d.copy(heightM = (d.heightM + 5).coerceAtMost(140)) })
                if (d.type == ProfileType.LIFT) Stepper("Lift speed", "%.0f mph".format(Units.mph(d.liftSpeed)),
                    { draft = d.copy(liftSpeed = (d.liftSpeed - 1 / Units.MPH).coerceAtLeast(4 / Units.MPH)) }, { draft = d.copy(liftSpeed = (d.liftSpeed + 1 / Units.MPH).coerceAtMost(20 / Units.MPH)) })
                else Stepper("Launch speed", "${d.launchMph} mph",
                    { draft = d.copy(launchMph = (d.launchMph - 5).coerceAtLeast(30)) }, { draft = d.copy(launchMph = (d.launchMph + 5).coerceAtMost(130)) })
                Stepper("Cars per train", "${d.cars}", { draft = d.copy(cars = (d.cars - 1).coerceAtLeast(2)) }, { draft = d.copy(cars = (d.cars + 1).coerceAtMost(10)) })
                Stepper("Riders per car", "${d.perCar}", { draft = d.copy(perCar = (d.perCar - 1).coerceAtLeast(1)) }, { draft = d.copy(perCar = (d.perCar + 1).coerceAtMost(8)) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Mid-course brake", 11.sp, modifier = Modifier.width(110.dp))
                    HmiButton("YES", active = d.mcbr, activeColor = Hmi.Info) { draft = d.copy(mcbr = true) }
                    HmiButton("NO", active = !d.mcbr, activeColor = Hmi.Info) { draft = d.copy(mcbr = false) }
                }
                HmiLabel("Hills are generated to fit the energy available; the course is stretched to the real track length.", 10.sp, color = Hmi.Dim)
                val canSwitch = vm.plc.state == RideState.POWER_OFF
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HmiButton("SAVE RIDE", Modifier.weight(1f), enabled = d.name.isNotBlank(), activeColor = Hmi.Ok) {
                        CustomRides.put(CustomRides.Ride(spec(d, h, r), r.layout, "OSM ways ${r.trackWayIds.joinToString(",")}"))
                        CustomRideStore.save(ctx)
                        saved++
                    }
                    HmiButton("SAVE + LOAD" + if (!canSwitch) " (power off)" else "", Modifier.weight(1f),
                        enabled = d.name.isNotBlank() && canSwitch, activeColor = Hmi.Ok) {
                        val s = spec(d, h, r)
                        CustomRides.put(CustomRides.Ride(s, r.layout, "OSM ways ${r.trackWayIds.joinToString(",")}"))
                        CustomRideStore.save(ctx)
                        saved++
                        if (vm.reconfigure(id = s.id)) onClose()
                    }
                }
            }
        }
    }
}

private fun spec(d: Draft, h: OsmClient.Hit, r: OsmLayout.Result): CoasterSpec {
    val id = "osm_" + (r.trackWayIds.minOrNull() ?: h.name.hashCode().toLong())
    val launch = d.type == ProfileType.LAUNCH
    return CoasterSpec(
        id, d.name.trim(), d.type, d.heightM.toDouble(),
        launchSpeed = if (launch) d.launchMph / 2.237 else 0.0, liftSpeed = d.liftSpeed,
        hills1 = listOf(0.65, 0.5, 0.55), mcbrFrac = 0.4, hills2 = listOf(0.55, 0.4, 0.3),
        cars = d.cars, ridersPerCar = d.perCar, layout = 0, seed = (id.hashCode() and 0xffff),
        park = h.tags["operator"] ?: "OpenStreetMap", hasMcbr = d.mcbr,
        note = "Built from OpenStreetMap (${r.layout.osmLengthM.roundToInt()} m of track). Layout is real; the profile is generated.",
    )
}

/** "62.5", "62.5 m" or "205 ft" → metres. */
private fun parseMetres(v: String?): Double? {
    v ?: return null
    val num = Regex("[0-9]+(\\.[0-9]+)?").find(v)?.value?.toDoubleOrNull() ?: return null
    return if (v.contains("ft") || v.contains("'")) num / 3.281 else num
}

@Composable
private fun Stepper(label: String, value: String, dec: () -> Unit, inc: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        HmiLabel(label, 11.sp, modifier = Modifier.width(110.dp))
        HmiButton("−", activeColor = Hmi.Info) { dec() }
        Box(Modifier.width(130.dp), contentAlignment = Alignment.Center) { HmiLabel(value, 12.sp, bold = true) }
        HmiButton("+", activeColor = Hmi.Info) { inc() }
    }
}

@Composable
private fun SavedRides(vm: SimViewModel, refresh: Int, onDelete: (String) -> Unit) {
    refresh
    HmiFrame("SAVED CUSTOM RIDES") {
        if (CustomRides.rides.isEmpty()) HmiLabel("None yet.", 11.sp, color = Hmi.Dim)
        CustomRides.rides.toList().forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HmiLabel("${r.spec.name} · ${r.spec.type.label} · ${Units.ft(r.layout.osmLengthM).roundToInt()} ft", 11.sp, modifier = Modifier.weight(1f))
                val loaded = vm.coasterId == r.spec.id
                HmiButton(if (loaded) "IN USE" else "DELETE", enabled = !loaded, activeColor = Hmi.Bad) { onDelete(r.spec.id) }
            }
        }
    }
}

@Composable
private fun PlanPreview(l: RealLayouts.Layout, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFF1B2229))
        val sx = size.width / 100f
        val sy = size.height / 56f
        fun o(x: Double, y: Double) = Offset(x.toFloat() * sx, y.toFloat() * sy)
        val path = Path()
        l.points.forEachIndexed { i, (_, x, y) -> val p = o(x, y); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        path.close()
        drawPath(path, Color(0xFFB0BEC5), style = Stroke(3f))
        fun seg(a: Double, b: Double, c: Color) {
            val pts = l.points.filter { it.first in a..b }
            pts.zipWithNext().forEach { (p, q) -> drawLine(c, o(p.second, p.third), o(q.second, q.third), 7f) }
        }
        seg(0.0, l.stationEnd, Color(0xFF2EBD59))
        seg(l.liftStart, l.liftEnd, Color(0xFFFFB300))
        l.points.firstOrNull()?.let { drawCircle(Color.White, 6f, o(it.second, it.third)) }
    }
}
