package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.PI
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
/** Night operation: the console stays under its overhead light and lit lenses glow harder; the HMI bezel goes dark
 *  while the backlit screen glows; the plan view shows track lighting. */
object Night {
    var on by androidx.compose.runtime.mutableStateOf(false)
}

fun flasher(): Boolean = (System.currentTimeMillis() / 400) % 2 == 0L

/** 0..1 lamp brightness: LEDs come up fast and decay a touch slower, so flashing lamps don't look digital. */
@Composable
fun lampFade(on: Boolean) = animateFloatAsState(if (on) 1f else 0f, tween(if (on) 60 else 150), label = "lamp")

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

private val chrome = listOf(Color(0xFFF4F5F6), Color(0xFFB4B8BC), Color(0xFF6C7176), Color(0xFF3E4246))

/** Contact shadow a panel device casts on the console plate. */
private fun DrawScope.deviceShadow(r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
        center + Offset(r * 0.04f, r * 0.12f), r * 1.08f), r * 1.08f, center + Offset(r * 0.04f, r * 0.12f))
}

/**
 * 800F-style round metal bezel: a polished chrome ring lit from the top left, with a bevel stepping down
 * into the operator opening. Returns the opening radius.
 */
private fun DrawScope.chromeBezel(r: Float): Float {
    val tl = Offset(center.x - r, center.y - r)
    val br = Offset(center.x + r, center.y + r)
    drawCircle(Brush.linearGradient(chrome, tl, br), r)
    // Outer edge and a bright arc where the light catches the top of the ring.
    drawCircle(Color.Black.copy(alpha = 0.55f), r, style = Stroke(r * 0.03f))
    drawArc(Color.White.copy(alpha = 0.7f), 200f, 70f, false, Offset(center.x - r * 0.9f, center.y - r * 0.9f),
        Size(r * 1.8f, r * 1.8f), style = Stroke(r * 0.04f))
    // Inner bevel runs the other way (shaded at the top, lit at the bottom), so the opening reads as a recess.
    drawCircle(Brush.linearGradient(chrome.reversed(), tl, br), r * 0.84f)
    val hole = r * 0.76f
    drawCircle(Color(0xFF0B0B0C), hole)
    return hole
}

/**
 * Extended guard (shroud) around a pushbutton, in black moulded plastic: a raised collar whose inner wall
 * shows above the cap, so the button can only be pressed deliberately. Returns the opening radius.
 */
private fun DrawScope.guardRing(r: Float, night: Boolean): Float {
    val tl = Offset(center.x - r, center.y - r)
    val br = Offset(center.x + r, center.y + r)
    val k = if (night) 0.5f else 1f
    drawCircle(Brush.linearGradient(listOf(Color(0xFF5A5E63), Color(0xFF26292C), Color(0xFF0C0D0E)).map { lerp(Color.Black, it, k) }, tl, br), r)
    drawCircle(Color.Black, r, style = Stroke(r * 0.03f))
    // Moulded top lip with a soft sheen.
    drawArc(Color.White.copy(alpha = 0.35f * k), 195f, 80f, false, Offset(center.x - r * 0.93f, center.y - r * 0.93f),
        Size(r * 1.86f, r * 1.86f), style = Stroke(r * 0.05f))
    // Inner wall: deep shadow under the near (top) lip, catching light on the far (bottom) side.
    val wall = r * 0.8f
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF020202), Color(0xFF151618), Color(0xFF3C4045).let { lerp(Color.Black, it, k) }),
        center.y - wall, center.y + wall), wall)
    return r * 0.66f
}

/**
 * The plastic operator cap: a short cylinder seen from slightly above. [travel] is 0 at rest, 1 fully pressed;
 * the side wall shrinks as the cap goes down. Illuminated caps are translucent and glow from the LED behind.
 */
private fun DrawScope.buttonCap(capR: Float, base: Color, light: Float, illuminated: Boolean, travel: Float, night: Boolean) {
    val depth = capR * (0.16f - 0.12f * travel)
    val face = center - Offset(0f, depth * 0.5f)
    // Shadow in the opening, then the cap's side wall.
    drawCircle(Color.Black.copy(alpha = 0.7f), capR * 1.02f, center + Offset(0f, depth * 0.6f))
    drawCircle(lerp(base, Color.Black, 0.55f), capR, face + Offset(0f, depth))
    // Face: lit from the top left, slightly dished (darker towards the middle, bright rim at the top).
    val top = lerp(base, Color.White, 0.28f + 0.37f * light)
    drawCircle(Brush.linearGradient(listOf(top, base, lerp(base, Color.Black, 0.3f)),
        face - Offset(capR, capR), face + Offset(capR, capR)), capR, face)
    if (illuminated) {
        // LED behind the diffuser: a hot centre fading to the cap colour.
        if (light > 0f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * light), base.copy(alpha = 0.0f)), face, capR * 0.8f), capR * 0.8f, face)
        // Unlit translucent cap: faint diffuser rings visible through it.
        if (light < 1f) listOf(0.35f, 0.62f).forEach { f ->
            drawCircle(Color.White.copy(alpha = (if (night) 0.03f else 0.07f) * (1f - light)), capR * f, face, style = Stroke(capR * 0.05f))
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)), face, capR), capR, face)
    drawArc(Color.White.copy(alpha = 0.38f + 0.22f * light), 200f, 75f, false, face - Offset(capR * 0.86f, capR * 0.86f),
        Size(capR * 1.72f, capR * 1.72f), style = Stroke(capR * 0.07f))
    drawCircle(Color.Black.copy(alpha = 0.45f), capR, face, style = Stroke(capR * 0.04f))
}

/**
 * 22 mm style illuminated (or plain) momentary pushbutton, after the Allen-Bradley 800F: chrome bezel and a
 * flush cap, or an extended black guard when [guarded]. Reports press and release so the PLC sees a real
 * held input (needed for two-hand dispatch and hold-to-run).
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
    guarded: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onChange)
    // Cap snaps down, then springs back up with a little overshoot. Both values are read only in the
    // draw lambda, so an animation frame redraws this device's layer without recomposing it.
    val travel by animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) tween(45) else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "travel")
    val lamp by lampFade((lit || Boot.lampTest) && illuminated)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .graphicsLayer() // own cached layer: redrawn only when this device changes
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
            val night = Night.on
            val light = lamp
            deviceShadow(r)
            if (light > 0f) {
                val g = if (night) r * 1.8f else r * 1.25f
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = (if (night) 0.75f else 0.45f) * light), Color.Transparent), center, g), g)
            }
            val hole = if (guarded) guardRing(r, night) else chromeBezel(r)
            if (night && !guarded) drawCircle(Color.Black.copy(alpha = 0.5f), r)
            val base = if (!illuminated) lerp(color, Color.Black, if (night) 0.6f else 0f)
                else lerp(lerp(color, Color.Black, if (night) 0.82f else 0.55f), color, light)
            buttonCap(hole * 0.9f, base, light, illuminated, travel.coerceAtLeast(-0.25f), night)
        }
        Spacer(Modifier.height(4.dp))
        LegendPlate(legend, plateWidth)
    }
}

/**
 * Industrial pilot light after the 800F: chrome bezel and a domed, frosted lens. Lit, the LED cluster
 * behind it gives a white-hot centre and a halo; unlit, the lens is a dark tinted dome you can see into.
 */
@Composable
fun PilotLight(legend: String, color: Color, lit: Boolean, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val lamp by lampFade(lit || Boot.lampTest)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.graphicsLayer().size(size)) {
            val r = this.size.minDimension / 2
            val night = Night.on
            val light = lamp
            deviceShadow(r)
            if (light > 0f) {
                val g = r * (if (night) 2.2f else 1.45f)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = (if (night) 0.85f else 0.55f) * light), Color.Transparent), center, g), g)
            }
            val hole = chromeBezel(r)
            if (night) drawCircle(Color.Black.copy(alpha = 0.5f), r)
            val lens = hole * 0.94f
            if (light < 1f) {
                val c = lerp(color, Color.Black, if (night) 0.85f else 0.62f)
                drawCircle(Brush.radialGradient(listOf(lerp(c, color, 0.25f), c, lerp(c, Color.Black, 0.6f)),
                    center + Offset(lens * 0.1f, lens * 0.15f), lens), lens)
                // LED cluster faintly visible through the unlit lens.
                for (k in 0 until 6) {
                    val a = Math.toRadians(k * 60.0)
                    drawCircle(lerp(c, color, 0.35f), lens * 0.09f,
                        center + Offset((lens * 0.38f * cos(a)).toFloat(), (lens * 0.38f * sin(a)).toFloat()))
                }
                drawCircle(lerp(c, color, 0.35f), lens * 0.09f)
            }
            if (light > 0f) {
                drawCircle(Brush.radialGradient(listOf(Color.White, lerp(color, Color.White, 0.35f), color, lerp(color, Color.Black, 0.35f)),
                    center, lens), lens, alpha = light)
            }
            // Fresnel rings moulded into the dome.
            listOf(0.42f, 0.7f).forEach { f -> drawCircle(Color.White.copy(alpha = 0.07f + 0.11f * light), lens * f, style = Stroke(lens * 0.04f)) }
            // Dome: darker rim, glossy highlight top left, small reflection bottom right.
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f - 0.25f * light)), center, lens), lens)
            drawOval(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)),
                center - Offset(lens * 0.6f, lens * 0.75f), center - Offset(lens * 0.05f, lens * 0.1f)),
                center - Offset(lens * 0.62f, lens * 0.72f), Size(lens * 0.75f, lens * 0.5f))
            drawArc(Color.White.copy(alpha = 0.25f), 20f, 60f, false, center - Offset(lens * 0.85f, lens * 0.85f),
                Size(lens * 1.7f, lens * 1.7f), style = Stroke(lens * 0.06f))
        }
        Spacer(Modifier.height(3.dp))
        LegendPlate(legend, 66.dp)
    }
}

/** Illuminated mushroom-head E-Stop: tap to push (latches, head flashes red), press and hold to twist-release. */
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
    // Mushroom head twist while resetting, in degrees clockwise; springs back to 0 when let go.
    var twist by remember { mutableStateOf(0f) }
    // Head drops when struck and pops back out with a bounce when twisted free.
    val sink by animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) tween(60) else spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "sink")
    val lamp by lampFade((pressed && flasher()) || Boot.lampTest)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .graphicsLayer() // own cached layer: redrawn only when this device changes
                .size(size)
                .pointerInput(pressed) {
                    // Push to latch. Once latched, twist the head clockwise (drag round it) to release,
                    // as on a real twist-to-reset mushroom head. Long press still releases as a fallback.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!pressed) { set(true); Haptics.click(); return@awaitEachGesture }
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        fun ang(p: Offset) = Math.toDegrees(kotlin.math.atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                        var last = ang(down.position)
                        val t0 = down.uptimeMillis
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val a = ang(ch.position)
                            var d = a - last
                            if (d > 180) d -= 360
                            if (d < -180) d += 360
                            last = a
                            twist = (twist + d).coerceIn(0f, TWIST_RESET)
                            if (kotlin.math.abs(d) > 0.5f) moved = true
                            ch.consume()
                            if (twist >= TWIST_RESET) { Haptics.click(); set(false); break }
                            if (!moved && ch.uptimeMillis - t0 > 800) { set(false); break }
                        }
                        twist = 0f
                    }
                },
        ) {
            val r = this.size.minDimension / 2
            // Yellow legend ring
            drawCircle(Color(0xFFFFD200), r)
            drawCircle(Color(0xFF8A7000), r, style = Stroke(2f))
            drawCircle(Color(0xFF3A3A3A), r * 0.62f)
            val head = r * (0.6f - 0.08f * sink)
            val light = lamp
            drawCircle(Color.Black.copy(alpha = 0.4f), head * 1.04f, center + Offset(3f, 5f) * (1f - 0.6f * sink))
            if (light > 0f) drawCircle(Brush.radialGradient(listOf(Color(0xFFFF2020).copy(alpha = 0.8f * light), Color.Transparent), center, r * 0.95f), r * 0.95f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFF7A7A), Color(0xFFD01010), Color(0xFF6E0000))
                        .zip(listOf(Color(0xFFFFD0D0), Color(0xFFFF3030), Color(0xFFB00000))) { a, b -> lerp(a, b, light) },
                    center = Offset(center.x - head * 0.35f, center.y - head * 0.4f), radius = head * 1.6f,
                ),
                head,
            )
            // Moulded twist arrows on the head (clockwise), turning with it while it is twisted.
            rotate(twist) {
                val ar = head * 0.62f
                for (start in listOf(-150f, 30f)) {
                    drawArc(Color.White.copy(alpha = 0.75f), start, 100f, false, topLeft = center - Offset(ar, ar),
                        size = Size(ar * 2, ar * 2), style = Stroke(head * 0.07f))
                    val endA = Math.toRadians((start + 100f).toDouble())
                    val tip = center + Offset((ar * cos(endA)).toFloat(), (ar * sin(endA)).toFloat())
                    val tang = endA + PI / 2
                    val back = Offset((head * 0.16f * cos(tang)).toFloat(), (head * 0.16f * sin(tang)).toFloat())
                    val side = Offset((head * 0.1f * cos(endA)).toFloat(), (head * 0.1f * sin(endA)).toFloat())
                    drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(tip.x + back.x * 0.6f, tip.y + back.y * 0.6f)
                        lineTo(tip.x - back.x + side.x, tip.y - back.y + side.y)
                        lineTo(tip.x - back.x - side.x, tip.y - back.y - side.y)
                        close()
                    }, Color.White.copy(alpha = 0.75f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LegendPlate(label, plateWidth)
    }
}

/** How far the E-stop head has to be twisted to release it, in degrees. */
private const val TWIST_RESET = 45f

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
    val tm = rememberTextMeasurer(cacheSize = 64)
    val pick by rememberUpdatedState(onSelect)
    val span = 180f
    fun angleOf(i: Int) = -span / 2 + span * i / (positions.size - 1).coerceAtLeast(1)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .graphicsLayer() // own cached layer: redrawn only when this device changes
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
            val c = Offset(center.x, center.y + r * 0.18f)
            val night = Night.on
            // Black legend ring with an engraved tick per position, and the labels around it.
            val ringR = r * 0.6f
            drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), c + Offset(0f, r * 0.06f), ringR * 1.15f),
                ringR * 1.15f, c + Offset(0f, r * 0.06f))
            drawCircle(Color(0xFF141516), ringR, c)
            drawCircle(Color(0xFF3A3D40), ringR, c, style = Stroke(r * 0.02f))
            positions.forEachIndexed { i, label ->
                val sel = i == selected
                rotate(angleOf(i), c) {
                    drawLine(if (sel) Lens.Yellow else Color(0xFFD8D8D8), Offset(c.x, c.y - ringR * 0.97f), Offset(c.x, c.y - ringR * 0.8f),
                        strokeWidth = r * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                val a = Math.toRadians(angleOf(i).toDouble() - 90)
                val style = TextStyle(color = if (sel) Lens.Yellow else Color(0xFFC8C8C8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                val m = tm.measure(label, style)
                // Anchor each label on the side facing away from the dial so the end positions don't crowd it.
                val rr = ringR * 1.12f
                val p = Offset((c.x + rr * cos(a)).toFloat(), (c.y + rr * sin(a)).toFloat())
                val ax = (cos(a).toFloat() + 1f) / 2f
                val ay = (sin(a).toFloat() + 1f) / 2f
                drawText(m, topLeft = Offset(p.x - m.size.width * (1f - ax), p.y - m.size.height * (1f - ay)))
            }
            // Chrome bezel and the lock cylinder face.
            val bez = r * 0.44f
            drawCircle(Brush.linearGradient(listOf(Color(0xFFF4F5F6), Color(0xFFB4B8BC), Color(0xFF6C7176), Color(0xFF3E4246)),
                c - Offset(bez, bez), c + Offset(bez, bez)), bez, c)
            drawArc(Color.White.copy(alpha = 0.7f), 200f, 70f, false, c - Offset(bez * 0.9f, bez * 0.9f), Size(bez * 1.8f, bez * 1.8f),
                style = Stroke(bez * 0.05f))
            val cyl = bez * 0.78f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFD9DCDF), Color(0xFF9EA3A8), Color(0xFF5E6368)), c - Offset(cyl * 0.3f, cyl * 0.35f), cyl * 1.4f), cyl, c)
            drawCircle(Color.Black.copy(alpha = 0.5f), cyl, c, style = Stroke(bez * 0.04f))
            if (night) drawCircle(Color.Black.copy(alpha = 0.45f), bez, c)
            rotate(angleOf(selected), c) {
                // Keyway the blade sits in.
                drawRoundRect(Color(0xFF151515), Offset(c.x - cyl * 0.12f, c.y - cyl * 0.7f), Size(cyl * 0.24f, cyl * 1.4f), CornerRadius(cyl * 0.1f))
                // Brass key: shadow, then the bow (head) standing out from the cylinder, pointing at the selected position.
                val brass = listOf(Color(0xFFF6E7A6), Color(0xFFD4B456), Color(0xFF8C6E22)).map { if (night) lerp(it, Color.Black, 0.45f) else it }
                val bowW = bez * 0.8f
                val bowTop = c.y - bez * 1.3f
                val bowBottom = c.y - cyl * 0.1f
                drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(c.x - bowW / 2 + bez * 0.06f, bowTop + bez * 0.12f),
                    Size(bowW, bowBottom - bowTop), CornerRadius(bowW * 0.42f))
                drawRoundRect(Brush.linearGradient(brass, Offset(c.x - bowW / 2, bowTop), Offset(c.x + bowW / 2, bowBottom)),
                    Offset(c.x - bowW / 2, bowTop), Size(bowW, bowBottom - bowTop), CornerRadius(bowW * 0.42f))
                drawRoundRect(Color(0xFF5A4512), Offset(c.x - bowW / 2, bowTop), Size(bowW, bowBottom - bowTop), CornerRadius(bowW * 0.42f),
                    style = Stroke(bez * 0.04f))
                // Key ring hole near the tip, and a raised rib down the middle of the bow.
                drawCircle(Color(0xFF1A1A1A), bowW * 0.17f, Offset(c.x, bowTop + bowW * 0.32f))
                drawCircle(Color(0xFF5A4512), bowW * 0.17f, Offset(c.x, bowTop + bowW * 0.32f), style = Stroke(bez * 0.03f))
                drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x - bowW * 0.28f, bowTop + bowW * 0.62f),
                    Offset(c.x - bowW * 0.28f, bowBottom - bowW * 0.18f), bez * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        LegendPlate(legend, 104.dp)
    }
}

@Composable
fun PanelSection(title: String, modifier: Modifier = Modifier, accent: Color = SectionNeutral, content: @Composable () -> Unit) {
    // Coloured outline with the legend on a filled tab down the left edge, reading bottom to top.
    val shape = RoundedCornerShape(12.dp)
    val tabShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    val edge = accent
    Box(modifier.border(2.dp, edge, shape)) {
        // The tab takes the section's height from its contents (no intrinsics: they break inside the scrolling console).
        Box(Modifier.matchParentSize()) {
            Box(Modifier.width(22.dp).fillMaxHeight().background(edge, tabShape), contentAlignment = Alignment.Center) {
                Text(title, color = if (accent == SectionNeutral) Color(0xFF15191D) else Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.sideways())
            }
        }
        Column(Modifier.padding(start = 30.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

/** Section outline colours: red ride control, amber station, green dispatch; light steel for the rest. */
val SectionNeutral = Color(0xFFB9BEC3)
val SectionRed = Color(0xFFD32F2F)
val SectionAmber = Color(0xFFE59400)
val SectionGreen = Color(0xFF2E9E4F)

/** Lays the content out rotated 90° anticlockwise, swapping its width and height so it takes only the rotated space. */
private fun Modifier.sideways() = layout { measurable, constraints ->
    val p = measurable.measure(androidx.compose.ui.unit.Constraints())
    layout(p.height, p.width) {
        p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

/** Riveted brushed-stainless nameplate with an engraved black legend, as fitted to real control panels. */
@Composable
fun SteelLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .graphicsLayer()
            .background(
                Brush.verticalGradient(listOf(Color(0xFFE4E7EA), Color(0xFFB9BEC3), Color(0xFFD3D7DB), Color(0xFFA9AEB4))
                ),
                RoundedCornerShape(2.dp),
            )
            .border(1.dp, Color(0xFF6E747A), RoundedCornerShape(2.dp))
            .drawBehind {
                // Faint horizontal brushing.
                var y = 2f
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += 3f
                }
                // A rivet at each end.
                val rr = 2.6.dp.toPx()
                listOf(6.dp.toPx(), size.width - 6.dp.toPx()).forEach { x ->
                    val c = Offset(x, size.height / 2)
                    drawCircle(Color(0xFF5E646A), rr + 0.8f, c)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White, Color(0xFF9EA4AA)), center = c - Offset(rr * 0.4f, rr * 0.4f), radius = rr * 1.4f),
                        rr, c,
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color(0xFF1A1C1E), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}
