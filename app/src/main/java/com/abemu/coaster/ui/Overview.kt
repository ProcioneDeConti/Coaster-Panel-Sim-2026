package com.abemu.coaster.ui

import com.abemu.coaster.sim.Units

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.BlockKind
import com.abemu.coaster.sim.TrainStatus
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import kotlin.math.abs
import androidx.compose.ui.draw.clipToBounds

internal val trainColors = listOf(Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFEF6C00))

/** Colour of train [id] on this ride: the real livery where the spec has one, else the generic palette. */
internal fun trainColor(profile: RideProfile, id: Int): Color =
    profile.spec.liveries?.let { Color(it[(id - 1) % it.size]) } ?: trainColors[(id - 1) % trainColors.size]

/** Legible text colour on top of [bg] (dark on yellow, white elsewhere). */
internal fun inkOn(bg: Color): Color = if (bg.luminance() > 0.5f) Color(0xFF15191D) else Color.White

private val legendKey = listOf(
    "━" to Color(0xFFE53935), " occupied  " to null, "━" to Color(0xFF43A047), " clear    " to null,
    "┅" to Color(0xFFFF1744), " brake closed  " to null, "┅" to Color(0xFF00E676), " open    " to null,
    "●" to Color(0xFFFFEB3B), " entry prox" to null,
)

/** Diagram key on a dark backing so scenery and track never run through it; each symbol in its own colour. */
internal fun DrawScope.legendChip(tm: TextMeasurer, title: String?, at: Offset, draw: Boolean = true): Rect {
    val text = buildAnnotatedString {
        if (title != null) withStyle(SpanStyle(color = Color(0xFFB0BEC5), fontWeight = FontWeight.Bold)) { append("$title     ") }
        legendKey.forEach { (str, col) -> withStyle(SpanStyle(color = col ?: Color(0xFF8FA3B1))) { append(str) } }
    }
    val l = tm.measure(text, TextStyle(fontSize = 10.sp))
    val px = 6.dp.toPx(); val py = 3.dp.toPx()
    val r = Rect(at - Offset(px, py), Size(l.size.width + 2 * px, l.size.height + 2 * py))
    if (draw) {
        drawRoundRect(Color(0xD90A121A), r.topLeft, r.size, CornerRadius(py * 2))
        drawText(l, topLeft = at)
    }
    return r
}

/** Train ID + speed (mph) as a pill in the train's colour. [bottomLeft] is the preferred spot; if it would cover a
 *  tag already drawn this frame (trains parked nose to tail), it steps up, then down, until clear. */
internal fun DrawScope.trainTag(tm: TextMeasurer, id: Int, vMs: Double, color: Color, bottomLeft: Offset, placed: MutableList<Rect>) {
    val l = tm.measure("T$id  ${"%.0f".format(Units.mph(abs(vMs)))} mph",
        TextStyle(color = inkOn(color), fontSize = 10.sp, fontWeight = FontWeight.Bold))
    val px = 5.dp.toPx(); val py = 1.5.dp.toPx()
    val w = l.size.width + 2 * px; val h = l.size.height + 2 * py
    val x = bottomLeft.x.coerceIn(2f, (size.width - w - 2f).coerceAtLeast(2f))
    val base = Rect(x, bottomLeft.y - h, x + w, bottomLeft.y)
    val r = sequenceOf(0, -1, 1, -2, 2, -3, 3)
        .map { k -> base.translate(0f, k * (h + 3f)) }
        .map { if (it.top < 2f) it.translate(0f, 2f - it.top) else it }
        .firstOrNull { c -> placed.none { it.overlaps(c) } } ?: base
    placed += r
    drawRoundRect(Color(0x66000000), r.topLeft + Offset(0f, 2f), r.size, CornerRadius(h / 2))
    drawRoundRect(color, r.topLeft, r.size, CornerRadius(h / 2))
    drawRoundRect(Color.White.copy(alpha = 0.55f), r.topLeft, r.size, CornerRadius(h / 2), style = Stroke(1f))
    drawText(l, topLeft = r.topLeft + Offset(px, py))
}


/**
 * Draws a train as linked cars that bend with the track. [at] gives the canvas point [d] metres behind the head.
 * Cars are sized in pixels so they stay readable at any zoom; the lead car carries a white nose.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrainCars(
    cars: Int, color: Color, width: Float = 14f, carPx: Float = 34f, gapPx: Float = 8f, at: (Double) -> Offset,
) {
    val total = cars * (carPx + gapPx)
    // Walk back from the head, recording (pixel distance, point). Big jumps (wrap-around in the profile) are skipped.
    val pts = ArrayList<Pair<Float, Offset>>()
    var prev = at(0.0)
    var px = 0f
    pts += 0f to prev
    var d = 0.0
    while (px < total && d < 600.0) {
        d += 0.25
        val p = at(d)
        val step = (p - prev).getDistance()
        if (step < 40f) px += step
        prev = p
        pts += px to p
    }
    val outline = Color(0xFF101418)
    for (k in cars - 1 downTo 0) {
        val f = k * (carPx + gapPx)
        val r = f + carPx
        val seg = pts.filter { it.first in f..r }.map { it.second }
        if (seg.size < 2) continue
        val path = Path().apply { moveTo(seg[0].x, seg[0].y); seg.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, outline, style = Stroke(width + 4f, cap = StrokeCap.Butt))
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt))
    }
    drawCircle(outline, width * 0.42f, pts[0].second)
    drawCircle(Color.White, width * 0.3f, pts[0].second)
}

@Composable
fun OverviewPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    var plan by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (plan) PlanDiagram(plc, Modifier.fillMaxSize()) else TrackDiagram(plc, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HmiButton("PLAN", active = plan, activeColor = Hmi.Info) { plan = true }
                HmiButton("PROFILE", active = !plan, activeColor = Hmi.Info) { plan = false }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockStatusFrame(plc, Modifier.weight(1.6f))
            HmiFrame("RIDE DATA", Modifier.weight(1f)) {
                if (plc.profile.type == ProfileType.LIFT) {
                    ValueBox("Lift chain", "%.1f ft/s".format(Units.fps(plc.plant.chainSpeed)))
                } else {
                    ValueBox("LSM state", plc.launchState.label)
                    ValueBox("Last exit spd", "%.1f mph".format(Units.mph(plc.plant.lastLaunchExitSpeed)))
                }
                ValueBox("Main air", "${plc.plant.airPsi.toInt()} psi")
                ValueBox("Trains (circuit/stor)", "${plc.plant.trains.size} / ${plc.plant.stored}")
                ValueBox("Dispatches", plc.dispatchCount.toString())
                ValueBox("PLC scan", "20 ms  #${plc.scanCount % 100000}")
            }
        }
    }
}

/** Per-block occupancy, train, brake and speed table. */
@Composable
fun BlockStatusFrame(plc: com.abemu.coaster.sim.RidePlc, modifier: Modifier = Modifier) {
    HmiFrame("BLOCK STATUS", modifier) {
        Row {
            listOf("BLOCK" to 2.2f, "STATUS" to 1.1f, "TRAIN" to 0.6f, "BRAKE" to 0.9f, "SPEED" to 0.8f).forEach { (h, w) ->
                HmiLabel(h, 10.sp, bold = true, color = Hmi.Dim, modifier = Modifier.weight(w))
            }
        }
        plc.profile.blocks.forEach { b ->
            val id = plc.occ[b.index]
            val brk = plc.plant.brakes[b.index]
            // Zebra rows; an occupied block gets a faint red wash so it reads at a glance.
            val rowBg = when {
                id != -1 && plc.powered -> Color(0x1AE53935)
                b.index % 2 == 0 -> Color(0x0D000000)
                else -> Color.Transparent
            }
            Row(Modifier.background(rowBg).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                HmiLabel("${b.tag} ${b.name}", 11.sp, modifier = Modifier.weight(2.2f))
                Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
                    val hold = plc.holdT[b.index]
                    Indicator(id != -1, if (hold > 0) Hmi.Warn else Hmi.Bad, Hmi.Ok)
                    HmiLabel(when {
                        hold > 0 -> " HOLD ${hold.toInt()} s"
                        id != -1 -> " OCCUPIED"
                        else -> " CLEAR"
                    }, 11.sp, bold = hold > 0, color = if (hold > 0) Color(0xFF8A5A00) else Hmi.Text)
                }
                HmiLabel(if (id != -1) "T$id" + plc.pending[b.index].let { f -> if (f != -1) "+T$f" else "" } else "—", 11.sp, bold = true, modifier = Modifier.weight(0.6f))
                HmiLabel(
                    when {
                        b.kind == BlockKind.LIFT -> if (plc.plant.chainSpeed > 0.1) "CHAIN RUN" else "CHAIN STOP"
                        brk.openProx -> "OPEN"
                        brk.closedProx -> "CLOSED"
                        else -> "MOVING"
                    },
                    11.sp, color = if (brk.isOpen) Hmi.Ok else Hmi.Text, modifier = Modifier.weight(0.9f),
                )
                HmiLabel(if (id != -1) "%.1f mph".format(Units.mph(abs(plc.trainSpeed(id)))) else "", 11.sp, modifier = Modifier.weight(0.8f))
            }
        }
    }
}

/** Side-elevation track diagram with live block occupancy, brakes, sensors and trains. */
@Composable
fun TrackDiagram(plc: RidePlc, modifier: Modifier = Modifier, ghost: RideProfile? = null) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    val profile = plc.profile
    val plant = plc.plant
    val blink = flasher()
    // Track and ghost outlines only change with the ride or the canvas size: build them once, not every frame.
    val outline = remember(profile, ghost) { TrackOutlineCache() }
    Canvas(modifier.background(Color(0xFF0F1A24))) {
        val padL = 16f
        val padR = 16f
        val top = 34f
        val bottom = size.height - 46f
        val w = size.width - padL - padR
        val maxH = maxOf(profile.maxHeight, profile.spikeHeight) + 6
        fun x(s: Double) = padL + (s / profile.length * w).toFloat()
        fun y(s: Double) = (bottom - profile.height(s) / maxH * (bottom - top)).toFloat()
        fun pt(s: Double) = Offset(x(s), y(s))

        // Grid
        for (h in 0..Units.ft(maxH).toInt() step 50) {
            val yy = (bottom - h / Units.FT / maxH * (bottom - top)).toFloat()
            drawLine(Color(0xFF1E2E3D), Offset(padL, yy), Offset(size.width - padR, yy))
            drawText(tm, "${h}ft", Offset(2f, yy - 14f), TextStyle(color = Color(0xFF4F6578), fontSize = 9.sp))
        }

        // Block bands along the bottom
        profile.blocks.forEach { b ->
            val occ = plc.occ[b.index] != -1
            val color = when {
                !plc.powered -> Color(0xFF3A4550)
                occ -> Color(0xFFC62828)
                else -> Color(0xFF2E7D32)
            }
            val segs = if (b.start <= b.end) listOf(b.start to b.end) else listOf(b.start to profile.length, 0.0 to b.end)
            segs.forEach { (a, e) ->
                drawRect(color, Offset(x(a) + 1, bottom + 8f), Size(x(e) - x(a) - 2, 12f))
            }
            val mid = if (b.start <= b.end) (b.start + b.end) / 2 else (b.end / 2)
            drawText(tm, b.tag, Offset(x(mid) - 8f, bottom + 22f), TextStyle(color = Color(0xFFB0BEC5), fontSize = 10.sp, fontWeight = FontWeight.Bold))
        }

        // Track
        if (outline.size != size) {
            outline.size = size
            outline.track = Path().apply {
                var s = 0.0
                moveTo(x(0.0), y(0.0))
                while (s <= profile.length) { lineTo(x(s), y(s)); s += 2.0 }
            }
            // Generated track before tags reshaped it (editor preview)
            outline.ghost = ghost?.let { gh ->
                Path().apply {
                    var g = 0.0
                    moveTo(x(0.0), (bottom - gh.height(0.0) / maxH * (bottom - top)).toFloat())
                    while (g <= profile.length) { lineTo(x(g), (bottom - gh.height(g) / maxH * (bottom - top)).toFloat()); g += 2.0 }
                }
            }
        }
        outline.ghost?.let { drawPath(it, Color(0xFF607D8B), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))) }
        drawPath(outline.track!!, Color(0xFF90A4AE), style = Stroke(4f, cap = StrokeCap.Round))

        // Lift chain / LSM section
        if (profile.type == ProfileType.LIFT) {
            val run = plant.chainSpeed > 0.1
            val p = Path().apply {
                moveTo(x(profile.liftStart), y(profile.liftStart))
                var q = profile.liftStart
                while (q <= profile.liftEnd) { lineTo(x(q), y(q)); q += 2.0 }
            }
            drawPath(p, if (run) Color(0xFFFFB300) else Color(0xFF6D5A2A),
                style = Stroke(6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), if (run) (System.currentTimeMillis() / 40 % 10).toFloat() * -1 else 0f)))
        } else {
            val firing = plc.launchState == LaunchState.FIRING
            val charging = plc.launchState == LaunchState.CHARGING
            drawLine(
                when { firing -> Color(0xFF40C4FF); charging && blink -> Color(0xFF40C4FF); else -> Color(0xFF1E4D6B) },
                pt(profile.launchStart), pt(profile.launchEnd), strokeWidth = 7f,
            )
            drawText(tm, "LSM", pt((profile.launchStart + profile.launchEnd) / 2) + Offset(-10f, 6f),
                TextStyle(color = Color(0xFF40C4FF), fontSize = 9.sp))
        }

        // Rear spike of a swing launch, drawn as a vertical tower at the junction
        val base = profile.height(profile.spikeJunction)
        fun spikeY(d: Double) = (bottom - (base + profile.spikeHeightAt(d)) / maxH * (bottom - top)).toFloat()
        if (profile.spikeHeight > 0) {
            val sx = x(profile.spikeJunction)
            drawLine(Color(0xFF90A4AE), Offset(sx, y(profile.spikeJunction)), Offset(sx, spikeY(profile.spikeLength)), strokeWidth = 4f)
            drawText(tm, "SPIKE", Offset(sx + 4f, spikeY(profile.spikeLength)), TextStyle(color = Color(0xFF90A4AE), fontSize = 9.sp))
        }

        // Brakes
        profile.blocks.forEach { b ->
            val bs = b.brakeStart ?: return@forEach
            val stop = b.stopPos ?: return@forEach
            val brk = plant.brakes[b.index]
            val from = if (b.kind == BlockKind.STATION) stop - 22 else bs
            val c = when {
                brk.openProx -> Color(0xFF00E676)
                brk.closedProx -> Color(0xFFFF1744)
                else -> Color(0xFFFFEA00)
            }
            drawLine(c, pt(from) + Offset(0f, -9f), pt(stop) + Offset(0f, -9f), strokeWidth = 5f)
        }

        // Entry proximity sensors
        profile.blocks.forEach { b ->
            val on = plant.entrySensor(b.index)
            drawCircle(if (on) Color(0xFFFFEB3B) else Color(0xFF455A64), 5f, pt(b.start) + Offset(0f, 10f))
        }

        // Trim brakes and extra prox sensors (from editor tags)
        profile.trims.forEach { tr ->
            var q = tr.start
            while (q < tr.end) { drawLine(Color(0xFFFF9100), pt(q) + Offset(0f, -7f), pt(q + 2) + Offset(0f, -7f), strokeWidth = 4f); q += 2.0 }
        }
        profile.sensors.forEachIndexed { i, s ->
            drawRect(if (plant.proxSensor(i)) Color(0xFFFFEB3B) else Color(0xFF455A64), pt(s.s) + Offset(-4f, 6f), Size(8f, 8f))
        }

        // Trains
        val tags = arrayListOf(legendChip(tm, null, Offset(padL, 6f), draw = false))
        plant.trains.forEach { t ->
            val c = trainColor(profile, t.id)
            val sx = x(profile.spikeJunction)
            val back = if (t.tailOnSpike && t.spike <= 0) profile.ahead(profile.spikeJunction, t.s) else 0.0
            drawTrainCars(profile.spec.cars, c) { d ->
                when {
                    t.spike > 0 -> Offset(sx, spikeY(t.spike + d))
                    t.tailOnSpike && d > back -> Offset(sx, spikeY(d - back))
                    else -> pt(t.s - d)
                }
            }
            if (t.spike > 0) {
                trainTag(tm, t.id, t.v, c, Offset(sx - 18f, spikeY(t.spike) - 14f), tags)
                return@forEach
            }
            trainTag(tm, t.id, t.v, c, pt(t.s) + Offset(-18f, -14f), tags)
        }

        // Remote E-Stop location marker
        val inp = plc.inputs
        val where = listOfNotNull(
            "LOAD STAND".takeIf { inp.loadStandEstop },
            "UNLOAD STAND".takeIf { inp.unloadStandEstop },
            "CONSOLE".takeIf { inp.estopPressed },
        )
        if (where.isNotEmpty() && blink) {
            val st = profile.blocks[0].stopPos!!
            drawText(tm, "⚠ E-STOP: " + where.joinToString(" + "), pt(st) + Offset(-4f, -64f),
                TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, background = Color(0xFFD50000)))
        }

        legendChip(tm, null, Offset(padL, 6f))
    }
}


/** Side-elevation track outlines for [TrackDiagram], rebuilt when the canvas size changes. */
private class TrackOutlineCache {
    var size = Size.Unspecified
    var track: Path? = null
    var ghost: Path? = null
}

/** Maps the 100 x 56 plan box onto a canvas of the given size. */
class PlanTransform(val profile: com.abemu.coaster.sim.RideProfile, val width: Float, val height: Float) {
    private val pad = 28f
    private val scale = minOf((width - pad * 2) / 100f, (height - pad * 2 - 14f) / 56f)
    private val ox = (width - 100f * scale) / 2
    private val oy = pad + 14f + (height - pad * 2 - 14f - 56f * scale) / 2

    fun pt(s: Double): Offset = planPt(profile.planPoint(s))

    /** Canvas position of a point in the plan box. */
    fun planPt(p: Pair<Double, Double>): Offset = Offset(ox + p.first.toFloat() * scale, oy + p.second.toFloat() * scale)

    private var spike: Path? = null

    /** Spike line from the junction to its tip (cached). */
    fun spikePath(): Path = spike ?: Path().also { path ->
        var d = 0.0
        while (d <= profile.spikeLength) {
            val p = planPt(profile.spikePlanPoint(d))
            if (d == 0.0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            d += 2.0
        }
        spike = path
    }

    private val segCache = HashMap<Pair<Double, Double>, Path>()

    /** Track path between two positions (forward, handling wrap). Cached: the track never moves. */
    fun seg(from: Double, to: Double): Path {
        if (segCache.size > 256) segCache.clear() // editor drags make one-off segments
        return segCache.getOrPut(from to to) { buildSeg(from, to) }
    }

    private fun buildSeg(from: Double, to: Double): Path {
        val len = profile.ahead(from, to).let { if (it == 0.0) profile.length else it }
        val path = Path()
        path.moveTo(pt(from).x, pt(from).y)
        var d = 0.0
        while (d < len) { d = minOf(len, d + 2.0); val p = pt(from + d); path.lineTo(p.x, p.y) }
        return path
    }

    /** Track position (metres) nearest to a canvas point, and its distance in pixels. */
    fun nearest(p: Offset): Pair<Double, Float> {
        var best = 0.0
        var bestD = Float.MAX_VALUE
        var s = 0.0
        while (s < profile.length) {
            val d = (pt(s) - p).getDistance()
            if (d < bestD) { bestD = d; best = s }
            s += 0.5
        }
        return best to bestD
    }
}

/**
 * Top-down (plan view) track diagram, the usual layout on ride HMIs.
 * [onTapTrack] receives the track position nearest a tap; [overlay] draws on top (editor tags).
 */
@Composable
fun PlanDiagram(
    plc: RidePlc,
    modifier: Modifier = Modifier,
    onTapTrack: ((Double) -> Unit)? = null,
    overlay: (androidx.compose.ui.graphics.drawscope.DrawScope.(PlanTransform) -> Unit)? = null,
) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    val profile = plc.profile
    val plant = plc.plant
    val blink = flasher()
    val tap by androidx.compose.runtime.rememberUpdatedState(onTapTrack)
    val tapMod = if (onTapTrack == null) Modifier else Modifier.pointerInput(profile) {
        detectTapGestures { p ->
            val (s, d) = PlanTransform(profile, size.width.toFloat(), size.height.toFloat()).nearest(p)
            if (d < 60f) tap?.invoke(s)
        }
    }
    // Reused across frames so the cached track paths survive; rebuilt on a new ride or size.
    val xfCache = remember(profile) { arrayOfNulls<PlanTransform>(1) }
    val scenery = remember(profile) { PlanSceneryCache() }
    val night = Night.on
    Canvas(modifier.clipToBounds().background(if (night) Color(0xFF060A0F) else Color(0xFF0F1A24)).then(tapMod)) {
        val xf = xfCache[0]?.takeIf { it.width == size.width && it.height == size.height }
            ?: PlanTransform(profile, size.width, size.height).also { xfCache[0] = it }
        fun pt(s: Double) = xf.pt(s)
        fun seg(from: Double, to: Double) = xf.seg(from, to)

        // Scenery from OSM (real rides only), pre-rendered: water, islands, grass, trees.
        scenery.get(xf, night, this)?.let { drawImage(it) }

        // Station platform and transfer spur
        val st = profile.blocks[0]
        val a = pt(st.stopPos!! - profile.trainLength - 6)
        val b = pt(st.stopPos + 4)
        drawRect(Color(0xFF37474F), Offset(minOf(a.x, b.x), a.y + 6f), Size(kotlin.math.abs(b.x - a.x), 14f))
        drawText(tm, "STATION", Offset(minOf(a.x, b.x), a.y + 22f), TextStyle(color = Color(0xFF90A4AE), fontSize = 9.sp, background = Color(0xB30F1A24)))
        val fb = profile.blocks[profile.transferBlock]
        val f1 = pt(fb.brakeStart!!)
        val f2 = pt(fb.stopPos!!)
        drawLine(Color(0xFF455A64), f1 + Offset(0f, -16f), f2 + Offset(0f, -16f), 5f)
        repeat(plant.stored) { k ->
            val x = f1.x + (f2.x - f1.x) * (0.25f + 0.4f * k)
            drawLine(Color(0xFF00897B), Offset(x - 14f, f1.y - 16f), Offset(x + 14f, f1.y - 16f), 9f, StrokeCap.Round)
        }
        drawText(tm, "TRANSFER / STORAGE", Offset(minOf(f1.x, f2.x), f1.y - 34f), TextStyle(color = Color(0xFF607D8B), fontSize = 9.sp, background = Color(0xB30F1A24)))

        // Track, coloured by block occupancy
        profile.blocks.forEach { blk ->
            val occ = plc.occ[blk.index] != -1
            val c = when {
                !plc.powered -> Color(0xFF5B6B78)
                occ -> Color(0xFFE53935)
                else -> Color(0xFF43A047)
            }
            drawPath(seg(blk.start, blk.end), c, style = Stroke(7f, cap = StrokeCap.Butt))
            val mid = blk.start + profile.ahead(blk.start, blk.end) / 2
            drawText(tm, blk.tag, pt(mid) + Offset(6f, 6f),
                TextStyle(color = Color(0xFFCFD8DC), fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        // Rails/centreline
        drawPath(seg(0.0, 0.0), Color(0xFF0F1A24), style = Stroke(1.5f))
        if (night) {
            // Track lighting: LED strips along the whole circuit, slowly chasing and shifting colour.
            val ms = System.currentTimeMillis()
            val hue = (ms / 60 % 360).toFloat()
            val full = seg(0.0, 0.0)
            drawPath(full, Color.hsv(hue, 0.8f, 1f).copy(alpha = 0.18f), style = Stroke(20f, cap = StrokeCap.Round))
            drawPath(full, Color.hsv((hue + 40) % 360, 0.7f, 1f).copy(alpha = 0.85f),
                style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f), -(ms / 25 % 14).toFloat())))
        }

        // Swing launch: spike line behind the switch track, and the switch table itself
        if (profile.planSpike.isNotEmpty()) {
            val path = xf.spikePath()
            val onSpike = plant.trains.any { it.spike > 0 || it.tailOnSpike }
            drawPath(path, if (onSpike) Color(0xFFE53935) else Color(0xFF546E7A), style = Stroke(7f, cap = StrokeCap.Butt))
            val tip = xf.planPt(profile.spikePlanPoint(profile.spikeLength))
            drawCircle(Color(0xFF90A4AE), 6f, tip)
            drawText(tm, "SPIKE", tip + Offset(8f, -6f), TextStyle(color = Color(0xFF90A4AE), fontSize = 9.sp))
            val sw = plant.switchPos
            drawPath(seg(profile.spikeJunction, profile.switchEnd), Color(0xFFAB47BC), style = Stroke(11f, cap = StrokeCap.Butt))
            drawText(tm, "SWITCH " + when { sw < 0.01 -> "STN"; sw > 0.99 -> "SPIKE"; else -> "MOVING" },
                pt(profile.switchEnd) + Offset(8f, 4f),
                TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, background = Color(0xFF6A1B9A)))
        }

        // Lift chain / LSM
        if (profile.type == ProfileType.LIFT) {
            val run = plant.chainSpeed > 0.1
            drawPath(seg(profile.liftStart, profile.liftEnd), if (run) Color(0xFFFFB300) else Color(0xFF6D5A2A),
                style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f),
                    if (run) -(System.currentTimeMillis() / 30 % 14).toFloat() else 0f)))
        } else {
            val on = plc.launchState == LaunchState.FIRING || (plc.launchState == LaunchState.CHARGING && blink)
            drawPath(seg(profile.launchStart, profile.launchEnd), if (on) Color(0xFF40C4FF) else Color(0xFF1E4D6B), style = Stroke(4f))
        }

        // Brakes: bar beside the track
        profile.blocks.forEach { blk ->
            val bs = blk.brakeStart ?: return@forEach
            val stop = blk.stopPos ?: return@forEach
            val brk = plant.brakes[blk.index]
            val from = if (blk.kind == BlockKind.STATION) stop - 22 else bs
            val c = when {
                brk.openProx -> Color(0xFF00E676)
                brk.closedProx -> Color(0xFFFF1744)
                else -> Color(0xFFFFEA00)
            }
            drawPath(seg(from, stop), c, style = Stroke(14f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))))
        }

        // Entry proximity sensors
        profile.blocks.forEach { blk ->
            val on = plant.entrySensor(blk.index)
            drawCircle(Color.Black, 6.5f, pt(blk.start))
            drawCircle(if (on) Color(0xFFFFEB3B) else Color(0xFF546E7A), 5f, pt(blk.start))
        }

        // Trim brakes and extra prox sensors (from editor tags)
        profile.trims.forEach { tr -> drawPath(seg(tr.start, tr.end), Color(0xFFFF9100), style = Stroke(8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)))) }
        profile.sensors.forEachIndexed { i, s ->
            val c = pt(s.s)
            drawRect(Color.Black, c - Offset(5.5f, 5.5f), Size(11f, 11f))
            drawRect(if (plant.proxSensor(i)) Color(0xFFFFEB3B) else Color(0xFF546E7A), c - Offset(4f, 4f), Size(8f, 8f))
        }

        // Trains
        val tags = arrayListOf(legendChip(tm, "PLAN VIEW", Offset(8f, 6f), draw = false))
        plant.trains.forEach { t ->
            val c = trainColor(profile, t.id)
            // One rectangle per train, lying along the track at its front (up the spike line when on it).
            // Outline: green = normal, yellow = PLC checking it, red = stopped on a fault.
            fun spikeAt(d: Double) = xf.planPt(profile.spikePlanPoint(d.coerceIn(0.0, profile.spikeLength)))
            val head = if (t.spike > 0) spikeAt(t.spike) else pt(t.s)
            val tail = if (t.spike > 0) spikeAt(t.spike + 6) else pt(t.s - 6)
            val dir = (head - tail).let { v -> if (v.getDistance() > 0.01f) v / v.getDistance() else Offset(1f, 0f) }
            val nrm = Offset(-dir.y, dir.x)
            val ring = when (plc.trainStatus(t)) {
                TrainStatus.GOOD -> Color(0xFF00E676)
                TrainStatus.CHECKING -> Color(0xFFFFD600)
                TrainStatus.BAD -> Color(0xFFFF1744)
            }
            fun rect(front: Float, back: Float, half: Float) = Path().apply {
                val a = head + dir * front
                val b = head - dir * back
                moveTo((a + nrm * half).x, (a + nrm * half).y); lineTo((b + nrm * half).x, (b + nrm * half).y)
                lineTo((b - nrm * half).x, (b - nrm * half).y); lineTo((a - nrm * half).x, (a - nrm * half).y); close()
            }
            if (night && t.spike <= 0) {
                // Headlight: a pool of light ahead of the train.
                val c0 = head + dir * 26f
                drawCircle(Brush.radialGradient(listOf(Color(0x99FFF3C4), Color.Transparent), c0, 30f), 30f, c0)
            }
            drawPath(rect(7f, 25f, 10f), ring)
            drawPath(rect(4f, 22f, 7f), c)
            trainTag(tm, t.id, t.v, c, head + Offset(8f, -6f), tags)
        }

        // Remote E-Stop location
        val inp = plc.inputs
        val where = listOfNotNull(
            "LOAD STAND".takeIf { inp.loadStandEstop },
            "UNLOAD STAND".takeIf { inp.unloadStandEstop },
            "CONSOLE".takeIf { inp.estopPressed },
        )
        if (where.isNotEmpty() && blink) {
            drawText(tm, "⚠ E-STOP: " + where.joinToString(" + "), pt(st.stopPos) + Offset(-60f, -44f),
                TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, background = Color(0xFFD50000)))
        }

        overlay?.invoke(this, xf)

        legendChip(tm, "PLAN VIEW", Offset(8f, 6f))
    }
}
