package com.abemu.coaster.ui

import com.abemu.coaster.sim.Units

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.BlockKind
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.TransferState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ================================================================ STATION

/** Top-down view of the swing-launch switch track: a sliding table carrying a curved piece (station line to
 *  launch track) and a straight piece (spike line to launch track). */
@Composable
internal fun SwitchTrackFrame(vm: SimViewModel) {
    vm.frame.longValue // recompose each update (the PLC object itself never changes)
    val plc = vm.plc
    val p = plc.profile
    val plant = plc.plant
    val pos = plant.switchPos.toFloat()
    val atStation = plc.switchStationLs
    val atSpike = plc.switchSpikeLs
    val title = when {
        atStation -> "SWITCH TRACK – STATION LINE"
        atSpike -> "SWITCH TRACK – SPIKE LINE"
        else -> "SWITCH TRACK – MOVING"
    }
    HmiFrame(title) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val yS = size.height * 0.22f
            val yL = size.height * 0.55f
            val gap = yL - yS
            val xA = size.width * 0.36f
            val xB = size.width * 0.62f
            val rail = Color(0xFF5F6870)
            // Fixed track: station and spike lines to the left, launch track to the right
            drawLine(rail, Offset(0f, yS), Offset(xA, yS), 6f)
            drawLine(rail, Offset(0f, yL), Offset(xA, yL), 6f)
            drawLine(Color(0xFF1E88E5), Offset(xB, yL), Offset(size.width, yL), 6f)
            // Table on its slide rails
            val off = gap * pos
            drawRect(Color(0xFF3A3F45), Offset(xA - 4, yS - 14), Size(xB - xA + 8, 2 * gap + 28), style = Stroke(1.5f))
            drawRect(Color(0xFFB0B7BE), Offset(xA, yS - 10 - off), Size(xB - xA, gap + 20 + gap))
            val good = Color(0xFF2EBD59)
            val moving = Color(0xFFFFB300)
            val curved = Path().apply {
                moveTo(xA, yS - off)
                cubicTo(xA + (xB - xA) / 3, yS - off, xA + 2 * (xB - xA) / 3, yL - off, xB, yL - off)
            }
            drawPath(curved, if (atStation) good else if (atSpike) Color(0xFF7A8189) else moving, style = Stroke(6f))
            drawLine(if (atSpike) good else if (atStation) Color(0xFF7A8189) else moving,
                Offset(xA, yL + gap - off), Offset(xB, yL + gap - off), 6f)
            // Trains as cars, at one uniform scale (the table's length sets pixels per metre) so they move smoothly
            // on and off the table, and clipped to the frame so they slide in and out at the edges.
            val j = p.spikeJunction
            val k = ((xB - xA) / (p.switchEnd - j)).toFloat()
            val reach = (size.width / k + p.trainLength).toDouble() // metres either side worth drawing
            // Canvas point of track position [s], or [spike] metres up the spike line (the spike line is drawn level).
            fun place(s: Double, spike: Double): Offset = when {
                spike > 0 -> Offset(xA - spike.toFloat() * k, yL)
                p.inRange(s, j, p.switchEnd) -> {
                    val f = (p.ahead(j, s) / (p.switchEnd - j)).toFloat()
                    if (atStation) {
                        // Curved piece: the same cubic as drawn above; x is linear in f.
                        val u = 1 - f
                        Offset(xA + f * (xB - xA), yS * (u * u * u + 3 * u * u * f) + yL * (3 * u * f * f + f * f * f))
                    } else Offset(xA + f * (xB - xA), yL)
                }
                plant.signedDist(j, s) < 0 -> Offset(xA - p.ahead(s, j).toFloat() * k, yS)
                else -> Offset(xB + p.ahead(p.switchEnd, s).toFloat() * k, yL)
            }
            val carLen = p.trainLength / p.spec.cars
            clipRect {
                plant.trains.forEach { tr ->
                    val near = tr.spike > 0 || tr.tailOnSpike || abs(plant.signedDist(j, tr.s)) < reach
                    if (!near) return@forEach
                    val c = trainColor(p, tr.id)
                    val back = if (tr.tailOnSpike && tr.spike <= 0) p.ahead(j, tr.s) else 0.0
                    fun at(d: Double) = when {
                        tr.spike > 0 -> place(0.0, tr.spike + d)
                        tr.tailOnSpike && d > back -> place(0.0, d - back)
                        else -> place(p.wrap(tr.s - d), 0.0)
                    }
                    for (n in p.spec.cars - 1 downTo 0) {
                        val path = Path()
                        var d = n * carLen + 0.15
                        path.moveTo(at(d).x, at(d).y)
                        while (d < (n + 1) * carLen - 0.15) { d = minOf(d + 0.25, (n + 1) * carLen - 0.15); path.lineTo(at(d).x, at(d).y) }
                        drawPath(path, Color(0xFF101418), style = Stroke(16f, cap = StrokeCap.Butt))
                        drawPath(path, c, style = Stroke(12f, cap = StrokeCap.Butt))
                    }
                    drawCircle(Color.White, 4f, at(0.0))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Indicator(atStation, Hmi.Ok, Hmi.Off); HmiLabel("STATION LS", 10.sp)
            Indicator(atSpike, Hmi.Ok, Hmi.Off); HmiLabel("SPIKE LS", 10.sp)
            Indicator(plc.switchCmd, Hmi.Info, Hmi.Off); HmiLabel("CMD SPIKE", 10.sp)
            Indicator(plant.switchOccupied, Hmi.Warn, Hmi.Off); HmiLabel("TABLE OCCUPIED", 10.sp)
        }
    }
}

// ================================================================ ALARMS

@Composable
fun AlarmsPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    var showHistory by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) } // code to text
    val hist = remember(plc, plc.alarms.historyVersion) { plc.alarms.history.toList() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiButton("ACTIVE (${plc.alarms.active.size})", active = !showHistory, activeColor = Hmi.Info) { showHistory = false }
            HmiButton("HISTORY", active = showHistory, activeColor = Hmi.Info) { showHistory = true }
            Box(Modifier.weight(1f))
            HmiButton("ACK ALL") { plc.alarms.acknowledgeAll() }
            HmiHoldButton("RESET") { plc.inputs.faultReset = it }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF51606F)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            listOf("TIME" to 0.9f, "SEVERITY" to 0.9f, if (showHistory) "EVENT" to 0.8f else "STATE" to 0.8f, "MESSAGE" to 5f).forEach { (h, w) ->
                Text(h, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(w))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color.White).border(1.dp, Color(0xFF8C939A))) {
            if (showHistory) {
                items(hist) { e ->
                    AlarmRow(e.time, e.severity.label, sevColor(e.severity), e.event, e.text, selected?.first == e.code && selected?.second == e.text) { selected = e.code to e.text }
                }
            } else {
                val act = plc.alarms.active
                if (act.isEmpty()) item { HmiLabel("  No active alarms", 12.sp, color = Hmi.Dim, modifier = Modifier.padding(8.dp)) }
                items(act) { a ->
                    val state = when {
                        plc.alarms.isFirstOut(a) -> "1ST OUT"
                        a.condition && !a.acked -> "ACT/UNACK"
                        a.condition -> "ACTIVE"
                        !a.acked -> "RTN/UNACK"
                        else -> "RESET REQ"
                    }
                    AlarmRow(a.since, a.severity.label, sevColor(a.severity), state, a.text, (!a.acked && flasher()) || selected?.first == a.code) { selected = a.code to a.text }
                }
            }
        }
        // Diagnostics for the selected alarm
        val sel = selected
        val diag = sel?.let { com.abemu.coaster.sim.Diagnostics.forCode(it.first) }
        HmiFrame(if (sel == null) "DIAGNOSTICS – tap an alarm" else "DIAGNOSTICS – ${sel.second}", Modifier.fillMaxWidth()) {
            when {
                sel == null -> HmiLabel("Select an alarm above to see probable causes and corrective actions.", 11.sp, color = Hmi.Dim)
                diag == null -> HmiLabel("No diagnostic guidance for this event (informational).", 11.sp, color = Hmi.Dim)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        HmiLabel("PROBABLE CAUSE", 10.sp, bold = true, color = Hmi.Dim)
                        diag.causes.forEach { HmiLabel("• $it", 11.sp) }
                    }
                    Column(Modifier.weight(1f)) {
                        HmiLabel("CORRECTIVE ACTION", 10.sp, bold = true, color = Hmi.Dim)
                        diag.actions.forEachIndexed { i, t -> HmiLabel("${i + 1}. $t", 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(time: String, sev: String, color: Color, state: String, text: String, highlight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (highlight) color.copy(alpha = 0.25f) else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f), color = Hmi.Text)
        Row(Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color))
            Text(" $sev", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Hmi.Text)
        }
        Text(state, fontSize = 10.sp, modifier = Modifier.weight(0.8f), color = Hmi.Dim)
        Text(text, fontSize = 12.sp, modifier = Modifier.weight(5f), color = Hmi.Text)
    }
}

// ================================================================ MANUAL / MAINT

@Composable
fun MaintPage(vm: SimViewModel) {
    val maintAccess = vm.access >= com.abemu.coaster.AccessLevel.MAINTENANCE
    vm.frame.longValue
    val plc = vm.plc
    val inp = plc.inputs
    val m = inp.mode
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Live map so manual brake releases can be done while watching the trains.
        PlanDiagram(plc, Modifier.fillMaxWidth().weight(0.9f))
    Row(Modifier.fillMaxWidth().weight(1.1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("MANUAL BLOCK CONTROL  (key: MANUAL, ride STOPPED)") {
                val manual = m == KeyMode.MANUAL && plc.state == RideState.STOPPED
                HmiLabel(if (manual) "Hold RELEASE to open a block brake. Next block must be clear." else "Not available – select MANUAL and stop the ride.",
                    11.sp, color = if (manual) Hmi.Text else Hmi.Dim)
                plc.profile.blocks.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Indicator(plc.occ[b.index] != -1, Hmi.Bad, Hmi.Ok)
                        HmiLabel("  ${b.tag} ${b.name}", 11.sp, modifier = Modifier.weight(1f))
                        when (b.kind) {
                            BlockKind.LIFT -> HmiHoldButton("LIFT JOG", Modifier.width(130.dp), manual) { inp.liftJog = it }
                            BlockKind.LAUNCH -> HmiHoldButton("MANUAL LAUNCH", Modifier.width(130.dp), manual) { inp.manualRelease[b.index] = it }
                            else -> HmiHoldButton("RELEASE ${b.tag}", Modifier.width(130.dp), manual && plc.nextClear(b.index)) {
                                inp.manualRelease[b.index] = it
                            }
                        }
                    }
                }
            }
            HmiFrame("MAINTENANCE BRAKE CONTROL  (key: MAINT – INTERLOCKS BYPASSED)") {
                val maint = m == KeyMode.MAINT && plc.powered && !plc.eStopped && maintAccess
                if (!maintAccess) HmiLabel("🔒 Log in as MAINTENANCE to use these controls.", 11.sp, color = Hmi.Bad)
                plc.profile.blocks.filter { it.stopPos != null }.forEach { b ->
                    val brk = plc.plant.brakes[b.index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HmiLabel("${b.tag} brakes", 11.sp, modifier = Modifier.weight(1f))
                        Indicator(brk.closedProx, Hmi.Bad); HmiLabel(" CL  ", 10.sp)
                        Indicator(brk.openProx, Hmi.Ok); HmiLabel(" OP  ", 10.sp)
                        HmiButton(if (plc.maintBrakeOpen[b.index]) "CLOSE" else "OPEN", Modifier.width(80.dp), maint,
                            active = plc.maintBrakeOpen[b.index], activeColor = Hmi.Warn) {
                            plc.maintBrakeOpen[b.index] = !plc.maintBrakeOpen[b.index]
                        }
                    }
                }
                if (plc.profile.type == ProfileType.LIFT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HmiLabel("Lift chain (no crest interlock)", 11.sp, modifier = Modifier.weight(1f))
                        HmiHoldButton("JOG", Modifier.width(80.dp), maint) { inp.liftJog = it }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Air compressor", 11.sp, modifier = Modifier.weight(1f))
                    HmiButton(if (plc.plant.compressorEnabled) "ON" else "OFF", Modifier.width(80.dp),
                        enabled = maintAccess, active = plc.plant.compressorEnabled) { plc.plant.compressorEnabled = !plc.plant.compressorEnabled }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Block tracking", 11.sp, modifier = Modifier.weight(1f))
                    HmiButton("VERIFY / RESYNC", enabled = maint) {
                        plc.resyncTracking(); plc.alarms.info("TRK", "Block tracking re-synchronised by maintenance")
                    }
                }
            }
        }
        Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (plc.profile.type == ProfileType.LAUNCH) RollbackFrame(vm, maintAccess)
            HmiFrame("TRANSFER TRACK / TRAIN STORAGE  (${plc.profile.blocks[plc.profile.transferBlock].tag})") {
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val ts = plc.transferState
                    val shift = when (ts) {
                        TransferState.SHIFT_OUT -> 0.5f
                        TransferState.MOVE -> 1f
                        TransferState.SHIFT_BACK -> 0.5f
                        else -> 0f
                    }
                    val mainY = 30f
                    val storeY = 85f
                    drawLine(Color(0xFF455A64), Offset(0f, mainY), Offset(size.width, mainY), 6f)
                    drawLine(Color(0xFF455A64), Offset(size.width * 0.3f, storeY), Offset(size.width, storeY), 6f)
                    val tableY = mainY + (storeY - mainY) * shift
                    drawRect(Color(0xFF78909C), Offset(size.width * 0.05f, tableY - 10), Size(size.width * 0.22f, 20f))
                    for (i in 0 until plc.plant.stored) {
                        drawRect(Color(0xFF00897B), Offset(size.width * (0.4f + i * 0.2f), storeY - 9), Size(size.width * 0.16f, 18f))
                    }
                    if (plc.occ[plc.profile.transferBlock] != -1) drawRect(Color(0xFF1565C0), Offset(size.width * 0.07f, tableY - 8), Size(size.width * 0.18f, 16f))
                }
                ValueBox("Transfer state", plc.transferState.label)
                ValueBox("Trains in storage", plc.plant.stored.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HmiButton("STORE TRAIN", Modifier.weight(1f), m == KeyMode.MAINT && maintAccess) { msg = plc.requestTransfer(true) }
                    HmiButton("RETRIEVE TRAIN", Modifier.weight(1f), m == KeyMode.MAINT && maintAccess) { msg = plc.requestTransfer(false) }
                }
                if (msg.isNotEmpty()) HmiLabel(msg, 11.sp, color = Hmi.Info)
            }
            HmiFrame("STATUS") {
                ValueBox("Ride state", plc.state.label)
                ValueBox("Mode", m.label)
                ValueBox("Main air", "${plc.plant.airPsi.toInt()} psi")
                HmiLabel(plc.statusText, 11.sp, color = Hmi.Info)
            }
        }
    }
    }
}

/** Launch rollback recovery: checklist, maintenance inspection sign-off and relaunch authorization. */
@Composable
private fun RollbackFrame(vm: SimViewModel, maintAccess: Boolean) {
    val plc = vm.plc
    var msg by remember { mutableStateOf("") }
    val active = plc.launchState == LaunchState.ROLLBACK || plc.relaunchHold
    HmiFrame("LAUNCH ROLLBACK RECOVERY" + if (plc.rollbacks > 0) "  (rollbacks: ${plc.rollbacks})" else "") {
        if (!active) {
            HmiLabel("No rollback. After a rollback: wait for the train to stop on the launch track, have maintenance inspect it " +
                "and sign off, FAULT RESET, then authorize the relaunch once the LSM has returned it to the launch position.",
                10.sp, color = Hmi.Dim)
        } else {
            plc.rollbackSteps.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Indicator(p.ok, Hmi.Ok, Hmi.Off, 12)
                    HmiLabel("  ${p.label}", 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HmiButton("SIGN OFF INSPECTION" + if (!maintAccess) " 🔒" else "", Modifier.weight(1f),
                    enabled = maintAccess && plc.launchState == LaunchState.ROLLBACK && !plc.rollbackInspected, fontSize = 10.sp) {
                    msg = plc.signOffRollbackInspection()
                }
                HmiButton("AUTHORIZE RELAUNCH", Modifier.weight(1f), enabled = plc.relaunchHold && !plc.relaunchArmed,
                    activeColor = Hmi.Warn, fontSize = 10.sp) { msg = plc.authorizeRelaunch() }
            }
            if (msg.isNotEmpty()) HmiLabel(msg, 11.sp, color = Hmi.Info)
        }
    }
}

// ================================================================ INSTRUCTOR / SIM

@Composable
fun SimPage(vm: SimViewModel, onOpenBuilder: () -> Unit, onOpenEditor: () -> Unit) {
    vm.frame.longValue
    val plc = vm.plc
    val f = plc.plant.faults
    val off = plc.state == RideState.POWER_OFF
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("RIDE CONFIGURATION  (ride power must be OFF)") {
                CoasterPicker(vm, off)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HmiButton("BUILD RIDE FROM OSM", Modifier.weight(1f), activeColor = Hmi.Info) { onOpenBuilder() }
                    HmiButton("OPEN TRACK EDITOR (DEV)", Modifier.weight(1f), activeColor = Hmi.Info) { onOpenEditor() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Trains", 11.sp, modifier = Modifier.width(90.dp))
                    (1..vm.plc.profile.maxTrains).forEach { n ->
                        HmiButton("$n", enabled = off, active = vm.trainCount == n, activeColor = Hmi.Info) { vm.reconfigure(trains = n) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Dispatch target", 11.sp, modifier = Modifier.width(90.dp))
                    // The rated interval (from the ride's rated capacity), or eased off for training.
                    listOf(1.0, 1.15, 1.3, 1.5).forEach { k ->
                        val sec = Math.round(plc.profile.ratedInterval * k)
                        HmiButton(if (k == 1.0) "RATED ${mss(sec.toDouble())}" else mss(sec.toDouble()), active = plc.targetScale == k, activeColor = Hmi.Info) { plc.targetScale = k }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Sim speed", 11.sp, modifier = Modifier.width(90.dp))
                    listOf(1.0, 2.0, 4.0).forEach { s ->
                        HmiButton("${s.toInt()}×", active = vm.timeScale == s, activeColor = Hmi.Info) { vm.timeScale = s }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Sound (all)", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton(if (vm.soundOn) "ON" else "OFF", active = vm.soundOn, activeColor = Hmi.Info) { vm.soundOn = !vm.soundOn }
                }
                WebMapControls(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Desk display", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton(if (vm.deskMode) "ON" else "OFF", active = vm.deskMode, activeColor = Hmi.Ok) { vm.switchDeskMode(!vm.deskMode) }
                    HmiButton("RANDOM FAULTS", active = vm.deskRandomFaults, activeColor = Hmi.Warn, fontSize = 10.sp) { vm.deskRandomFaults = !vm.deskRandomFaults }
                    HmiLabel("ride runs itself; maintenance fixes faults", 10.sp, color = Hmi.Dim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Debug tools", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton(if (vm.debugTools) "ON" else "OFF", active = vm.debugTools, activeColor = Hmi.Warn) {
                        vm.debugTools = !vm.debugTools
                        if (!vm.debugTools) SoundTest.allOff()
                    }
                    HmiLabel("sound test panel", 10.sp, color = Hmi.Dim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Haptics", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton(if (vm.hapticsOn) "ON" else "OFF", active = vm.hapticsOn, activeColor = Hmi.Info) { vm.hapticsOn = !vm.hapticsOn }
                }
                SoundKind.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { k ->
                            val en = vm.soundEnabled[k] != false
                            HmiButton((if (en) "■ " else "□ ") + k.label, Modifier.weight(1f), enabled = vm.soundOn,
                                active = en, activeColor = Hmi.Info, fontSize = 10.sp) { vm.soundEnabled[k] = !en }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Volume", 11.sp, modifier = Modifier.width(90.dp))
                    listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%").forEach { (v, l) ->
                        HmiButton(l, active = vm.volume == v, activeColor = Hmi.Info) { vm.volume = v }
                    }
                }
            }
            if (vm.debugTools) SoundTestFrame()
            HmiFrame("OPERATOR AIDS") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Dispatch", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton("TWO-HAND", active = !plc.inputs.singleButtonDispatch, activeColor = Hmi.Info) {
                        plc.inputs.singleButtonDispatch = false
                    }
                    HmiButton("SINGLE BUTTON (mouse)", active = plc.inputs.singleButtonDispatch, activeColor = Hmi.Warn) {
                        plc.inputs.singleButtonDispatch = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Restraint check", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton("REQUIRED", active = vm.restraintWalk, activeColor = Hmi.Info) { vm.restraintWalk = true }
                    HmiButton("OFF", active = !vm.restraintWalk, activeColor = Hmi.Warn) { vm.restraintWalk = false }
                    HmiLabel("tap each car on STATION before LOAD READY", 10.sp, color = Hmi.Dim)
                }
            }
            HmiFrame("FAULT INJECTION – SYSTEM") {
                FaultToggle("Safety circuit open (remote E-Stop)", f.safetyCircuit) { f.safetyCircuit = it }
                FaultToggle("Air leak (pressure decay)", f.airLeak) { f.airLeak = it }
                if (plc.profile.type == ProfileType.LIFT) {
                    FaultToggle("Lift VFD fault", f.liftVfd) { f.liftVfd = it }
                    FaultToggle("Lift motor overload trip", f.liftOverload) { f.liftOverload = it }
                } else {
                    FaultToggle("LSM drive fault", f.lsmFault) { f.lsmFault = it }
                    FaultToggle("Launch underspeed (causes rollback)", f.launchUnderspeed) { f.launchUnderspeed = it }
                    if (!plc.profile.spec.lsmLift) FaultToggle("LSM derated – marginal launch (rolls back in a headwind > ~22 mph)", f.lsmDerate) { f.lsmDerate = it }
                    if (plc.hasSwitch) FaultToggle("Switch track jammed", f.switchStuck) { f.switchStuck = it }
                }
                FaultToggle("Station gate stuck open", f.gateStuck) { f.gateStuck = it }
                FaultToggle("Safety I/O module connection fault", f.safetyIoFault) { f.safetyIoFault = it }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("E-Stop ch B welded", 11.sp, modifier = Modifier.weight(1f))
                    (listOf(-1) + plc.safety.stations.indices).forEach { k ->
                        HmiButton(if (k < 0) "–" else plc.safety.stations[k].take(4), active = f.estopWeldedB == k,
                            activeColor = if (k < 0) Hmi.Info else Hmi.Bad, fontSize = 9.sp) { f.estopWeldedB = k }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Restraint fault row", 11.sp, modifier = Modifier.weight(1f))
                    (listOf(-1) + (0 until plc.profile.rows)).forEach { r ->
                        HmiButton(if (r < 0) "–" else "${r + 1}", active = f.restraintRow == r,
                            activeColor = if (r < 0) Hmi.Info else Hmi.Bad) { f.restraintRow = r }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Seat restraint won't lock", 11.sp, modifier = Modifier.weight(1f))
                    HmiButton("–", active = f.restraintSeat < 0, activeColor = Hmi.Info) { f.restraintSeat = -1 }
                    HmiButton("RANDOM SEAT", active = f.restraintSeat >= 0, activeColor = Hmi.Bad, fontSize = 10.sp) {
                        f.restraintSeat = (0 until plc.profile.rows * plc.profile.spec.ridersPerCar).random()
                    }
                    if (f.restraintSeat >= 0) HmiLabel("car ${f.restraintSeat / plc.profile.spec.ridersPerCar + 1} seat ${f.restraintSeat % plc.profile.spec.ridersPerCar + 1}", 10.sp, color = Hmi.Dim)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("FAULT INJECTION – BLOCK DEVICES") {
                plc.profile.blocks.forEach { b ->
                    HmiLabel("${b.tag} ${b.name}", 11.sp, bold = true)
                    val items = buildList<Pair<String, Pair<Boolean, (Boolean) -> Unit>>> {
                        add("Prox stuck ON" to ((b.index in f.sensorStuckOn) to { on: Boolean ->
                            if (on) f.sensorStuckOn += b.index else f.sensorStuckOn -= b.index }))
                        add("Prox failed OFF" to ((b.index in f.sensorStuckOff) to { on: Boolean ->
                            if (on) f.sensorStuckOff += b.index else f.sensorStuckOff -= b.index }))
                        if (b.stopPos != null) {
                            val brk = plc.plant.brakes[b.index]
                            add("Brake stuck open" to (brk.stuckOpen to { on: Boolean -> brk.stuckOpen = on }))
                            add("Brake stuck closed" to (brk.stuckClosed to { on: Boolean -> brk.stuckClosed = on }))
                        }
                    }
                    items.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { (label, st) ->
                                HmiButton(label, Modifier.weight(1f), active = st.first, activeColor = Hmi.Bad) { st.second(!st.first) }
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
            HmiFrame("NOTES") {
                HmiLabel("Faults act on the simulated field devices. The PLC only sees them through its I/O, " +
                    "so diagnose them from alarms and the I/O screen as you would on a real ride. Clear the fault here, " +
                    "then press FAULT RESET.", 11.sp, color = Hmi.Dim)
            }
        }
    }
}

@Composable
private fun FaultToggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Indicator(on, Hmi.Bad)
        HmiLabel("  $label", 11.sp, modifier = Modifier.weight(1f))
        HmiButton(if (on) "CLEAR" else "INJECT", Modifier.width(80.dp), active = on, activeColor = Hmi.Bad) { set(!on) }
    }
}


/** Debug panel (shown with Debug tools ON): play each ride sound on demand. Held sounds toggle; one-shots play once. */
@Composable
private fun SoundTestFrame() {
    var tick by remember { mutableStateOf(0) } // recompose after a toggle (SoundTest isn't Compose state)
    tick
    HmiFrame("SOUND TEST (DEBUG)") {
        HmiLabel("Held sounds (tap to toggle) — uses the volume setting above", 10.sp, color = Hmi.Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple("TRACK CLEAR", { SoundTest.trackClear }, { v: Boolean -> SoundTest.trackClear = v }),
                Triple("FAULT HORN", { SoundTest.horn }, { v: Boolean -> SoundTest.horn = v }),
                Triple("LIFT MOTOR", { SoundTest.lift }, { v: Boolean -> SoundTest.lift = v }),
                Triple("ANTI-ROLLBACK", { SoundTest.clack }, { v: Boolean -> SoundTest.clack = v }),
                Triple("LSM WHINE", { SoundTest.lsm }, { v: Boolean -> SoundTest.lsm = v; if (v) SoundTest.lsmStartMs = System.currentTimeMillis() }),
            ).forEach { (label, get, set) ->
                HmiButton(label, Modifier.weight(1f), active = get(), activeColor = Hmi.Info, fontSize = 10.sp, maxLines = 2, hPad = 4.dp) {
                    set(!get()); tick++
                }
            }
        }
        HmiLabel("One-shots", 10.sp, color = Hmi.Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HmiButton("DISPATCH CHIME", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { SoundTest.audio?.dispatchChime() }
            HmiButton("LAUNCH BEEPS", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { SoundTest.audio?.launchWarning() }
            HmiButton("AIR HISS", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { SoundTest.audio?.hiss() }
            HmiButton("RESTRAINT LOCK", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { SoundTest.audio?.thump(locking = true) }
            HmiButton("RESTRAINT UNLOCK", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { SoundTest.audio?.thump(locking = false) }
            HmiButton("ALL OFF", Modifier.weight(1f), fontSize = 10.sp, maxLines = 2, hPad = 4.dp) {
                SoundTest.allOff()
                tick++
            }
        }
        RadioClipPicker()
    }
}

/** Debug: pick any recorded radio line and put it on the air through the console radio. */
@Composable
private fun RadioClipPicker() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val script = remember { loadRadio(context).values.toList() }
    var open by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(script.firstOrNull()) }
    HmiLabel("Radio lines (${script.size}) — plays on the console radio (sound must be on)", 10.sp, color = Hmi.Dim)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            HmiButton(sel?.let { "${it.clip}  ·  ${it.label}  ▾" } ?: "–", Modifier.fillMaxWidth(), fontSize = 10.sp) { open = true }
            androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
                script.forEach { l ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text("${l.clip}  ·  ${l.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(l.text, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        },
                        onClick = { sel = l; open = false; RadioState.preview.addLast(l.clip) },
                    )
                }
            }
        }
        HmiButton("PLAY", fontSize = 10.sp) { sel?.let { RadioState.preview.addLast(it.clip) } }
        HmiButton("NEXT ▸", fontSize = 10.sp) {
            val i = script.indexOf(sel) + 1
            sel = script.getOrNull(i % script.size)?.also { RadioState.preview.addLast(it.clip) }
        }
    }
}

@Composable
private fun CoasterPicker(vm: SimViewModel, enabled: Boolean) {
    val custom = com.abemu.coaster.sim.CustomRides.rides.map { it.spec }
    val groups = ProfileType.entries.map { t -> t.label.uppercase() to com.abemu.coaster.sim.CoasterLibrary.builtIn.filter { it.type == t } } +
        if (custom.isNotEmpty()) listOf("CUSTOM (OSM RIDE BUILDER)" to custom) else emptyList()
    groups.forEach { (title, specs) ->
        HmiLabel(title, 10.sp, bold = true, color = Hmi.Dim)
        specs.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    HmiButton(c.name + if (c.park != null) " ★" else "", Modifier.weight(1f), enabled = enabled,
                        active = vm.coasterId == c.id, activeColor = Hmi.Info, fontSize = 10.sp) { vm.reconfigure(id = c.id) }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
    val p = vm.plc.profile
    val facts = buildString {
        append(p.name)
        p.spec.park?.let { append(" – $it") }
        append(" · ${p.type.label}")
        if (p.type == ProfileType.LIFT) append(" · lift ${Units.ft(p.spec.height).toInt()} ft @ ${"%.0f".format(Units.mph(p.liftSpeed))} mph")
        else append(" · launch ${Units.mph(p.launchSpeed).toInt()} mph, top hat ${Units.ft(p.maxHeight).toInt()} ft")
        append(" · track ${p.length.toInt()} m · ${p.rows} cars × ${p.spec.ridersPerCar} riders")
    }
    HmiLabel(facts, 11.sp, bold = true)
    if (p.spec.note.isNotEmpty()) HmiLabel(p.spec.note, 10.sp, color = Hmi.Dim)
    if (!enabled) HmiLabel("Turn RIDE POWER OFF to change coaster or train count.", 10.sp, color = Hmi.Warn)
}
