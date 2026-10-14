package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.Color
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
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import kotlin.math.abs

private val trainColors = listOf(Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFEF6C00))

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
            HmiFrame("BLOCK STATUS", Modifier.weight(1.6f)) {
                Row {
                    listOf("BLOCK" to 1.6f, "STATUS" to 1.1f, "TRAIN" to 0.6f, "BRAKE" to 0.9f, "SPEED" to 0.8f).forEach { (h, w) ->
                        HmiLabel(h, 10.sp, bold = true, color = Hmi.Dim, modifier = Modifier.weight(w))
                    }
                }
                plc.profile.blocks.forEach { b ->
                    val id = plc.occ[b.index]
                    val brk = plc.plant.brakes[b.index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HmiLabel("${b.tag} ${b.name}", 11.sp, modifier = Modifier.weight(1.6f))
                        Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
                            Indicator(id != -1, Hmi.Bad, Hmi.Ok)
                            HmiLabel(if (id != -1) " OCCUPIED" else " CLEAR", 11.sp)
                        }
                        HmiLabel(if (id != -1) "T$id" else "—", 11.sp, bold = true, modifier = Modifier.weight(0.6f))
                        HmiLabel(
                            when {
                                b.kind == BlockKind.LIFT -> if (plc.plant.chainSpeed > 0.1) "CHAIN RUN" else "CHAIN STOP"
                                brk.openProx -> "OPEN"
                                brk.closedProx -> "CLOSED"
                                else -> "MOVING"
                            },
                            11.sp, color = if (brk.isOpen) Hmi.Ok else Hmi.Text, modifier = Modifier.weight(0.9f),
                        )
                        HmiLabel(if (id != -1) "%.1f m/s".format(abs(plc.trainSpeed(id))) else "", 11.sp, modifier = Modifier.weight(0.8f))
                    }
                }
            }
            HmiFrame("RIDE DATA", Modifier.weight(1f)) {
                if (plc.profile.type == ProfileType.LIFT) {
                    ValueBox("Lift chain", "%.2f m/s".format(plc.plant.chainSpeed))
                } else {
                    ValueBox("LSM state", plc.launchState.label)
                    ValueBox("Last exit spd", "%.1f m/s".format(plc.plant.lastLaunchExitSpeed))
                }
                ValueBox("Main air", "${plc.plant.airPsi.toInt()} psi")
                ValueBox("Trains (circuit/stor)", "${plc.plant.trains.size} / ${plc.plant.stored}")
                ValueBox("Dispatches", plc.dispatchCount.toString())
                ValueBox("PLC scan", "20 ms  #${plc.scanCount % 100000}")
            }
        }
    }
}

/** Side-elevation track diagram with live block occupancy, brakes, sensors and trains. */
@Composable
fun TrackDiagram(plc: RidePlc, modifier: Modifier = Modifier, ghost: RideProfile? = null) {
    val tm = rememberTextMeasurer()
    val profile = plc.profile
    val plant = plc.plant
    val blink = flasher()
    Canvas(modifier.background(Color(0xFF0F1A24))) {
        val padL = 16f
        val padR = 16f
        val top = 34f
        val bottom = size.height - 46f
        val w = size.width - padL - padR
        val maxH = profile.maxHeight + 6
        fun x(s: Double) = padL + (s / profile.length * w).toFloat()
        fun y(s: Double) = (bottom - profile.height(s) / maxH * (bottom - top)).toFloat()
        fun pt(s: Double) = Offset(x(s), y(s))

        // Grid
        for (h in 0..maxH.toInt() step 10) {
            val yy = (bottom - h / maxH * (bottom - top)).toFloat()
            drawLine(Color(0xFF1E2E3D), Offset(padL, yy), Offset(size.width - padR, yy))
            drawText(tm, "${h}m", Offset(2f, yy - 14f), TextStyle(color = Color(0xFF4F6578), fontSize = 9.sp))
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
        val path = Path()
        var s = 0.0
        path.moveTo(x(0.0), y(0.0))
        while (s <= profile.length) { path.lineTo(x(s), y(s)); s += 2.0 }
        // Generated track before tags reshaped it (editor preview)
        if (ghost != null) {
            val gp = Path()
            var g = 0.0
            gp.moveTo(x(0.0), (bottom - ghost.height(0.0) / maxH * (bottom - top)).toFloat())
            while (g <= profile.length) { gp.lineTo(x(g), (bottom - ghost.height(g) / maxH * (bottom - top)).toFloat()); g += 2.0 }
            drawPath(gp, Color(0xFF607D8B), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
        }
        drawPath(path, Color(0xFF90A4AE), style = Stroke(4f, cap = StrokeCap.Round))

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
        plant.trains.forEach { t ->
            val c = trainColors[(t.id - 1) % trainColors.size]
            val tp = Path()
            var q = t.s - profile.trainLength
            tp.moveTo(x(profile.wrap(q)), y(q))
            var lastX = x(profile.wrap(q))
            while (q <= t.s) {
                val xx = x(profile.wrap(q))
                if (abs(xx - lastX) > w / 2) tp.moveTo(xx, y(q)) else tp.lineTo(xx, y(q))
                lastX = xx
                q += 1.0
            }
            drawPath(tp, c, style = Stroke(11f, cap = StrokeCap.Round))
            val head = pt(t.s)
            drawText(
                tm, "T${t.id} ${"%.1f".format(abs(t.v))}", head + Offset(-18f, -32f),
                TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, background = c),
            )
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

        // Legend
        drawText(tm, "■ occupied  ■ clear   ━ brake closed / open   ● entry prox",
            Offset(padL, 6f), TextStyle(color = Color(0xFF78909C), fontSize = 10.sp))
    }
}


/** Maps the 100 x 56 plan box onto a canvas of the given size. */
class PlanTransform(val profile: com.abemu.coaster.sim.RideProfile, width: Float, height: Float) {
    private val pad = 28f
    private val scale = minOf((width - pad * 2) / 100f, (height - pad * 2 - 14f) / 56f)
    private val ox = (width - 100f * scale) / 2
    private val oy = pad + 14f + (height - pad * 2 - 14f - 56f * scale) / 2

    fun pt(s: Double): Offset {
        val (x, y) = profile.planPoint(s)
        return Offset(ox + x.toFloat() * scale, oy + y.toFloat() * scale)
    }

    /** Track path between two positions (forward, handling wrap). */
    fun seg(from: Double, to: Double): Path {
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
    val tm = rememberTextMeasurer()
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
    Canvas(modifier.background(Color(0xFF0F1A24)).then(tapMod)) {
        val xf = PlanTransform(profile, size.width, size.height)
        fun pt(s: Double) = xf.pt(s)
        fun seg(from: Double, to: Double) = xf.seg(from, to)

        // Station platform and transfer spur
        val st = profile.blocks[0]
        val a = pt(st.stopPos!! - profile.trainLength - 6)
        val b = pt(st.stopPos + 4)
        drawRect(Color(0xFF37474F), Offset(minOf(a.x, b.x), a.y + 6f), Size(kotlin.math.abs(b.x - a.x), 14f))
        drawText(tm, "STATION", Offset(minOf(a.x, b.x), a.y + 22f), TextStyle(color = Color(0xFF90A4AE), fontSize = 9.sp))
        val fb = profile.blocks[profile.transferBlock]
        val f1 = pt(fb.brakeStart!!)
        val f2 = pt(fb.stopPos!!)
        drawLine(Color(0xFF455A64), f1 + Offset(0f, -16f), f2 + Offset(0f, -16f), 5f)
        repeat(plant.stored) { k ->
            val x = f1.x + (f2.x - f1.x) * (0.25f + 0.4f * k)
            drawLine(Color(0xFF00897B), Offset(x - 14f, f1.y - 16f), Offset(x + 14f, f1.y - 16f), 9f, StrokeCap.Round)
        }
        drawText(tm, "TRANSFER / STORAGE", Offset(minOf(f1.x, f2.x), f1.y - 34f), TextStyle(color = Color(0xFF607D8B), fontSize = 9.sp))

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
        plant.trains.forEach { t ->
            val c = trainColors[(t.id - 1) % trainColors.size]
            drawPath(seg(t.s - profile.trainLength, t.s), c, style = Stroke(13f, cap = StrokeCap.Round))
            drawCircle(Color.White, 4f, pt(t.s))
            drawText(tm, "T${t.id} ${"%.1f".format(kotlin.math.abs(t.v))}", pt(t.s) + Offset(8f, -22f),
                TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, background = c))
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

        drawText(tm, "PLAN VIEW   ━ occupied / clear   ┅ brake closed / open   ● entry prox",
            Offset(8f, 6f), TextStyle(color = Color(0xFF78909C), fontSize = 10.sp))
    }
}
