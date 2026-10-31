package com.abemu.coaster.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.abemu.coaster.R
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity

/** The physical operator console: hardwired devices wired to PLC input cards. */
@Composable
fun OperatorConsole(vm: SimViewModel, modifier: Modifier = Modifier) {
    vm.frame.longValue // recompose each update
    val plc = vm.plc
    val inp = plc.inputs
    val blink = flasher()
    val powered = plc.powered
    val hasFault = plc.alarms.anyActive(Severity.ESTOP) || plc.alarms.anyActive(Severity.CSTOP)

    Column(
        modifier
            .background(Brush.verticalGradient(if (Night.on) listOf(Color(0xFF14171A), Color(0xFF0A0B0D)) else listOf(Color(0xFF3A3F45), Color(0xFF26292D))))
            .brushedMetal()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OPERATOR CONSOLE  ·  OC-1", color = Color(0xFFD0D6DD), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("120 VAC / 24 VDC", color = Color(0xFF8A929C), fontSize = 10.sp)
            }
            // Easter egg: the real ride's logo slapped on the panel like a sticker.
            stickerFor(plc.profile.spec.id)?.let { CoasterSticker(it) }
        }

        // --- Power / mode / E-Stop ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            KeySwitch(
                "MODE SELECT", KeyMode.entries.map { it.label }, inp.mode.ordinal,
                onSelect = { inp.mode = KeyMode.entries[it] },
            )
            PanelSection("RIDE POWER") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PushButton("POWER\nON", Lens.White, powered, { inp.powerOn = it }, guarded = true)
                    PushButton("POWER\nOFF", Lens.Black, false, { inp.powerOff = it }, illuminated = false)
                }
            }
            EStopButton(inp.estopPressed, { inp.estopPressed = it })
        }

        // --- Status lamps ---
        PanelSection("STATUS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PilotLight("FAULT", Lens.Red, powered && hasFault && (blink || plc.alarms.anyUnacked.not()))
                PilotLight("AIR\nPRESSURE OK", Lens.Green, plc.plant.airPsi >= 75)
                if (plc.profile.type == ProfileType.LIFT)
                    PilotLight("LIFT\nRUNNING", Lens.Amber, plc.plant.chainSpeed > 0.1)
                else
                    PilotLight("LSM\nARMED", Lens.Amber, plc.launchState == LaunchState.CHARGING && blink ||
                        plc.launchState == LaunchState.FIRING)
                PilotLight("RIDE\nRUNNING", Lens.Green, plc.state == RideState.RUNNING ||
                    (plc.state == RideState.STARTING && blink))
                PilotLight("BLOCK\nCLEAR", Lens.White, powered && plc.nextClear(0))
                PilotLight("TRACK CLEAR\nWARNING", Lens.Amber, plc.trackClearActive && blink)
            }
        }

        // --- Ride control ---
        PanelSection("RIDE CONTROL", accent = SectionRed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val pw = 70.dp
                PushButton("RIDE\nSTART", Lens.Green,
                    plc.state == RideState.RUNNING || (plc.state == RideState.STOPPED && inp.mode == KeyMode.AUTO && !hasFault && blink),
                    { inp.rideStart = it }, size = 62.dp, plateWidth = pw, guarded = true)
                PushButton("CONTROLLED\nSTOP", Lens.Red, plc.state == RideState.CSTOP && blink,
                    { inp.controlledStop = it }, size = 62.dp, plateWidth = pw)
                // Divider, then the maintenance / critical-function group on a red field.
                Box(Modifier.width(2.dp).height(96.dp).background(Color(0xFF15171A)))
                Row(Modifier.weight(1f).background(SectionRed.copy(alpha = if (Night.on) 0.3f else 0.55f), RoundedCornerShape(8.dp))
                    .border(1.dp, SectionRed, RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                PushButton("FAULT\nRESET", Lens.Blue, powered && plc.resetNeeded && blink,
                    { inp.faultReset = it }, plateWidth = pw)
                PushButton("ALARM\nSILENCE", Lens.Amber, plc.hornActive && blink,
                    { inp.silence = it }, plateWidth = pw)
                if (plc.profile.type == ProfileType.LIFT)
                    PushButton("LIFT JOG\n(HOLD)", Lens.Black, false, { inp.liftJog = it }, illuminated = false, plateWidth = pw)
                else
                    PushButton("MANUAL\nLAUNCH", Lens.Black, false, { inp.manualRelease[1] = it }, illuminated = false, plateWidth = pw)
                PushButton("STATION\nRELEASE", Lens.Black, false, { inp.manualRelease[0] = it }, illuminated = false, plateWidth = pw)
                }
            }
        }

        // --- Station ---
        val unlocked = plc.plant.restraintUnlockCmd
        PanelSection("STATION", accent = SectionAmber) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Lamps flash while an action is in progress and go solid once it is confirmed.
                val plant = plc.plant
                val locking = powered && !unlocked && plc.trainInStation && !plc.allRowsLocked
                val locked = powered && !unlocked && plc.allRowsLocked
                val gatesOpening = plant.gatesOpenCmd && plant.gatePos < 0.97
                val gatesClosing = powered && !plant.gatesOpenCmd && !plant.gateClosedSensor
                PushButton("RESTRAINTS\nOPEN", Lens.Amber, unlocked, { inp.restraintOpen = it })
                PushButton("RESTRAINTS\nLOCK", Lens.Green, locked || (locking && blink),
                    { inp.restraintClose = it })
                PushButton("GATES\nOPEN", Lens.Amber, (plant.gatesOpenCmd && !gatesOpening) || (gatesOpening && blink),
                    { inp.gatesOpen = it })
                PushButton("GATES\nCLOSE", Lens.Green, (powered && plant.gateClosedSensor && !plant.gatesOpenCmd) ||
                    (gatesClosing && blink), { inp.gatesClose = it })
            }
        }

        // --- Dispatch (two-hand) ---
        // Engraved legends only: ride status and mode messages belong on the HMI, not the hardware panel.
        PanelSection("DISPATCH", accent = SectionGreen) {
            val ready = plc.dispatchReady
            // Held dispatch (waiting for the course block): the lens flashes yellow until the train releases.
            val held = plc.dispatchArmed
            val lens = if (held) Lens.Yellow else Lens.Green
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PushButton("DISPATCH", lens, (ready || held) && blink || plc.releaseLatch[0], { inp.dispatchL = it }, size = 80.dp, plateWidth = 96.dp, guarded = true)
                // The remote stands sit between the dispatch buttons to keep the console short.
                Row(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OperatorStand("LOAD STAND", inp.loadStandEstop, { inp.loadStandEstop = it }, plc.loadReady) { inp.loadReady = it }
                    OperatorStand("UNLOAD STAND", inp.unloadStandEstop, { inp.unloadStandEstop = it }, plc.unloadReady) { inp.unloadReady = it }
                }
                PushButton("DISPATCH", lens, (ready || held) && blink || plc.releaseLatch[0], { inp.dispatchR = it }, size = 80.dp, plateWidth = 96.dp, guarded = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LegendPlate("TWO-HAND DISPATCH – PRESS BOTH TOGETHER", 260.dp)
            }
        }
        ConsoleRadio(vm)
        Spacer(Modifier.height(4.dp))
    }
}


/** Remote operator stand: its own E-Stop plus that side's READY pushbutton. */
@Composable
private fun OperatorStand(
    name: String,
    estop: Boolean,
    onEstop: (Boolean) -> Unit,
    ready: Boolean,
    onReady: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .background(if (Night.on) Color(0xFF15181B) else Color(0xFF2F3338), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF6A7178), RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = Color(0xFFB8C0CA), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EStopButton(estop, onEstop, size = 48.dp, label = "E-STOP", plateWidth = 64.dp)
            PushButton("READY", Lens.White, ready, onReady, size = 42.dp, plateWidth = 56.dp)
        }
    }
}

/** Logo of the real ride a profile is modelled on, if we have one. */
private fun stickerFor(rideId: String): Int? = when (rideId) {
    "millennium" -> R.drawable.sticker_millennium
    "magnum" -> R.drawable.sticker_magnum
    "gemini" -> R.drawable.sticker_gemini
    "maverick" -> R.drawable.sticker_maverick
    "tt2" -> R.drawable.sticker_tt2
    else -> null
}

/**
 * A vinyl sticker of the logo, stuck on slightly crooked over the console title: the logo printed on a white
 * rounded card with a glossy laminate, a soft shadow on the plate, and the bottom-right corner peeling up.
 */
@Composable
private fun CoasterSticker(res: Int) {
    val painter = painterResource(res)
    val logoW = 88.dp
    val logoH = logoW * (painter.intrinsicSize.height / painter.intrinsicSize.width).coerceIn(0.25f, 1f)
    val pad = 5.dp
    Canvas(Modifier.offset(x = 0.dp, y = 6.dp).rotate(-7f).size(logoW + pad * 2, logoH + pad * 2)) {
        val W = size.width
        val H = size.height
        val corner = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
        val peel = minOf(W, H) * 0.2f
        // Outline with the peeled corner folded away along the line from (W, H - peel) to (W - peel, H).
        val stuck = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(W, 0f); lineTo(W, H - peel); lineTo(W - peel, H); lineTo(0f, H); close()
        }
        // Soft shadow of the stuck part on the plate.
        for ((k, spread) in listOf(1.5f, 3f, 4.5f).withIndex()) {
            val o = spread.dp.toPx()
            translate(1.5.dp.toPx(), 2.5.dp.toPx()) {
                clipPath(stuck) {
                    drawRoundRect(Color.Black.copy(alpha = 0.12f - k * 0.03f), Offset(-o / 2, -o / 2),
                        androidx.compose.ui.geometry.Size(W + o, H + o), corner)
                }
            }
        }
        clipPath(stuck) {
            drawRoundRect(Color(0xFFFBFBF8), Offset.Zero, size, corner)
            translate(pad.toPx(), pad.toPx()) {
                with(painter) { draw(androidx.compose.ui.geometry.Size(logoW.toPx(), logoH.toPx())) }
            }
            // Laminate gloss, and a faint edge so the card reads against light logos.
            drawRect(Brush.linearGradient(
                0f to Color.White.copy(alpha = 0f), 0.28f to Color.White.copy(alpha = 0.4f), 0.4f to Color.White.copy(alpha = 0.06f),
                0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.1f),
                start = Offset(0f, 0f), end = Offset(W, H)))
            drawRoundRect(Color.Black.copy(alpha = 0.15f), Offset.Zero, size, corner, style = Stroke(1.dp.toPx()))
        }
        // The fold: the lifted corner flipped over the crease shows the sticker's paler backing,
        // casting a shadow across the face below it.
        val a = Offset(W, H - peel)
        val b = Offset(W - peel, H)
        val tip = Offset(W - peel * 0.92f, H - peel * 0.92f)
        val flap = androidx.compose.ui.graphics.Path().apply {
            moveTo(a.x, a.y)
            quadraticTo(W - peel * 0.35f, H - peel * 0.75f, tip.x, tip.y)
            quadraticTo(W - peel * 0.75f, H - peel * 0.35f, b.x, b.y)
            close()
        }
        translate(-2.dp.toPx(), -2.dp.toPx()) { drawPath(flap, Color.Black.copy(alpha = 0.22f)) }
        drawPath(flap, Brush.linearGradient(listOf(Color(0xFFD9D5CA), Color(0xFFF4F1EA), Color(0xFFCFCABD)),
            start = Offset(W - peel / 2, H - peel / 2), end = tip))
        drawLine(Color.Black.copy(alpha = 0.18f), a, b, 0.8.dp.toPx())
    }
}

/**
 * Lightly brushed metal finish for the console plate: fine horizontal grain streaks of varying length and
 * brightness. Rendered once into a bitmap per size (and day/night), so the per-frame redraw is one image.
 */
private fun Modifier.brushedMetal() = drawWithCache {
    val night = Night.on
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    val bmp = androidx.compose.ui.graphics.ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = androidx.compose.ui.graphics.Paint()
    val rnd = kotlin.random.Random(7)
    var y = 0f
    while (y < h) {
        // A few streaks per row; light and dark ones so the grain reads without changing the overall tone.
        repeat(3) {
            val x0 = rnd.nextFloat() * w * 1.2f - w * 0.2f
            val len = w * (0.15f + rnd.nextFloat() * 0.6f)
            val light = rnd.nextBoolean()
            val a = (0.02f + rnd.nextFloat() * 0.05f) * if (night) 0.4f else 1f
            paint.color = (if (light) Color.White else Color.Black).copy(alpha = a)
            paint.strokeWidth = 1f
            canvas.drawLine(Offset(x0, y), Offset(x0 + len, y), paint)
        }
        y += 1.5f
    }
    onDrawBehind {
        drawImage(bmp)
        // Soft sheen band across the upper part of the plate.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (night) 0.01f else 0.035f), Color.Transparent),
            0f, size.height * 0.5f))
    }
}
