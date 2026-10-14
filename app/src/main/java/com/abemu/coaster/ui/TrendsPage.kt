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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.TrendChannel

private data class Series(val ch: TrendChannel, val color: Color)

@Composable
fun TrendsPage(vm: SimViewModel) {
    vm.frame.longValue
    val t = vm.trends
    var minutes by remember { mutableIntStateOf(5) }
    val speedSeries = t.trainSpeed.mapIndexed { i, c -> Series(c, listOf(Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00897B))[i]) } +
        Series(t.drive, Color(0xFFE65100))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiLabel("Time span", 11.sp)
            listOf(1, 5, 10).forEach { m -> HmiButton("$m min", active = minutes == m, activeColor = Hmi.Info) { minutes = m } }
            Box(Modifier.weight(1f))
            HmiButton("CLEAR") { t.clear() }
        }
        HmiFrame("SPEEDS (m/s)", Modifier.weight(1f)) {
            TrendChart(speedSeries, minutes, t.period, 0f, maxOf(10f, vm.plc.profile.let {
                maxOf(it.launchSpeed, it.blocks.maxOf { b -> b.exitLaunch }, Math.sqrt(2 * 9.81 * it.maxHeight)).toFloat() * 1.1f
            }), Modifier.weight(1f))
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
    val tm = rememberTextMeasurer()
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
            val left = 36f
            val w = size.width - left - 4f
            val h = size.height - 16f
            val points = (minutes * 60 / period).toInt()
            // grid
            for (k in 0..4) {
                val y = h - h * k / 4
                drawLine(Color(0xFFE0E0E0), Offset(left, y), Offset(size.width, y))
                drawText(tm, "%.0f".format(yMin + (yMax - yMin) * k / 4), Offset(2f, y - 8f), TextStyle(fontSize = 8.sp, color = Color.Gray))
            }
            for (k in 0..minutes) {
                val x = left + w * k / minutes
                drawLine(Color(0xFFF0F0F0), Offset(x, 0f), Offset(x, h))
                drawText(tm, "-${minutes - k}m", Offset(x - 10f, h + 2f), TextStyle(fontSize = 8.sp, color = Color.Gray))
            }
            series.forEach { s ->
                val ch = s.ch
                val start = maxOf(0, ch.size - points)
                val path = Path()
                for (i in start until ch.size) {
                    val x = left + w * (points - (ch.size - i)).toFloat() / points
                    val y = h - h * ((ch[i] - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
                    if (i == start) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(2f))
            }
        }
    }
}
