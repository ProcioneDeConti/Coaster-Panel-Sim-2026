package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.RidePlc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ================================================================ STATION
//
// Layout: left, the station graphic, its controls, the dispatch timer and the operator stands; right, the dispatch
// status banner, the permissive checklist and the PA panel. Every panel shows live state, not just buttons.

private val Amber = Color(0xFFE59400)
private val Locked = Color(0xFF2EBD59)
private val Unlocked = Color(0xFFFFB300)

@Composable
fun StationPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StationFrame(plc)
            StationControls(plc)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DispatchTimer(plc, Modifier.weight(1.15f).fillMaxHeight())
                OperatorStands(plc, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DispatchBanner(plc)
            if (plc.hasSwitch) SwitchTrackFrame(vm)
            PermissivesFrame(plc, Modifier.fillMaxWidth().weight(1f))
            SpielPanel(plc.profile.spec.id, plc.profile.name, Modifier.fillMaxWidth())
        }
    }
}

/** Small filled status tag: [text] on [color], white lettering. */
@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.background(color, RoundedCornerShape(3.dp))
            .border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .let { if (onClick != null) it.clickable { Haptics.tick(); onClick() } else it }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** "LABEL  [STATE]" read-out used along the top of the station frame. */
@Composable
private fun StatusTag(label: String, state: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HmiLabel("$label ", 10.sp, bold = true, color = Hmi.Dim)
        Pill(state, color)
    }
}

// ---------------------------------------------------------------- station graphic

@Composable
private fun StationFrame(plc: RidePlc) {
    val plant = plc.plant
    val st = plant.stationTrain()
    val rowsN = plc.profile.rows
    HmiFrame("STATION – ${if (st != null) "TRAIN ${st.id} IN STATION" else "NO TRAIN"}") {
        // State read-outs: restraints, gates, the operator's car check, riders aboard.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusTag("RESTRAINTS", restraintState(plc).first, restraintState(plc).second)
            StatusTag("GATES", gateState(plc).first, gateState(plc).second)
            if (plc.restraintWalk && st != null) {
                val done = plc.carChecked.count { it }
                StatusTag("CHECKED", "$done / $rowsN", if (done == rowsN) Hmi.Ok else Hmi.Info)
            }
        }
        var walkMsg by remember { mutableStateOf("") }
        var boarded by remember(st?.id) { mutableStateOf(false) }
        if (st != null && plant.gatePos > 0.0) boarded = true
        val tm = rememberTextMeasurer(cacheSize = 32)
        val u = with(LocalDensity.current) { 1.dp.toPx() }
        val flip = plc.profile.spec.stationExitRight
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF2A3036), RoundedCornerShape(3.dp))
                .pointerInput(plc, u) {
                    detectTapGestures { p ->
                        val g = StationGeo(size.width.toFloat(), rowsN, u, flip)
                        val r = g.carAt(p.x)
                        if (p.y in g.trainY..g.trainY + g.cross && r in 0 until rowsN) walkMsg = plc.checkCar(r)
                    }
                },
        ) {
            val g = StationGeo(size.width, rowsN, u, flip)
            val margin = g.margin
            val pitch = g.pitch
            val platLabel = TextStyle(color = Color(0xFF2A3036), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            // Unload platform: concrete with a yellow safety edge.
            drawRect(Brush.verticalGradient(listOf(Color(0xFFA9B0B6), Color(0xFF959DA4))), Offset.Zero, Size(size.width, g.unloadH))
            drawRect(Color(0xFFFFC400), Offset(0f, g.unloadH - 3 * u), Size(size.width, 3 * u))
            drawText(tm, "UNLOAD", Offset(margin, 4 * u), platLabel)
            // Track bed: ties and two rails, centred under the train.
            val r1 = g.cy - g.cross * 0.33f
            val r2 = g.cy + g.cross * 0.33f
            var x = 0f
            while (x < size.width) { drawLine(Color(0xFF4A3F36), Offset(x, r1 - 7 * u), Offset(x, r2 + 7 * u), 4 * u); x += 11 * u }
            for (ry in listOf(r1, r2)) drawLine(Color(0xFFAEB5BB), Offset(0f, ry), Offset(size.width, ry), 3 * u)

            if (st != null) withTransform({ if (flip) scale(-1f, 1f, Offset(size.width / 2, 0f)) }) {
                val body = trainColor(plc.profile, st.id)
                val per = st.seatsPerRow
                val across = if (per >= 2) 2 else 1
                val along = (per + across - 1) / across
                val corner = CornerRadius(5 * u)
                for (r in 0 until rowsN) {
                    val x0 = margin + r * pitch
                    val o = Offset(x0 + 2 * u, g.trainY)
                    val sz = Size(pitch - 4 * u, g.cross)
                    // Car body, longer than it is wide; the front car gets a pointed nose. Drawn travelling left and
                    // mirrored as a whole for right-exit stations.
                    if (r == 0) {
                        drawPath(Path().apply {
                            moveTo(o.x - 9 * u, o.y + sz.height / 2)
                            lineTo(o.x + 6 * u, o.y); lineTo(o.x + sz.width, o.y); lineTo(o.x + sz.width, o.y + sz.height)
                            lineTo(o.x + 6 * u, o.y + sz.height); close()
                        }, body)
                    } else drawRoundRect(body, o, sz, corner)
                    drawRoundRect(Color.White.copy(alpha = 0.16f), o, Size(sz.width, sz.height * 0.3f), corner) // sheen
                    // Seats: rows of two across, front to back. Each restraint bar sits in front of its rider (left),
                    // lit green when locked, amber when not; a head shows when the seat is taken.
                    val sl = (sz.width - 8 * u) / along
                    val sa = (sz.height - 6 * u) / across
                    for (k in 0 until per) {
                        val idx = r * per + k
                        val so = Offset(o.x + 4 * u + (k / across) * sl, o.y + 3 * u + (k % across) * sa)
                        val seat = Size(sl - 2 * u, sa - 2 * u)
                        drawRoundRect(Color(0xFF14181C), so, seat, CornerRadius(2.5f * u))
                        drawRoundRect(if (st.seatLocked[idx]) Locked else Unlocked, so + Offset(1.5f * u, 1.5f * u),
                            Size(2.5f * u, seat.height - 3 * u), CornerRadius(1.2f * u))
                        if (boarded && st.seatTaken[idx])
                            drawCircle(Color(0xFFF2D7B6), minOf(seat.width, seat.height) * 0.26f, so + Offset(seat.width * 0.6f, seat.height / 2))
                    }
                    if (!plant.rowLockSensor(r)) drawRoundRect(Color(0xFFFF5252), o, sz, corner, style = Stroke(2.5f * u))
                    // Operator's push-pull check: a green tick badge on the car.
                    if (plc.carChecked[r]) {
                        val c = Offset(o.x + sz.width - 6 * u, o.y - 2 * u)
                        drawCircle(Color.White, 7 * u, c)
                        drawCircle(Locked, 6 * u, c)
                        drawPath(Path().apply {
                            moveTo(c.x - 3 * u, c.y); lineTo(c.x - 0.5f * u, c.y + 2.5f * u); lineTo(c.x + 3.5f * u, c.y - 2.5f * u)
                        }, Color.White, style = Stroke(1.6f * u, cap = StrokeCap.Round))
                    }
                }
            } else {
                drawText(tm, "NO TRAIN IN STATION", Offset(margin, g.cy - 7 * u),
                    TextStyle(color = Color(0xFF8B949C), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
            }

            // Load platform with its yellow edge and the gate line: a leaf per car swinging 90 degrees toward the train.
            val gateY = g.gateY
            drawRect(Brush.verticalGradient(listOf(Color(0xFF959DA4), Color(0xFF7F878E))), Offset(0f, gateY), Size(size.width, size.height - gateY))
            val open = plant.gatePos.toFloat().coerceIn(0f, 1f)
            val ang = open * (PI / 2).toFloat()
            val gateColor = if (plant.gateClosedSensor) Locked else Unlocked
            val len = minOf(pitch * 0.5f, 30 * u)
            drawLine(Color(0xFF3B4147), Offset(0f, gateY), Offset(size.width, gateY), 4 * u) // fence
            for (r in 0 until rowsN) {
                val x0 = g.carX(r)
                val hinge = Offset(x0 + pitch / 2 - len / 2, gateY)
                drawLine(Color(0xFF2A3036), hinge, hinge + Offset(len, 0f), 5 * u) // the opening
                drawLine(gateColor, hinge, hinge + Offset(len * cos(ang), -len * sin(ang)), 4 * u, cap = StrokeCap.Round)
                drawCircle(Color(0xFF1E2226), 3 * u, hinge)
            }
            drawRect(Color(0xFFFFC400), Offset(0f, gateY + 3 * u), Size(size.width, 3 * u))
            // Car numbers on the load platform under each gate.
            val num = TextStyle(color = Color(0xFF2A3036), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            for (r in 0 until rowsN) {
                val l = tm.measure("${r + 1}", num)
                drawText(l, topLeft = Offset(g.carX(r) + pitch / 2 - l.size.width / 2f, gateY + 8 * u))
            }
            drawText(tm, "LOAD", Offset(size.width - margin - 30 * u, size.height - 14 * u), platLabel)
        }
        // Key and restraint-walk prompt on one line.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(Locked, "locked"); LegendSwatch(Unlocked, "unlocked"); LegendSwatch(Color(0xFFFF5252), "row fault", outline = true)
            if (plc.restraintWalk) {
                val left = plc.carChecked.count { !it }
                HmiLabel(
                    when {
                        st == null -> ""
                        left == 0 -> "Check complete – load side may go ready"
                        else -> "Tap each car to push-pull test it"
                    } + if (walkMsg.isNotEmpty()) "  ·  $walkMsg" else "",
                    10.sp, bold = true, color = if (left == 0) Hmi.Ok else Hmi.Info,
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, outline: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 10.dp).let {
            if (outline) it.border(2.dp, color, RoundedCornerShape(2.dp)) else it.background(color, RoundedCornerShape(2.dp))
        })
        HmiLabel(" $label", 10.sp, color = Hmi.Dim)
    }
}

/**
 * Station graphic geometry, shared by the drawing and the tap handler. Cars keep a realistic plan shape (about
 * 1.8x longer than wide); on rides with few, long cars the train no longer stretches across the whole platform.
 */
private class StationGeo(private val width: Float, rows: Int, u: Float, private val flip: Boolean = false) {
    val margin = 16 * u
    val unloadH = 22 * u
    val cy = unloadH + 45 * u
    val cross: Float
    val pitch: Float
    init {
        val free = (width - 2 * margin) / rows
        cross = minOf(46 * u, free * 0.68f)
        pitch = minOf(free, cross * 1.45f + 4 * u)
    }
    val trainY get() = cy - cross / 2
    /** Left edge of car [r]'s slot (car 1 is the front car, at the exit end). */
    fun carX(r: Int) = if (flip) width - margin - (r + 1) * pitch else margin + r * pitch
    /** Car under screen x, or -1. */
    fun carAt(x: Float): Int = ((if (flip) width - margin - x else x - margin) / pitch).let { if (it < 0) -1 else it.toInt() }
    val gateY = unloadH + 100 * u
}

private fun restraintState(plc: RidePlc): Pair<String, Color> = when {
    plc.plant.stationTrain() == null -> "NO TRAIN" to Hmi.Off
    plc.allRowsLocked && !plc.plant.restraintUnlockCmd -> "LOCKED" to Hmi.Ok
    plc.plant.restraintUnlockCmd -> "OPEN" to Amber
    else -> "LOCKING" to Amber
}

private fun gateState(plc: RidePlc): Pair<String, Color> = when {
    plc.gatesClosed -> "CLOSED" to Hmi.Ok
    plc.plant.gatesOpenCmd && plc.plant.gatePos >= 0.97 -> "OPEN" to Amber
    else -> "MOVING" to Amber
}

// ---------------------------------------------------------------- controls

@Composable
private fun StationControls(plc: RidePlc) {
    val inp = plc.inputs
    HmiFrame("STATION CONTROLS") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlGroup("RESTRAINTS", restraintState(plc), Modifier.weight(1f),
                "OPEN" to { on: Boolean -> inp.restraintOpen = on }, "LOCK" to { on: Boolean -> inp.restraintClose = on })
            ControlGroup("GATES", gateState(plc), Modifier.weight(1f),
                "OPEN" to { on: Boolean -> inp.gatesOpen = on }, "CLOSE" to { on: Boolean -> inp.gatesClose = on })
        }
    }
}

/** A labelled pair of hold-to-run buttons with the device's current state beside the label. */
@Composable
private fun ControlGroup(
    title: String, state: Pair<String, Color>, modifier: Modifier,
    a: Pair<String, (Boolean) -> Unit>, b: Pair<String, (Boolean) -> Unit>,
) {
    Column(modifier.background(Color(0x14000000), RoundedCornerShape(3.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HmiLabel(title, 11.sp, bold = true, modifier = Modifier.weight(1f))
            Pill(state.first, state.second)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiHoldButton(a.first, Modifier.weight(1f)) { a.second(it) }
            HmiHoldButton(b.first, Modifier.weight(1f)) { b.second(it) }
        }
    }
}

// ---------------------------------------------------------------- dispatch timer

@Composable
private fun DispatchTimer(plc: RidePlc, modifier: Modifier) {
    val target = plc.dispatchTarget
    HmiFrame("DISPATCH TIMER", modifier) {
        val since = plc.sinceDispatch
        val color = when {
            since == null -> Color(0xFF6E7881)
            since <= target * 0.85 -> Color(0xFF3BE07A)
            since <= target -> Color(0xFFFFC233)
            else -> Color(0xFFFF5A5A)
        }
        // LCD read-out: time since the last dispatch against the target.
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF101418), RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF3A4148), RoundedCornerShape(3.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(since?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "-:--",
                fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("TARGET ${mss(target)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB8C2CC), fontWeight = FontWeight.Bold)
                Text("RATED ${plc.profile.spec.ratedCapacity}/h", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF7D8892))
            }
        }
        // Progress toward the target; the tick marks the target, the bar runs on to 150 %.
        val frac = ((since ?: 0.0) / target).coerceIn(0.0, 1.5).toFloat() / 1.5f
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(Color(0xFF9AA1A8), cornerRadius = CornerRadius(4f))
            drawRoundRect(color, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(4f))
            val tx = size.width / 1.5f
            drawLine(Color.Black, Offset(tx, -2f), Offset(tx, size.height + 2f), 3f)
        }
        val avg = plc.avgInterval
        val pct = avg?.let { plc.profile.ratedInterval / it * 100 }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("LAST", plc.lastInterval?.let { mss(it) } ?: "—", Modifier.weight(1f),
                plc.lastInterval?.let { if (it <= target) Hmi.Ok else Hmi.Bad })
            Stat("AVG (20)", avg?.let { mss(it) } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("RIDERS/H", avg?.let { "${(3600 / it * plc.profile.ridersPerDispatch).toInt()}" } ?: "—", Modifier.weight(1f))
            Stat("OF RATED", pct?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f),
                pct?.let { if (it >= 95) Hmi.Ok else if (it >= 80) Amber else Hmi.Bad })
        }
        IntervalHistory(plc, Modifier.fillMaxWidth().fillMaxHeight()) // last child: takes the rest of the frame
    }
}

/**
 * The last dispatch intervals as a bar chart against the target. Fixed slots fill from the right, so the newest
 * interval is always the rightmost bar. Green: on target. Amber: up to 15 % over. Red: further over.
 */
@Composable
private fun IntervalHistory(plc: RidePlc, modifier: Modifier) {
    val gaps = plc.dispatchTimes.toList().zipWithNext { a, b -> b - a }.takeLast(Slots)
    val target = plc.dispatchTarget
    val tm = rememberTextMeasurer(cacheSize = 24)
    Column(modifier) {
        Text("LAST $Slots INTERVALS  (newest at right)", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Hmi.Dim, letterSpacing = 0.5.sp)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color.White, RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF9AA1A8), RoundedCornerShape(3.dp))) {
            val pad = 5.dp.toPx()
            val labelH = 11.dp.toPx() // room above the tallest bar for its value
            val base = size.height - pad
            val top = maxOf(target * 1.3, (gaps.maxOrNull() ?: 0.0) * 1.05)
            val h = base - pad - labelH
            fun y(v: Double) = (base - h * (v / top)).toFloat()
            // Baseline and a faint mid gridline.
            drawLine(Color(0xFF9AA1A8), Offset(pad, base), Offset(size.width - pad, base), 1.5f)
            drawLine(Color(0xFFE6E9EC), Offset(pad, y(top / 2)), Offset(size.width - pad, y(top / 2)), 1f)
            val bw = (size.width - 2 * pad) / Slots
            val valStyle = TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text)
            gaps.forEachIndexed { k, g ->
                val slot = Slots - gaps.size + k
                val x = pad + slot * bw
                val c = when {
                    g <= target -> Hmi.Ok
                    g <= target * 1.15 -> Amber
                    else -> Hmi.Bad
                }
                drawRect(c, Offset(x + bw * 0.2f, y(g)), Size(bw * 0.6f, base - y(g)))
                val l = tm.measure(mss(g), valStyle)
                drawText(l, topLeft = Offset(x + (bw - l.size.width) / 2, y(g) - l.size.height))
            }
            // Target line, labelled at the left so it never sits under the newest bars.
            val ty = y(target)
            drawLine(Color(0xFF15191D), Offset(pad, ty), Offset(size.width - pad, ty), 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
            val l = tm.measure("TARGET ${mss(target)}", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White))
            drawRoundRect(Color(0xFF15191D), Offset(pad, ty - l.size.height / 2f - 1f), Size(l.size.width + 6f, l.size.height + 2f), CornerRadius(3f))
            drawText(l, topLeft = Offset(pad + 3f, ty - l.size.height / 2f))
            if (gaps.isEmpty()) {
                val m = tm.measure("No dispatch intervals yet", TextStyle(fontSize = 9.sp, color = Hmi.Dim))
                drawText(m, topLeft = Offset((size.width - m.size.width) / 2, base - h / 3 - m.size.height))
            }
        }
    }
}

private const val Slots = 12

/** Seconds as m:ss, the format used for every dispatch time on the HMI. */
internal fun mss(sec: Double): String = (sec + 0.5).toInt().let { "%d:%02d".format(it / 60, it % 60) }

/** Compact labelled value tile; [accent] colours the value. */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier, accent: Color? = null) {
    Column(modifier.background(Color.White, RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF9AA1A8), RoundedCornerShape(3.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Hmi.Dim, letterSpacing = 0.5.sp)
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = accent ?: Hmi.Text)
    }
}

// ---------------------------------------------------------------- operator stands

@Composable
private fun OperatorStands(plc: RidePlc, modifier: Modifier) {
    val inp = plc.inputs
    HmiFrame("OPERATOR STANDS", modifier) {
        // Column captions, then one row per stand.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(StandName))
            listOf("E-STOP" to 1f, "FOOT SW" to 1f, "READY" to 1.5f).forEach { (t, w) ->
                Text(t, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Hmi.Dim, letterSpacing = 0.5.sp, modifier = Modifier.weight(w))
            }
        }
        StandRow("LOAD", inp.loadStandEstop, inp.loadFootSwitch, { inp.loadFootSwitch = it }, plc.loadReady)
        StandRow("UNLOAD", inp.unloadStandEstop, inp.unloadFootSwitch, { inp.unloadFootSwitch = it }, plc.unloadReady)
        StandRow("CONSOLE", inp.estopPressed, null, null, null)
        HmiLabel("Tap FOOT SW to step on / off a stand.", 9.sp, color = Hmi.Dim)
    }
}

private val StandName = 52.dp

/** One stand: E-stop state, foot switch (tap to step on/off), ready lamp. */
@Composable
private fun StandRow(name: String, estop: Boolean, foot: Boolean?, setFoot: ((Boolean) -> Unit)?, ready: Boolean?) {
    Row(Modifier.fillMaxWidth().background(Color(0x14000000), RoundedCornerShape(3.dp)).padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Hmi.Text, modifier = Modifier.width(StandName - 4.dp))
        Pill(if (estop) "STOP" else "OK", if (estop) (if (flasher()) Hmi.Bad else Color(0xFF7A1010)) else Hmi.Ok, Modifier.weight(1f))
        if (foot != null && setFoot != null) Pill(if (foot) "ON" else "OFF", if (foot) Hmi.Ok else Hmi.Off, Modifier.weight(1f)) { setFoot(!foot) }
        else Spacer(Modifier.weight(1f))
        if (ready != null) Pill(if (ready) "READY" else "—", if (ready) Hmi.Info else Hmi.Off, Modifier.weight(1.5f))
        else Spacer(Modifier.weight(1.5f))
    }
}

// ---------------------------------------------------------------- dispatch status + permissives

@Composable
private fun DispatchBanner(plc: RidePlc) {
    val blink = flasher()
    val (text, bg) = when {
        plc.dispatchArmed -> "DISPATCH ARMED" to (if (blink) Hmi.Warn else Color(0xFF8A6400))
        plc.dispatchReady -> "READY TO DISPATCH" to (if (blink) Hmi.Ok else Color(0xFF156B31))
        else -> "NOT READY" to Color(0xFF6E767E)
    }
    val missing = plc.permissives.filter { !it.ok }
    val sub = when {
        plc.dispatchArmed -> "AWAITING COURSE CLEAR"
        plc.dispatchReady -> "ALL PERMISSIVES MADE · TWO-HAND DISPATCH"
        missing.isEmpty() -> ""
        else -> "AWAITING: " + missing[0].label.uppercase() + if (missing.size > 1) "  (+${missing.size - 1})" else ""
    }
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(3.dp)).border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The checklist in two columns, unmade permissives in bold so the eye lands on them. */
@Composable
private fun PermissivesFrame(plc: RidePlc, modifier: Modifier) {
    val list = plc.permissives
    val made = list.count { it.ok }
    HmiFrame("DISPATCH PERMISSIVES  ($made / ${list.size})", modifier) {
        val half = (list.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (col in listOf(list.take(half), list.drop(half))) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    col.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Indicator(p.ok, Hmi.Ok, Hmi.Bad, 11)
                            Spacer(Modifier.width(5.dp))
                            Text(p.label, fontSize = 11.sp, fontWeight = if (p.ok) FontWeight.Normal else FontWeight.Bold,
                                color = if (p.ok) Hmi.Dim else Hmi.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
