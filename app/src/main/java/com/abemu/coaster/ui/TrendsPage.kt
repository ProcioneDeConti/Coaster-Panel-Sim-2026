package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.Units
import com.abemu.coaster.sim.TrendChannel

private data class Series(val ch: TrendChannel, val color: Color)

private val trainTrendColors = listOf(
    Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFC62828), Color(0xFF6D4C41), Color(0xFF558B2F),
)

@Composable
fun TrendsPage(vm: SimViewModel) {
    vm.frame.longValue
    val t = vm.trends
    var minutes by remember { mutableIntStateOf(5) }
    // Only the trains this ride has (circuit + storage); ids run 1..n.
    val fleet = (vm.plc.plant.trains.maxOfOrNull { it.id } ?: 0).coerceAtLeast(vm.plc.plant.trains.size + vm.plc.plant.stored)
    val speedSeries = t.trainSpeed.take(fleet.coerceIn(1, t.trainSpeed.size)).mapIndexed { i, c -> Series(c, trainTrendColors[i]) } +
        Series(t.drive, Color(0xFFE65100))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiLabel("Time span", 11.sp)
            listOf(1, 5, 10).forEach { m -> HmiButton("$m min", active = minutes == m, activeColor = Hmi.Info) { minutes = m } }
            Box(Modifier.weight(1f))
            HmiButton("CLEAR") { t.clear() }
        }
        HmiFrame("SPEEDS (mph)", Modifier.weight(1f)) {
            // Channels log mph; the profile is in m/s. Round the top up to a clean 20 mph step.
            val topMs = vm.plc.profile.let { maxOf(it.launchSpeed, it.blocks.maxOf { b -> b.exitLaunch }, Math.sqrt(2 * 9.81 * it.maxHeight)) }
            val topMph = (Math.ceil(Units.mph(topMs) * 1.05 / 20.0) * 20.0).toFloat().coerceAtLeast(20f)
            TrendChart(speedSeries, minutes, t.period, 0f, topMph, Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("MAIN AIR (psi)", Modifier.weight(1f)) {
                TrendChart(listOf(Series(t.air, Color(0xFF2E7D32))), minutes, t.period, 0f, 120f, Modifier.weight(1f))
            }
            HmiFrame("LIFT MOTOR CURRENT (A)", Modifier.weight(1f)) {
                TrendChart(listOf(Series(t.liftCurrent, Color(0xFFC62828))), minutes, t.period, 0f, 300f, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrendChart(series: List<Series>, minutes: Int, period: Double, yMin: Float, yMax: Float, modifier: Modifier) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(s.color))
                    HmiLabel(" ${s.ch.name}: %.1f %s".format(s.ch.last(), s.ch.unit), 10.sp)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color.White)) {
            val axis = TextStyle(fontSize = 10.sp, color = Color(0xFF6B7178), fontFamily = FontFamily.Monospace)
            val left = 34.dp.toPx()
            val top = 8.dp.toPx()
            val w = size.width - left - 8.dp.toPx()
            val h = size.height - top - 18.dp.toPx()
            val points = (minutes * 60 / period).toInt()
            // plot area + grid
            drawRect(Color(0xFFFAFBFC), Offset(left, top), Size(w, h))
            for (k in 0..4) {
                val y = top + h - h * k / 4
                drawLine(if (k == 0) Color(0xFF9AA1A8) else Color(0xFFE3E6E9), Offset(left, y), Offset(left + w, y), if (k == 0) 2f else 1f)
                val l = tm.measure("%.0f".format(yMin + (yMax - yMin) * k / 4), axis)
                drawText(l, topLeft = Offset(left - l.size.width - 4.dp.toPx(), y - l.size.height / 2f))
            }
            for (k in 0..minutes) {
                val x = left + w * k / minutes
                drawLine(Color(0xFFEDEFF1), Offset(x, top), Offset(x, top + h))
                val l = tm.measure(if (k == minutes) "now" else "-${minutes - k}m", axis)
                val lx = (x - l.size.width / 2f).coerceIn(left, left + w - l.size.width)
                drawText(l, topLeft = Offset(lx, top + h + 3.dp.toPx()))
            }
            series.forEach { s ->
                val ch = s.ch
                val start = maxOf(0, ch.size - points)
                val path = Path()
                for (i in start until ch.size) {
                    val x = left + w * (points - (ch.size - i)).toFloat() / points
                    val y = top + h - h * ((ch[i] - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
                    if (i == start) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            }
        }
    }
}
