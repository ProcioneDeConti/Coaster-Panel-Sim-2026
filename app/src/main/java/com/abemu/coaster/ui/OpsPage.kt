package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.AccessLevel
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.Demand
import com.abemu.coaster.sim.WeatherPreset
import kotlin.math.cos
import kotlin.math.sin

private fun compass(deg: Double): String =
    listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")[((deg / 22.5) + 0.5).toInt() % 16]

/** PARK OPS: the ride's line and the weather instruments that drive the weather hold. */
@Composable
fun OpsPage(vm: SimViewModel) {
    vm.frame.longValue
    val instructor = vm.access >= AccessLevel.INSTRUCTOR
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QueueFrame(vm, instructor)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WeatherFrame(vm, instructor)
        }
    }
}

@Composable
private fun QueueFrame(vm: SimViewModel, instructor: Boolean) {
    val q = vm.queue
    val plc = vm.plc
    HmiFrame("GUEST QUEUE – ${plc.profile.name.uppercase()}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HmiLabel("POSTED WAIT", 10.sp, color = Hmi.Dim)
                Text(if (q.closedReason.isNotEmpty()) "CLOSED" else "${q.postedWaitMin} min", fontSize = 30.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = if (q.closedReason.isNotEmpty()) Hmi.Bad else Hmi.Text)
                if (q.closedReason == "WEATHER") HmiLabel("Closed for weather", 10.sp, bold = true, color = Hmi.Bad)
            }
            Column(Modifier.weight(1f)) {
                HmiLabel("GUESTS IN LINE", 10.sp, color = Hmi.Dim)
                Text("${q.inLine}", fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Hmi.Text)
            }
        }
        // Line fill: one bar per 100 guests of a ~2,000 guest switchback queue.
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            drawRect(Color(0xFF9AA1A8))
            val frac = (q.inLine / 2000f).coerceIn(0f, 1f)
            drawRect(if (frac > 0.8f) Hmi.Bad else if (frac > 0.5f) Hmi.Warn else Hmi.Ok, size = Size(size.width * frac, size.height))
            for (k in 1 until 20) { val x = size.width * k / 20; drawLine(Color(0x55000000), Offset(x, 0f), Offset(x, size.height), 1f) }
        }
        ValueBox("Actual wait for a guest joining now", "${q.estimateWaitMin(plc)} min")
        ValueBox("Riders / hour (last 15 min)", "${q.ridersPerHour}")
        val seats = plc.profile.ridersPerDispatch
        ValueBox("Rated capacity", "${plc.profile.spec.ratedCapacity} /h  (${seats} seats every ${mss(plc.profile.ratedInterval)})")
        if (plc.targetScale != 1.0)
            ValueBox("Capacity at instructor target", "${(3600 / plc.dispatchTarget * seats).toInt()} /h  (@ ${mss(plc.dispatchTarget)})")
        ValueBox("Last train", if (q.lastLoad.second == 0) "—" else "${q.lastLoad.first} / ${q.lastLoad.second} seats")
        ValueBox("Riders today", "${q.riders}")
        ValueBox("Trains held on the brakes now / stack-ups", "${plc.stackedTrains} / ${plc.stackEvents}")
        ValueBox("Balked at the entrance (wait too long)", "${q.balked}")
        ValueBox("Walked off the line (ride down)", "${q.walkedOff}")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiLabel("Queue entrance", 11.sp, modifier = Modifier.width(110.dp))
            HmiButton("OPEN", active = q.open, activeColor = Hmi.Ok) { q.open = true }
            HmiButton("CLOSED", active = !q.open, activeColor = Hmi.Bad) { q.open = false }
        }
    }
    HmiFrame("PARK ATTENDANCE" + if (!instructor) "  (instructor)" else "") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Demand.entries.forEach { d ->
                HmiButton("${d.label}\n${d.perHour}/h", Modifier.weight(1f), enabled = instructor, active = q.demand == d,
                    activeColor = Hmi.Info, fontSize = 10.sp, maxLines = 2) { q.demand = d }
            }
        }
        HmiLabel("Guests heading for the ride each hour. The longer the posted wait, the more of them skip it. " +
            "With the ride down for more than 3 minutes, guests start leaving the line; in a weather hold the entrance closes.",
            10.sp, color = Hmi.Dim)
    }
}

@Composable
private fun WeatherFrame(vm: SimViewModel, instructor: Boolean) {
    val wx = vm.plc.weather
    val w = vm.weather
    HmiFrame("WEATHER STATION") {
        // Status banner
        val held = wx.hold
        Row(verticalAlignment = Alignment.CenterVertically) {
            Indicator(held, if (flasher()) Hmi.Bad else Color(0xFF7A1010), Hmi.Ok, 18)
            HmiLabel(if (held) "  WEATHER HOLD – ${wx.reason.uppercase()}" else "  NO WEATHER HOLD", 14.sp, bold = true,
                color = if (held) Hmi.Bad else Hmi.Ok)
        }
        if (wx.lightningHold) ValueBox("Lightning all-clear in", clock(wx.lightningClearIn.coerceAtLeast(0.0)) + "  (resets on each strike ≤ ${wx.lightningMi.toInt()} mi)")
        if (wx.windHold) ValueBox("Wind all-clear in",
            if (vm.weather.windMph < wx.windLimitMph - 5) clock(wx.windClearIn.coerceAtLeast(0.0)) else "waiting for wind < ${(wx.windLimitMph - 5).toInt()} mph")

        // Anemometer
        HmiLabel("ANEMOMETER (top of structure)", 10.sp, bold = true, color = Hmi.Dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%2.0f mph".format(w.windMph), fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = if (w.windMph >= wx.windLimitMph) Hmi.Bad else if (w.windMph >= wx.windLimitMph - 8) Hmi.Warn else Hmi.Text)
            HmiLabel("   limit ${wx.windLimitMph.toInt()} mph for this ride", 10.sp, color = Hmi.Dim)
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val max = 60f
            drawRect(Color(0xFF9AA1A8))
            val frac = (w.windMph.toFloat() / max).coerceIn(0f, 1f)
            drawRect(if (w.windMph >= wx.windLimitMph) Hmi.Bad else Hmi.Info, size = Size(size.width * frac, size.height))
            val lx = size.width * wx.windLimitMph.toFloat() / max
            drawLine(Color.Black, Offset(lx, 0f), Offset(lx, size.height), 3f)
        }

        // Gust trace: peak wind each second over the last 10 minutes, with the hold limit and the all-clear level.
        HmiLabel("GUST TRACE – last ${com.abemu.coaster.sim.Weather.HISTORY_SEC / 60} min", 10.sp, bold = true, color = Hmi.Dim)
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val top = maxOf(50f, (w.history.maxOrNull() ?: 0f) + 5f)
            fun y(mph: Float) = size.height * (1 - mph / top)
            drawRect(Color(0xFF1B2229))
            for (g in 10 until top.toInt() step 10) drawLine(Color(0xFF2E3A44), Offset(0f, y(g.toFloat())), Offset(size.width, y(g.toFloat())), 1f)
            val lim = wx.windLimitMph.toFloat()
            drawLine(Color(0xFFD42020), Offset(0f, y(lim)), Offset(size.width, y(lim)), 2f)
            drawLine(Color(0xFF2EBD59), Offset(0f, y(lim - 5)), Offset(size.width, y(lim - 5)), 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val n = com.abemu.coaster.sim.Weather.HISTORY_SEC
            val h = w.history
            if (h.size >= 2) {
                val path = androidx.compose.ui.graphics.Path()
                h.forEachIndexed { i, v ->
                    val x = size.width * (n - h.size + i) / (n - 1).toFloat()
                    if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                }
                drawPath(path, Color(0xFF64B5F6), style = Stroke(2f))
            }
        }
        HmiLabel("— hold limit ${wx.windLimitMph.toInt()} mph   - - all-clear below ${(wx.windLimitMph - 5).toInt()} mph", 9.sp, color = Hmi.Dim)

        val tm = rememberTextMeasurer(cacheSize = 8)
        // Wind vane and what the wind does to a launch.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(96.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2 - 10
                drawCircle(Brush.radialGradient(listOf(Color(0xFF26313B), Color(0xFF141A20)), c, r + 8), r + 8, c)
                drawCircle(Color(0xFF55606A), r, c, style = Stroke(1.5f))
                // Bezel: a tick every 10°, longer every 30°, cardinal letters inside.
                for (k in 0 until 36) {
                    val a = Math.toRadians(k * 10.0)
                    val u = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val len = if (k % 3 == 0) 6.dp.toPx() else 3.dp.toPx()
                    drawLine(Color(0xFF7D8892), c + u * r, c + u * (r - len), if (k % 9 == 0) 2f else 1f)
                }
                listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (t, deg) ->
                    val a = Math.toRadians(deg)
                    val l = tm.measure(t, TextStyle(color = if (t == "N") Color(0xFFEF5350) else Color(0xFFB0BEC5), fontSize = 8.sp, fontWeight = FontWeight.Bold))
                    val at = c + Offset(sin(a).toFloat(), -cos(a).toFloat()) * (r - 13.dp.toPx())
                    drawText(l, topLeft = at - Offset(l.size.width / 2f, l.size.height / 2f))
                }
                val th = Math.toRadians(w.dirDeg)
                // Arrow points downwind, from the side the wind comes from.
                val u = Offset(sin(th).toFloat(), -cos(th).toFloat())
                val from = c + u * r
                val to = c - u * (r * 0.62f)
                val n = Offset(-u.y, u.x)
                drawLine(Color(0xFF64B5F6), from, to + u * 8.dp.toPx(), 3f, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(to.x, to.y)
                    val b = to + u * 9.dp.toPx()
                    lineTo((b + n * 4.dp.toPx()).x, (b + n * 4.dp.toPx()).y)
                    lineTo((b - n * 4.dp.toPx()).x, (b - n * 4.dp.toPx()).y)
                    close()
                }, Color(0xFF64B5F6))
                // Tail feathers on the windward end.
                for (f in 0..1) {
                    val q = from - u * (f * 5.dp.toPx())
                    drawLine(Color(0xFF64B5F6), q, q + (n - u) * 4.dp.toPx(), 2f)
                }
                drawCircle(Color(0xFFCFD8DC), 2.5f, c)
                val plc = vm.plc
                if (plc.profile.type == com.abemu.coaster.sim.ProfileType.LAUNCH) {
                    val a = plc.profile.planPoint(plc.profile.launchStart)
                    val b = plc.profile.planPoint(plc.profile.launchEnd)
                    val d = Offset((b.first - a.first).toFloat(), (b.second - a.second).toFloat())
                    val len = d.getDistance()
                    if (len > 0) drawLine(Color(0xFFFFB300), c, c + d / len * r * 0.9f, 3f)
                }
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                ValueBox("Wind from", "%03.0f° %s".format(w.dirDeg, compass(w.dirDeg)))
                ValueBox("2-min mean / peak gust", "%.0f / %.0f mph".format(w.mean2Min, w.peak2Min))
                val plc = vm.plc
                if (plc.profile.type == com.abemu.coaster.sim.ProfileType.LAUNCH && !plc.profile.spec.lsmLift) {
                    val hw = plc.plant.headwind(plc.profile.launchEnd) / 0.44704
                    ValueBox("Launch " + if (hw >= 0) "headwind" else "tailwind", "%.0f mph".format(kotlin.math.abs(hw)))
                    HmiLabel("▬ amber: launch direction. A strong headwind on a weak launch can roll the train back.", 9.sp, color = Hmi.Dim)
                }
            }
        }

        // Lightning detector: rings at the hold radius and 20 mi; strikes plotted by distance.
        HmiLabel("LIGHTNING DETECTOR", 10.sp, bold = true, color = Hmi.Dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(150.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r20 = size.minDimension / 2 - 4
                drawCircle(Brush.radialGradient(listOf(Color(0xFF14261C), Color(0xFF0D1418)), c, r20), r20, c)
                // Faint 5-mile range rings and a crosshair
                for (mi in listOf(5, 10, 15)) drawCircle(Color(0x332EBD59), r20 * mi / 20f, c, style = Stroke(1f))
                drawLine(Color(0x262EBD59), c - Offset(r20, 0f), c + Offset(r20, 0f), 1f)
                drawLine(Color(0x262EBD59), c - Offset(0f, r20), c + Offset(0f, r20), 1f)
                val r8 = r20 * wx.lightningMi.toFloat() / 20f
                drawCircle(if (wx.lightningHold) Color(0x55D42020) else Color(0x1F2EBD59), r8, c)
                drawCircle(Color(0xFFD42020), r8, c, style = Stroke(2f))
                // Slow radar sweep: a fading wedge trailing the beam.
                val sweep = (System.currentTimeMillis() % 4000L) / 4000f * 360f
                rotate(sweep, c) {
                    drawArc(Brush.sweepGradient(0.85f to Color.Transparent, 1f to Color(0x592EBD59), center = c),
                        0f, 360f, true, topLeft = c - Offset(r20, r20), size = Size(2 * r20, 2 * r20))
                    drawLine(Color(0xAA4CE07A), c, c + Offset(r20, 0f), 1.5f)
                }
                drawCircle(Color(0xFF55606A), r20, c, style = Stroke(1.5f))
                drawCircle(Color.White, 3f, c)
                w.strikeMi?.let { mi ->
                    val a = (mi * 1.7) % (2 * Math.PI)
                    val rr = r20 * (mi.toFloat() / 20f).coerceAtMost(1f)
                    val p = c + Offset((rr * cos(a)).toFloat(), (rr * sin(a)).toFloat())
                    drawCircle(Brush.radialGradient(listOf(Color(0xAAFFEB3B), Color.Transparent), p, 16f), 16f, p)
                    drawCircle(Color(0xFFFFEB3B), 5f, p)
                }
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                ValueBox("Last strike", w.strikeMi?.let { "%.1f mi".format(it) } ?: "none (1 min)")
                ValueBox("Hold radius", "${wx.lightningMi.toInt()} mi")
                ValueBox("Conditions", w.current.label)
                HmiLabel("Inner ring: ${wx.lightningMi.toInt()} mi hold radius · outer ring: 20 mi", 9.sp, color = Hmi.Dim)
            }
        }
    }
    HmiFrame("PARK LIGHTING") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.abemu.coaster.Lighting.entries.forEach { l ->
                HmiButton(l.label, Modifier.weight(1f), active = vm.lighting == l, activeColor = Hmi.Info, fontSize = 10.sp) { vm.lighting = l }
            }
        }
        HmiLabel("Night: the console and HMI dim, lit lamps glow, and the plan view shows the track lighting.",
            10.sp, color = Hmi.Dim)
    }
    HmiFrame("WEATHER (INSTRUCTOR)" + if (!instructor) "  – log in to change" else "") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WeatherPreset.entries.forEach { p ->
                HmiButton(p.label, Modifier.weight(1f), enabled = instructor, active = w.preset == p, activeColor = Hmi.Info,
                    fontSize = 10.sp, maxLines = 2) { w.preset = p }
            }
        }
        HmiButton("RESET WEATHER + CLEAR HOLDS", Modifier.fillMaxWidth(), enabled = instructor, activeColor = Hmi.Info,
            fontSize = 10.sp) { w.reset(); wx.reset() }
        HmiLabel("STORM CELL brings a cell in from 25 mi at ~25 mph. Lightning within ${wx.lightningMi.toInt()} mi holds dispatch; " +
            "the hold clears ${(wx.lightningClearSec / 60).toInt()} min after the last close strike. Wind at or above the ride's limit " +
            "holds dispatch until it stays ${(wx.windClearSec / 60).toInt()} min below (limit − 5). Trains already on the course " +
            "finish their circuit; use sim speed to fast-forward.", 10.sp, color = Hmi.Dim)
    }
}

