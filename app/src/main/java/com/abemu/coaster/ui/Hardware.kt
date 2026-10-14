package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Lens {
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF2B2B)
    val Amber = Color(0xFFFFA51F)
    val Blue = Color(0xFF2F7BFF)
    val White = Color(0xFFF5F2E8)
    val Yellow = Color(0xFFFFE02E)
    val Black = Color(0xFF2A2A2A)
}

/** Shared 2.5 Hz flasher, like a PLC flasher bit. */
fun flasher(): Boolean = (System.currentTimeMillis() / 400) % 2 == 0L

/** Engraved black legend plate under a device. */
@Composable
fun LegendPlate(text: String, width: Dp = 92.dp) {
    Box(
        Modifier
            .width(width)
            .background(Color(0xFF111111), RoundedCornerShape(3.dp))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Color(0xFFE8E8E8), fontSize = 9.sp, lineHeight = 10.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontFamily = FontFamily.SansSerif,
        )
    }
}

private fun DrawScope.bezel(r: Float) {
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFE9E9E9), Color(0xFF8A8A8A), Color(0xFF4A4A4A)),
            start = Offset(center.x - r, center.y - r), end = Offset(center.x + r, center.y + r),
        ), r,
    )
    drawCircle(Color(0xFF1C1C1C), r * 0.86f)
}

/**
 * 22 mm style illuminated (or plain) momentary pushbutton. Reports press and release so
 * the PLC sees a real held input (needed for two-hand dispatch and hold-to-run).
 */
@Composable
fun PushButton(
    legend: String,
    color: Color,
    lit: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    illuminated: Boolean = true,
    plateWidth: Dp = 84.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true; Haptics.click(); cb(true)
                        waitForUpOrCancellation()
                        pressed = false; cb(false)
                    }
                },
        ) {
            val r = this.size.minDimension / 2
            bezel(r)
            val capR = r * (if (pressed) 0.70f else 0.76f)
            val on = lit && illuminated
            if (on) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center, r * 1.0f),
                    r * 1.0f,
                )
            }
            val base = if (!illuminated) color else if (on) color else lerp(color, Color.Black, 0.62f)
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(base, Color.White, if (on) 0.55f else 0.18f), base, lerp(base, Color.Black, 0.35f)),
                    center = Offset(center.x - capR * 0.3f, center.y - capR * 0.35f), radius = capR * 1.5f,
                ),
                capR,
            )
            drawCircle(Color.Black.copy(alpha = 0.5f), capR, style = Stroke(2f))
        }
        Spacer(Modifier.height(4.dp))
        LegendPlate(legend, plateWidth)
    }
}

@Composable
fun PilotLight(legend: String, color: Color, lit: Boolean, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            bezel(r)
            if (lit) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), center, r * 1.1f), r * 1.1f)
            val c = if (lit) color else lerp(color, Color.Black, 0.7f)
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(c, Color.White, if (lit) 0.7f else 0.15f), c),
                    center = Offset(center.x - r * 0.2f, center.y - r * 0.25f), radius = r * 0.9f,
                ),
                r * 0.72f,
            )
        }
        Spacer(Modifier.height(3.dp))
        LegendPlate(legend, 66.dp)
    }
}

/** Mushroom-head E-Stop: tap to push (latches), press and hold to twist-release. */
@Composable
fun EStopButton(
    pressed: Boolean,
    onSet: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    label: String = "EMERGENCY\nSTOP",
    plateWidth: Dp = 110.dp,
) {
    val set by rememberUpdatedState(onSet)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(pressed) {
                    detectTapGestures(
                        onPress = { if (!pressed) set(true) },
                        onLongPress = { if (pressed) set(false) },
                    )
                },
        ) {
            val r = this.size.minDimension / 2
            // Yellow legend ring
            drawCircle(Color(0xFFFFD200), r)
            drawCircle(Color(0xFF8A7000), r, style = Stroke(2f))
            drawCircle(Color(0xFF3A3A3A), r * 0.62f)
            val head = if (pressed) r * 0.52f else r * 0.6f
            drawCircle(Color.Black.copy(alpha = 0.4f), head * 1.04f, center + Offset(3f, 5f))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFF7A7A), Color(0xFFD01010), Color(0xFF6E0000)),
                    center = Offset(center.x - head * 0.35f, center.y - head * 0.4f), radius = head * 1.6f,
                ),
                head,
            )
            if (!pressed) {
                // twist arrows
                rotate(-30f) {
                    drawArc(Color.White.copy(alpha = 0.7f), 200f, 60f, false, topLeft = center - Offset(head * 0.6f, head * 0.6f),
                        size = Size(head * 1.2f, head * 1.2f), style = Stroke(3f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LegendPlate(if (pressed) "E-STOP\nHOLD TO RELEASE" else label, plateWidth)
    }
}

/** Maintained selector keyswitch. Tap left half to turn counter-clockwise, right half clockwise. */
@Composable
fun KeySwitch(
    legend: String,
    positions: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 116.dp,
) {
    val tm = rememberTextMeasurer()
    val pick by rememberUpdatedState(onSelect)
    val span = 150f
    fun angleOf(i: Int) = -span / 2 + span * i / (positions.size - 1).coerceAtLeast(1)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(selected) {
                    detectTapGestures { p ->
                        Haptics.click()
                        if (p.x < this.size.width / 2) pick((selected - 1).coerceAtLeast(0))
                        else pick((selected + 1).coerceAtMost(positions.size - 1))
                    }
                },
        ) {
            val r = this.size.minDimension / 2
            val c = Offset(center.x, center.y + r * 0.22f)
            val face = r * 0.36f
            // Position labels on an arc above the dial, each centred on its own angle.
            positions.forEachIndexed { i, label ->
                val a = Math.toRadians(angleOf(i).toDouble() - 90)
                val rr = face * 2.05f
                val style = TextStyle(
                    color = if (i == selected) Lens.Yellow else Color(0xFFC8C8C8),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                )
                val m = tm.measure(label, style)
                val p = Offset((c.x + rr * cos(a)).toFloat(), (c.y + rr * sin(a)).toFloat())
                drawText(m, topLeft = p - Offset(m.size.width / 2f, m.size.height / 2f))
            }
            for (i in positions.indices) {
                rotate(angleOf(i), c) {
                    drawLine(
                        if (i == selected) Lens.Yellow else Color(0xFF9A9A9A),
                        Offset(c.x, c.y - face * 1.18f), Offset(c.x, c.y - face * 1.42f), strokeWidth = 4f,
                    )
                }
            }
            drawCircle(Brush.linearGradient(listOf(Color(0xFFDDDDDD), Color(0xFF555555))), face * 1.1f, c)
            drawCircle(Color(0xFF202020), face, c)
            rotate(angleOf(selected), c) {
                drawRoundRect(
                    Brush.linearGradient(listOf(Color(0xFFF4E39A), Color(0xFFB59A3A))),
                    topLeft = Offset(c.x - face * 0.16f, c.y - face * 0.85f),
                    size = Size(face * 0.32f, face * 1.7f),
                    cornerRadius = CornerRadius(6f),
                )
                drawCircle(Color(0xFFC9B060), face * 0.35f, Offset(c.x, c.y - face * 0.85f))
            }
        }
        LegendPlate(legend, 104.dp)
    }
}

@Composable
fun PanelSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .border(1.dp, Color(0xFF5A5F66), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Color(0xFFB8C0CA), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        content()
    }
}
