package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.sim.IoModule
import com.abemu.coaster.sim.ModuleKind
import com.abemu.coaster.sim.RidePlc

/*
 * ControlLogix-style chassis hardware, drawn rather than laid out: moulded black module fronts with a
 * smoked status-indicator window and a hinged terminal-block door carrying a paper wiring label, a
 * controller with its scrolling 4-character display, keyswitch and Ethernet port, a vented power supply,
 * and blank slot fillers. All sizes are in dp via [u].
 */

val SlotWidth: Dp = 64.dp
val PsWidth: Dp = 78.dp
val ModuleHeight: Dp = 300.dp

private val LedGreen = Color(0xFF39E26A)
private val LedAmber = Color(0xFFFFC400)
private val LedRed = Color(0xFFFF3B30)
private val LedOff = Color(0xFF1B1E21)
private val ForceRing = Color(0xFFFFB000)
private val SafetyRed = Color(0xFFC62828)
private val PaperLabel = Color(0xFFE9E5D8)
private val Silk = Color(0xFFC9CED4)

private fun kindLabel(k: ModuleKind) = when (k) {
    ModuleKind.INPUT -> "DC INPUT"
    ModuleKind.OUTPUT -> "DC OUTPUT"
    ModuleKind.ANALOG -> "ANALOG INPUT"
    ModuleKind.SAFETY_IN -> "SAFETY INPUT"
    ModuleKind.SAFETY_OUT -> "SAFETY OUTPUT"
    ModuleKind.CPU -> "CONTROLLER"
}

// ---------------------------------------------------------------- shared drawing helpers

private class Ink(val tm: TextMeasurer, val u: Float)

/** Text centred on [cx] (or left-aligned at [cx] when [left]), top at [y]. */
private fun DrawScope.label(
    ink: Ink, text: String, cx: Float, y: Float, sp: Float, color: Color,
    bold: Boolean = false, left: Boolean = false, mono: Boolean = false,
) {
    val l = ink.tm.measure(text, TextStyle(color = color, fontSize = sp.sp, lineHeight = sp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif))
    drawText(l, topLeft = Offset(if (left) cx else cx - l.size.width / 2f, y))
}

/** Moulded black module housing: rounded front, edge highlights, release latch, bottom vents. */
private fun DrawScope.housing(u: Float, safety: Boolean, body: List<Color> = listOf(
    Color(0xFF34383D), Color(0xFF1F2225), Color(0xFF191B1E), Color(0xFF2B2E32))) {
    val w = size.width; val h = size.height
    drawRoundRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(u, u * 2), size = Size(w, h - u), cornerRadius = CornerRadius(2 * u))
    drawRoundRect(Brush.horizontalGradient(body), size = Size(w, h - u), cornerRadius = CornerRadius(2 * u))
    drawLine(Color.White.copy(alpha = 0.12f), Offset(u * 0.6f, 2 * u), Offset(u * 0.6f, h - 3 * u), u * 0.8f)
    drawLine(Color.Black.copy(alpha = 0.6f), Offset(w - u * 0.6f, 2 * u), Offset(w - u * 0.6f, h - 3 * u), u * 0.8f)
    // Release latch at the top.
    drawRoundRect(Color(0xFF3E4348), Offset(w * 0.32f, u), Size(w * 0.36f, 4 * u), CornerRadius(u))
    drawLine(Color.White.copy(alpha = 0.15f), Offset(w * 0.34f, u * 1.6f), Offset(w * 0.66f, u * 1.6f), u * 0.6f)
    if (safety) drawRect(SafetyRed, Offset(0f, 7 * u), Size(w, 3 * u))
    // Vent slits along the bottom.
    for (i in 0 until 3) drawRoundRect(Color.Black.copy(alpha = 0.7f), Offset(w * 0.2f, h - 11 * u + i * 3 * u), Size(w * 0.6f, 1.4f * u), CornerRadius(u))
}

/** A status LED, rectangular or round, with a glow when lit and an amber ring when its point is forced. */
private fun DrawScope.led(u: Float, c: Offset, w: Float, h: Float, color: Color, on: Boolean, forced: Boolean = false, round: Boolean = false) {
    val tl = Offset(c.x - w / 2, c.y - h / 2)
    if (on) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, w * 1.4f), w * 1.4f, c)
    val fill = if (on) color else LedOff
    if (round) drawCircle(fill, w / 2, c) else drawRoundRect(fill, tl, Size(w, h), CornerRadius(u * 0.6f))
    if (on) {
        if (round) drawCircle(Color.White.copy(alpha = 0.6f), w * 0.18f, c - Offset(w * 0.12f, w * 0.12f))
        else drawRoundRect(Color.White.copy(alpha = 0.55f), tl + Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.3f), CornerRadius(u * 0.4f))
    } else {
        if (round) drawCircle(Color(0xFF34383C), w / 2, c, style = Stroke(u * 0.5f))
        else drawRoundRect(Color(0xFF34383C), tl, Size(w, h), CornerRadius(u * 0.6f), style = Stroke(u * 0.5f))
    }
    if (forced) {
        val p = u * 1.1f
        if (round) drawCircle(ForceRing, w / 2 + p, c, style = Stroke(u * 0.9f))
        else drawRoundRect(ForceRing, tl - Offset(p, p), Size(w + 2 * p, h + 2 * p), CornerRadius(u), style = Stroke(u * 0.9f))
    }
}

/** Smoked acrylic window the status LEDs sit behind. */
private fun DrawScope.smokedWindow(u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(Color(0xFF08090A), Offset(x, y), Size(w, h), CornerRadius(u * 1.2f))
    drawRoundRect(Color(0xFF4A4F55), Offset(x, y), Size(w, h), CornerRadius(u * 1.2f), style = Stroke(u * 0.6f))
}

private fun DrawScope.windowGloss(u: Float, x: Float, y: Float, w: Float, h: Float) {
    val p = Path().apply { moveTo(x + u, y + u); lineTo(x + w * 0.55f, y + u); lineTo(x + u, y + h * 0.7f); close() }
    drawPath(p, Color.White.copy(alpha = 0.05f))
}

// ---------------------------------------------------------------- I/O module

@Composable
fun ModuleFace(tm: TextMeasurer, plc: RidePlc, m: IoModule, selected: Boolean, onClick: () -> Unit) {
    val faulted = m.kind == ModuleKind.SAFETY_IN && plc.safety.ioFault
    val states = m.points.map { it.value(plc) }
    val forced = m.points.map { plc.forces[it.tag] != null }
    val blink = flasher()
    val now = System.currentTimeMillis()
    Canvas(Modifier.size(SlotWidth, ModuleHeight).clickable { Haptics.tick(); onClick() }) {
        val ink = Ink(tm, 1.dp.toPx())
        if (m.kind == ModuleKind.CPU) drawCpu(ink, plc, blink, now) else drawIo(ink, m, states, forced, faulted, blink)
        if (selected) {
            val u = ink.u
            drawRoundRect(Hmi.Info.copy(alpha = 0.35f), Offset(-u, -u), Size(size.width + 2 * u, size.height + u), CornerRadius(3 * u), style = Stroke(4 * u))
            drawRoundRect(Hmi.Info, size = Size(size.width, size.height - u), cornerRadius = CornerRadius(2 * u), style = Stroke(2 * u))
        }
    }
}

private fun DrawScope.drawIo(ink: Ink, m: IoModule, states: List<Boolean>, forced: List<Boolean>, faulted: Boolean, blink: Boolean) {
    val u = ink.u
    val w = size.width; val h = size.height
    val safety = m.kind == ModuleKind.SAFETY_IN || m.kind == ModuleKind.SAFETY_OUT
    housing(u, safety)
    label(ink, m.catalog, w / 2, 12 * u, 6.5f, Color.White, bold = true)
    label(ink, kindLabel(m.kind), w / 2, 21 * u, 4.5f, Silk)

    // Status indicator window: numbered point LEDs in rows of eight, then OK (and CAL on analog).
    val analog = m.kind == ModuleKind.ANALOG
    val rows = if (analog) 0 else (m.points.size + 7) / 8
    val wx = 4 * u; val wy = 29 * u; val ww = w - 8 * u
    val wh = 6 * u + rows * 13 * u + 10 * u
    smokedWindow(u, wx, wy, ww, wh)
    val out = m.kind == ModuleKind.OUTPUT || m.kind == ModuleKind.SAFETY_OUT
    val cell = (ww - 4 * u) / 8
    for (r in 0 until rows) {
        val y0 = wy + 4 * u + r * 13 * u
        for (c in 0 until 8) {
            val k = r * 8 + c
            if (k >= m.points.size) break
            val cx = wx + 2 * u + cell * c + cell / 2
            label(ink, "$k", cx, y0, 3.6f, Color(0xFF8A9096))
            led(u, Offset(cx, y0 + 8.5f * u), cell * 0.66f, 3.4f * u, if (out) LedAmber else LedGreen, states[k], forced[k])
        }
    }
    val okY = wy + wh - 6 * u
    val okOn = !faulted || blink
    led(u, Offset(wx + 6 * u, okY), 3.6f * u, 3.6f * u, if (faulted) LedRed else LedGreen, okOn, round = true)
    label(ink, "OK", wx + 10 * u, okY - 2.6f * u, 4.2f, Silk, bold = true, left = true)
    if (analog) {
        led(u, Offset(wx + ww / 2 + 4 * u, okY), 3.6f * u, 3.6f * u, LedAmber, false, round = true)
        label(ink, "CAL", wx + ww / 2 + 8 * u, okY - 2.6f * u, 4.2f, Silk, bold = true, left = true)
    }
    windowGloss(u, wx, wy, ww, wh)

    // Hinged RTB door with a paper wiring label.
    val dTop = wy + wh + 6 * u
    val dBot = h - 16 * u
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF30343A), Color(0xFF212428), Color(0xFF1B1D20)), dTop, dBot),
        Offset(2.5f * u, dTop), Size(w - 5 * u, dBot - dTop), CornerRadius(1.6f * u))
    drawRoundRect(Color.Black.copy(alpha = 0.7f), Offset(2.5f * u, dTop), Size(w - 5 * u, dBot - dTop), CornerRadius(1.6f * u), style = Stroke(u * 0.7f))
    drawLine(Color.White.copy(alpha = 0.14f), Offset(4 * u, dTop + u), Offset(w - 4 * u, dTop + u), u * 0.6f)
    val lTop = dTop + 6 * u
    val lines = if (analog) 8 else m.points.size
    val lBot = (lTop + 12 * u + lines * 8.5f * u).coerceAtMost(dBot - 18 * u)
    drawRect(PaperLabel, Offset(7 * u, lTop), Size(w - 14 * u, lBot - lTop))
    drawRect(Color(0xFF2B2B2B), Offset(7 * u, lTop), Size(w - 14 * u, 9 * u))
    label(ink, m.catalog.removePrefix("1756-"), w / 2, lTop + 2 * u, 4.2f, Color.White, bold = true)
    val pitch = (lBot - lTop - 11 * u) / lines
    for (k in 0 until lines) {
        val y = lTop + 11 * u + k * pitch
        label(ink, if (analog) "IN$k" else "$k", 9 * u, y + pitch * 0.12f, 3.2f, Color(0xFF6B675C), left = true, mono = true)
        drawLine(Color(0xFFB9B4A6), Offset(8 * u, y + pitch), Offset(w - 8 * u, y + pitch), u * 0.4f)
    }
    // Finger grip and hinge pins at the bottom of the door.
    drawRoundRect(Color.Black.copy(alpha = 0.75f), Offset(w * 0.3f, dBot - 9 * u), Size(w * 0.4f, 3.5f * u), CornerRadius(1.7f * u))
    drawLine(Color.White.copy(alpha = 0.12f), Offset(w * 0.32f, dBot - 5.2f * u), Offset(w * 0.68f, dBot - 5.2f * u), u * 0.5f)
    for (x in listOf(6 * u, w - 6 * u)) drawCircle(Color(0xFF55595E), 1.4f * u, Offset(x, dBot - 2.5f * u))
}

// ---------------------------------------------------------------- controller

private fun DrawScope.drawCpu(ink: Ink, plc: RidePlc, blink: Boolean, now: Long) {
    val u = ink.u
    val w = size.width; val h = size.height
    housing(u, safety = true)
    label(ink, "1756-L83ES", w / 2, 12 * u, 6.5f, Color.White, bold = true)
    label(ink, "GuardLogix 5580", w / 2, 21 * u, 4.2f, Silk)

    // Scrolling 4-character status display, one character every 280 ms.
    val parts = buildList {
        add(if (plc.safety.ioFault) "I/O Fault" else "Rem Run")
        if (plc.forces.any) add("Forces Enabled")
        add("Safety Locked")
        add("192.168.1.10")
    }
    val msg = parts.joinToString("    ") + "    "
    val off = ((now / 280) % msg.length).toInt()
    val shown = (msg + msg).substring(off, off + 4)
    val dx = 6 * u; val dy = 29 * u; val dw = w - 12 * u; val dh = 14 * u
    drawRoundRect(Color(0xFF3A3E43), Offset(dx - u, dy - u), Size(dw + 2 * u, dh + 2 * u), CornerRadius(u * 1.5f))
    drawRect(Color(0xFF020302), Offset(dx, dy), Size(dw, dh))
    val glyph = dw / 4
    val disp = if (plc.safety.ioFault) Color(0xFFFF5A4A) else Color(0xFF7CFF8A)
    shown.forEachIndexed { i, ch ->
        val cx = dx + glyph * i + glyph / 2
        drawRect(disp.copy(alpha = 0.06f), Offset(cx - glyph * 0.42f, dy + 1.5f * u), Size(glyph * 0.84f, dh - 3 * u))
        if (ch != ' ') drawCircle(Brush.radialGradient(listOf(disp.copy(alpha = 0.35f), Color.Transparent), Offset(cx, dy + dh / 2), glyph * 0.6f),
            glyph * 0.6f, Offset(cx, dy + dh / 2))
        label(ink, ch.toString(), cx, dy + 0.8f * u, 10f, disp, bold = true, mono = true)
    }
    windowGloss(u, dx, dy, dw, dh)

    // Status LEDs.
    val leds = listOf<Pair<String, Color?>>(
        "RUN" to LedGreen,
        "FORCE" to (if (plc.forces.any) LedAmber else null),
        "SD" to null,
        "OK" to LedGreen,
        "SAFE" to LedGreen,
        "I/O" to (if (plc.safety.ioFault) (if (blink) LedRed else null) else LedGreen),
    )
    val lx = 6 * u; val ly = 50 * u; val lw = w - 12 * u; val lh = leds.size * 8.5f * u + 4 * u
    smokedWindow(u, lx, ly, lw, lh)
    leds.forEachIndexed { i, (name, c) ->
        val y = ly + 6 * u + i * 8.5f * u
        led(u, Offset(lx + 6 * u, y), 3.6f * u, 3.6f * u, c ?: LedGreen, c != null, round = true)
        label(ink, name, lx + 11 * u, y - 2.8f * u, 4.6f, Silk, bold = true, left = true)
    }
    windowGloss(u, lx, ly, lw, lh)

    // Mode keyswitch, turned to REM.
    val kc = Offset(w / 2, 123 * u)
    label(ink, "RUN", kc.x - 14 * u, kc.y - 12 * u, 3.6f, Silk, bold = true)
    label(ink, "REM", kc.x, kc.y - 15.5f * u, 3.6f, Color.White, bold = true)
    label(ink, "PROG", kc.x + 14 * u, kc.y - 12 * u, 3.6f, Silk, bold = true)
    drawCircle(Brush.linearGradient(listOf(Color(0xFFDADDE0), Color(0xFF7D838A), Color(0xFF3F4449)), kc - Offset(8 * u, 8 * u), kc + Offset(8 * u, 8 * u)), 8 * u, kc)
    drawCircle(Color(0xFF16181A), 6.2f * u, kc)
    drawRoundRect(Color(0xFFB8BCC0), Offset(kc.x - 1.1f * u, kc.y - 4.5f * u), Size(2.2f * u, 9 * u), CornerRadius(u))

    // SD card door and USB port.
    drawRoundRect(Color(0xFF2C3034), Offset(9 * u, 142 * u), Size(w - 18 * u, 16 * u), CornerRadius(1.2f * u))
    drawRoundRect(Color.Black.copy(alpha = 0.7f), Offset(9 * u, 142 * u), Size(w - 18 * u, 16 * u), CornerRadius(1.2f * u), style = Stroke(u * 0.6f))
    label(ink, "SD", w / 2, 146 * u, 4.6f, Silk, bold = true)
    drawRect(Color(0xFF050505), Offset(w / 2 - 4 * u, 165 * u), Size(8 * u, 3 * u))
    drawRect(Color(0xFF8C9298), Offset(w / 2 - 3 * u, 165.8f * u), Size(6 * u, 1 * u))
    label(ink, "USB", w / 2, 170 * u, 3.6f, Silk)

    // Embedded Ethernet port: LINK steady, ACT flickering with traffic.
    val ey = h - 52 * u
    label(ink, "ENET 1", w / 2, ey - 9 * u, 4.2f, Silk, bold = true)
    val ex = w / 2 - 10 * u
    drawRoundRect(Color(0xFF6E747A), Offset(ex - u, ey - u), Size(22 * u, 18 * u), CornerRadius(u))
    drawRect(Color(0xFF070808), Offset(ex, ey), Size(20 * u, 16 * u))
    drawRect(Color(0xFF070808), Offset(ex + 6 * u, ey + 16 * u), Size(8 * u, 2.5f * u))
    for (k in 0 until 8) drawRect(Color(0xFFC8A64A), Offset(ex + 2.5f * u + k * 2 * u, ey + 1.5f * u), Size(1 * u, 3 * u))
    val act = ((now / 70) * 2654435761L).let { (it ushr 7) % 3 != 0L }
    led(u, Offset(ex + 3 * u, ey + 13.5f * u), 3f * u, 2f * u, LedGreen, true)
    led(u, Offset(ex + 17 * u, ey + 13.5f * u), 3f * u, 2f * u, LedAmber, act)
    label(ink, "LNK", ex + 3 * u, ey + 20 * u, 3.2f, Silk)
    label(ink, "ACT", ex + 17 * u, ey + 20 * u, 3.2f, Silk)
}

// ---------------------------------------------------------------- power supply and fillers

@Composable
fun PowerSupplyFace(tm: TextMeasurer) {
    Canvas(Modifier.size(PsWidth, ModuleHeight)) {
        val ink = Ink(tm, 1.dp.toPx())
        val u = ink.u
        val w = size.width; val h = size.height
        housing(u, safety = false, body = listOf(Color(0xFF43484E), Color(0xFF2D3135), Color(0xFF272A2E), Color(0xFF3A3E43)))
        label(ink, "1756-PA75", w / 2, 12 * u, 6.5f, Color.White, bold = true)
        label(ink, "POWER SUPPLY", w / 2, 21 * u, 4.5f, Silk)
        // Brand badge.
        drawOval(Color(0xFF1F5AA8), Offset(w / 2 - 11 * u, 30 * u), Size(22 * u, 13 * u))
        drawOval(Color.White, Offset(w / 2 - 11 * u, 30 * u), Size(22 * u, 13 * u), style = Stroke(u * 0.8f))
        label(ink, "BR", w / 2, 32.5f * u, 6f, Color.White, bold = true)
        // POWER LED.
        smokedWindow(u, 8 * u, 50 * u, w - 16 * u, 11 * u)
        led(u, Offset(15 * u, 55.5f * u), 4.2f * u, 4.2f * u, LedGreen, true, round = true)
        label(ink, "POWER", 21 * u, 52.6f * u, 4.8f, Silk, bold = true, left = true)
        // Vent grille.
        for (r in 0 until 14) for (c in 0 until 4) {
            drawRoundRect(Color.Black.copy(alpha = 0.75f), Offset(9 * u + c * (w - 18 * u) / 4, 68 * u + r * 6 * u),
                Size((w - 18 * u) / 4 - 2.5f * u, 3 * u), CornerRadius(1.5f * u))
        }
        // Rating sticker with a warning triangle.
        val sy = 158 * u
        drawRect(PaperLabel, Offset(8 * u, sy), Size(w - 16 * u, 38 * u))
        val tri = Path().apply { moveTo(15 * u, sy + 4 * u); lineTo(21 * u, sy + 14 * u); lineTo(9 * u, sy + 14 * u); close() }
        drawPath(tri, Color(0xFFFFD000)); drawPath(tri, Color.Black, style = Stroke(u * 0.6f))
        label(ink, "!", 15 * u, sy + 6.5f * u, 5f, Color.Black, bold = true)
        label(ink, "85–265V AC", 24 * u, sy + 4 * u, 4.2f, Color(0xFF2B2B2B), bold = true, left = true)
        label(ink, "47–63 Hz", 24 * u, sy + 10 * u, 4.2f, Color(0xFF2B2B2B), left = true)
        label(ink, "100 VA max", 11 * u, sy + 19 * u, 4.2f, Color(0xFF2B2B2B), left = true)
        label(ink, "Out 5.1V 13A", 11 * u, sy + 25 * u, 4.2f, Color(0xFF2B2B2B), left = true)
        label(ink, "Series C", 11 * u, sy + 31 * u, 4.2f, Color(0xFF2B2B2B), left = true)
        // Terminal block under its cover.
        val ty = 206 * u
        val th = h - 16 * u - ty
        drawRoundRect(Color(0xFF1A1C1F), Offset(5 * u, ty), Size(w - 10 * u, th), CornerRadius(1.6f * u))
        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(5 * u, ty), Size(w - 10 * u, th * 0.45f), CornerRadius(1.6f * u))
        listOf("L1", "L2/N", "GND").forEachIndexed { i, t ->
            val y = ty + 10 * u + i * (th - 16 * u) / 3
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE0E3E6), Color(0xFF6E747A)), Offset(w * 0.65f, y), 4 * u), 4 * u, Offset(w * 0.65f, y))
            drawLine(Color(0xFF3A3E43), Offset(w * 0.65f - 2.8f * u, y), Offset(w * 0.65f + 2.8f * u, y), u)
            label(ink, t, 10 * u, y - 2.6f * u, 4.2f, Silk, bold = true, left = true)
        }
    }
}

/** 1756-N2 blank slot filler. */
@Composable
fun SlotFiller(tm: TextMeasurer) {
    Canvas(Modifier.size(SlotWidth, ModuleHeight)) {
        val ink = Ink(tm, 1.dp.toPx())
        val u = ink.u
        val w = size.width; val h = size.height
        housing(u, safety = false, body = listOf(Color(0xFF26292C), Color(0xFF17191B), Color(0xFF141618), Color(0xFF212427)))
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(8 * u, 30 * u), Size(w - 16 * u, h - 60 * u), CornerRadius(2 * u))
        for (i in 0 until 5) {
            val y = 60 * u + i * 36 * u
            drawLine(Color.White.copy(alpha = 0.05f), Offset(12 * u, y), Offset(w - 12 * u, y), u)
        }
        label(ink, "1756-N2", w / 2, h - 26 * u, 4.2f, Color(0xFF6F757B), bold = true)
    }
}
