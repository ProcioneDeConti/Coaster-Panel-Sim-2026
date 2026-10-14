package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import kotlin.math.abs

private val trainColors = listOf(Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFEF6C00))

@Composable
fun OverviewPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackDiagram(plc, Modifier.fillMaxWidth().weight(1f))
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
fun TrackDiagram(plc: RidePlc, modifier: Modifier = Modifier) {
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

        // Legend
        drawText(tm, "■ occupied  ■ clear   ━ brake closed / open   ● entry prox",
            Offset(padL, 6f), TextStyle(color = Color(0xFF78909C), fontSize = 10.sp))
    }
}

