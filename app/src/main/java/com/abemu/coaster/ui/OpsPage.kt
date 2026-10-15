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
import androidx.compose.ui.graphics.drawscope.Stroke
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
        val seats = plc.profile.ridersPerTrain
        ValueBox("Theoretical capacity at target", "${(3600 / plc.dispatchTarget * seats).toInt()} /h  (${seats} seats @ ${plc.dispatchTarget.toInt()} s)")
        ValueBox("Last train", if (q.lastLoad.second == 0) "—" else "${q.lastLoad.first} / ${q.lastLoad.second} seats")
        ValueBox("Riders today", "${q.riders}")
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

        // Lightning detector: rings at the hold radius and 20 mi; strikes plotted by distance.
        HmiLabel("LIGHTNING DETECTOR", 10.sp, bold = true, color = Hmi.Dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(150.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r20 = size.minDimension / 2 - 4
                drawCircle(Color(0xFF1B2229), r20, c)
                drawCircle(Color(0xFF55606A), r20, c, style = Stroke(1.5f))
                val r8 = r20 * wx.lightningMi.toFloat() / 20f
                drawCircle(if (wx.lightningHold) Color(0x55D42020) else Color(0x332EBD59), r8, c)
                drawCircle(Color(0xFFD42020), r8, c, style = Stroke(2f))
                drawCircle(Color.White, 3f, c)
                w.strikeMi?.let { mi ->
                    val a = (mi * 1.7) % (2 * Math.PI)
                    val rr = r20 * (mi.toFloat() / 20f).coerceAtMost(1f)
                    val p = c + Offset((rr * cos(a)).toFloat(), (rr * sin(a)).toFloat())
                    drawCircle(Color(0xFFFFEB3B), 6f, p)
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
    HmiFrame("WEATHER (INSTRUCTOR)" + if (!instructor) "  – log in to change" else "") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WeatherPreset.entries.forEach { p ->
                HmiButton(p.label, Modifier.weight(1f), enabled = instructor, active = w.preset == p, activeColor = Hmi.Info,
                    fontSize = 10.sp, maxLines = 2) { w.preset = p }
            }
        }
        HmiLabel("STORM CELL brings a cell in from 25 mi at ~25 mph. Lightning within ${wx.lightningMi.toInt()} mi holds dispatch; " +
            "the hold clears ${(wx.lightningClearSec / 60).toInt()} min after the last close strike. Wind at or above the ride's limit " +
            "holds dispatch until it stays ${(wx.windClearSec / 60).toInt()} min below (limit − 5). Trains already on the course " +
            "finish their circuit; use sim speed to fast-forward.", 10.sp, color = Hmi.Dim)
    }
}

