package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.BlockKind
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.TransferState
import kotlin.math.abs

// ================================================================ STATION

@Composable
fun StationPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    val plant = plc.plant
    val inp = plc.inputs
    val st = plant.stationTrain()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("STATION – ${if (st != null) "TRAIN ${st.id} IN STATION" else "NO TRAIN"}") {
                // Train plan view: rows, restraints, gates
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val rows = plc.profile.rows
                    val margin = 20f
                    val carW = (size.width - margin * 2) / rows
                    val trainY = 40f
                    val trainH = 60f
                    drawRect(Color(0xFF9EA4AA), Offset(0f, trainY - 8), Size(size.width, trainH + 16))
                    for (r in 0 until rows) {
                        val x0 = margin + r * carW
                        if (st != null) {
                            drawRect(Color(0xFF1565C0), Offset(x0 + 3, trainY), Size(carW - 6, trainH))
                            val locked = plant.rowLockSensor(r)
                            drawRect(if (locked) Color(0xFF2EBD59) else Color(0xFFE53935),
                                Offset(x0 + 10, trainY + 12), Size(carW - 20, trainH - 24))
                        }
                    }
                    // Gates (load side, bottom)
                    val open = plant.gatePos
                    for (r in 0 until rows) {
                        val x0 = margin + r * carW
                        val gx = x0 + 6 + (carW - 12) * (open.toFloat() * 0.85f)
                        drawRect(Color(0xFF424242), Offset(x0 + 4, trainY + trainH + 22), Size(carW - 8, 4f))
                        drawRect(if (plant.gateClosedSensor) Color(0xFF2EBD59) else Color(0xFFFFB300),
                            Offset(gx, trainY + trainH + 14), Size(carW * 0.16f, 18f))
                    }
                    drawRect(Color(0xFF6D4C41), Offset(0f, 0f), Size(size.width, 18f)) // unload platform
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HmiLabel("Unload platform ▲", 10.sp, color = Hmi.Dim)
                    HmiLabel("Row lock:  ■ locked  ■ unlocked", 10.sp, color = Hmi.Dim)
                    HmiLabel("Load gates ▼", 10.sp, color = Hmi.Dim)
                }
            }
            HmiFrame("STATION CONTROLS (HMI)") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HmiHoldButton("RESTRAINTS\nOPEN", Modifier.weight(1f)) { inp.restraintOpen = it }
                    HmiHoldButton("RESTRAINTS\nLOCK", Modifier.weight(1f)) { inp.restraintClose = it }
                    HmiHoldButton("GATES\nOPEN", Modifier.weight(1f)) { inp.gatesOpen = it }
                    HmiHoldButton("GATES\nCLOSE", Modifier.weight(1f)) { inp.gatesClose = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DispatchTimer(plc, Modifier.weight(1.3f))
                HmiFrame("OPERATOR STANDS", Modifier.weight(1f)) {
                    StandStatus("Load stand", plc.inputs.loadStandEstop, plc.loadReady)
                    StandStatus("Unload stand", plc.inputs.unloadStandEstop, plc.unloadReady)
                    StandStatus("Console", plc.inputs.estopPressed, null)
                }
            }
        }
        HmiFrame("DISPATCH PERMISSIVES", Modifier.weight(1f).fillMaxHeight()) {
            plc.permissives.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Indicator(p.ok, Hmi.Ok, Hmi.Bad, 14)
                    HmiLabel("  ${p.label}", 12.sp)
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .background(if (plc.dispatchReady) (if (flasher()) Hmi.Ok else Color(0xFF156B31)) else Color(0xFF7A8189))
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (plc.dispatchReady) "READY TO DISPATCH" else "NOT READY", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ================================================================ ALARMS

@Composable
fun AlarmsPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    var showHistory by remember { mutableStateOf(false) }
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
                items(plc.alarms.history.toList()) { e ->
                    AlarmRow(e.time, e.severity.label, sevColor(e.severity), e.event, e.text, false)
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
                    AlarmRow(a.since, a.severity.label, sevColor(a.severity), state, a.text, !a.acked && flasher())
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(time: String, sev: String, color: Color, state: String, text: String, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (highlight) color.copy(alpha = 0.25f) else Color.Transparent)
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
    vm.frame.longValue
    val plc = vm.plc
    val inp = plc.inputs
    val m = inp.mode
    var msg by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                val maint = m == KeyMode.MAINT && plc.powered && !plc.eStopped
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
                        active = plc.plant.compressorEnabled) { plc.plant.compressorEnabled = !plc.plant.compressorEnabled }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Block tracking", 11.sp, modifier = Modifier.weight(1f))
                    HmiButton("VERIFY / RESYNC", enabled = maint) {
                        plc.resyncTracking(); plc.alarms.info("TRK", "Block tracking re-synchronised by maintenance")
                    }
                }
            }
        }
        Column(Modifier.weight(0.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    HmiButton("STORE TRAIN", Modifier.weight(1f), m == KeyMode.MAINT) { msg = plc.requestTransfer(true) }
                    HmiButton("RETRIEVE TRAIN", Modifier.weight(1f), m == KeyMode.MAINT) { msg = plc.requestTransfer(false) }
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

// ================================================================ I/O

private data class Io(val addr: String, val tag: String, val on: Boolean)

@Composable
fun IoPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    val p = plc.plant
    val i = plc.inputs
    val bl = plc.profile.blocks
    val console = listOf(
        "ESTOP_PB_NC" to !i.estopPressed, "KEY_AUTO" to (i.mode == KeyMode.AUTO), "KEY_MANUAL" to (i.mode == KeyMode.MANUAL),
        "KEY_MAINT" to (i.mode == KeyMode.MAINT), "PWR_ON_PB" to i.powerOn, "PWR_OFF_PB_NC" to !i.powerOff,
        "RIDE_START_PB" to i.rideStart, "CS_PB_NC" to !i.controlledStop, "FAULT_RST_PB" to i.faultReset,
        "SILENCE_PB" to i.silence, "DISPATCH_L_PB" to i.dispatchL, "DISPATCH_R_PB" to i.dispatchR,
        "RESTR_OPEN_PB" to i.restraintOpen, "RESTR_LOCK_PB" to i.restraintClose, "GATE_OPEN_PB" to i.gatesOpen,
        "GATE_CLOSE_PB" to i.gatesClose,
    ).mapIndexed { n, (t, v) -> Io("Local:1:I.Data.$n", t, v) }
    val field = buildList {
        bl.forEach { add("${it.tag}_PX_ENTRY" to p.entrySensor(it.index)) }
        bl.filter { it.stopPos != null }.forEach {
            add("${it.tag}_BRK_CLOSED_PX" to p.brakes[it.index].closedProx)
            add("${it.tag}_BRK_OPEN_PX" to p.brakes[it.index].openProx)
            add("${it.tag}_PX_STOP" to (p.trainAtStop(it.index) != null))
        }
        add("GATES_CLOSED_LS" to p.gateClosedSensor)
        add("LOAD_STAND_ESTOP_NC" to !i.loadStandEstop)
        add("UNLOAD_STAND_ESTOP_NC" to !i.unloadStandEstop)
        add("MSR1_SAFETY_OK" to (!p.faults.safetyCircuit && !i.anyEstop))
        add("AIR_PRESS_SW" to (p.airPsi >= 75))
        if (plc.profile.type == ProfileType.LIFT) {
            add("LIFT_CREST_PX" to p.bodyAt(plc.profile.liftCrestSensor))
            add("LIFT_VFD_READY" to !p.faults.liftVfd)
            add("LIFT_OL1_OK" to !p.faults.liftOverload)
        } else {
            add("LSM_EXIT_PX" to p.bodyAt(plc.profile.launchEnd))
            add("LSM_CREST_PX" to p.bodyAt(plc.profile.crestSensor))
            add("LSM_DRIVE_READY" to !p.faults.lsmFault)
        }
        for (r in 0 until plc.profile.rows) add("ROW${r + 1}_LOCKED_LS" to p.rowLockSensor(r))
    }.mapIndexed { n, (t, v) -> Io("Local:${2 + n / 16}:I.Data.${n % 16}", t, v) }
    val outputs = buildList {
        bl.filter { it.stopPos != null }.forEach { add("${it.tag}_BRK_SOL" to p.brakes[it.index].cmdOpen) }
        bl.forEach { add("${it.tag}_DRIVE_TIRES" to p.tiresCmd[it.index]) }
        if (plc.profile.type == ProfileType.LIFT) add("LIFT_VFD_RUN" to p.liftRunCmd)
        else { add("LSM_FIRE" to p.lsmFireCmd); add("LSM_POSITION" to p.lsmPositionCmd) }
        add("GATE_OPEN_SOL" to p.gatesOpenCmd)
        add("RESTR_UNLOCK_SOL" to p.restraintUnlockCmd)
        add("COMPRESSOR_RUN" to (p.compressorEnabled && p.airPsi < 110))
        add("HORN" to (plc.hornActive && flasher()))
        add("DISPATCH_LT" to plc.dispatchReady)
        add("FAULT_LT" to plc.alarms.active.isNotEmpty())
    }.mapIndexed { n, (t, v) -> Io("Local:${5 + n / 16}:O.Data.${n % 16}", t, v) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IoColumn("DIGITAL INPUTS – CONSOLE", console, Modifier.weight(1f))
        IoColumn("DIGITAL INPUTS – FIELD", field, Modifier.weight(1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IoColumn("DIGITAL OUTPUTS", outputs, Modifier.weight(1f))
            HmiFrame("ANALOG – Local:8:I") {
                ValueBox("Ch0 Main air (psi)", "%.1f".format(p.airPsi))
                if (plc.profile.type == ProfileType.LIFT) ValueBox("Ch1 Lift speed (m/s)", "%.2f".format(p.chainSpeed))
                p.trains.forEach { t -> ValueBox("Train ${t.id} speed / pos", "%5.1f / %5.0f m".format(abs(t.v), t.s)) }
            }
        }
    }
}

@Composable
private fun IoColumn(title: String, points: List<Io>, modifier: Modifier) {
    HmiFrame(title, modifier.fillMaxHeight()) {
        LazyColumn {
            items(points) { io ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Indicator(io.on, Hmi.Ok, Color(0xFF5E666E), 10)
                    Text(" ${io.addr}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Hmi.Dim, modifier = Modifier.width(118.dp))
                    Text(io.tag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text)
                }
            }
        }
    }
}

// ================================================================ INSTRUCTOR / SIM

@Composable
fun SimPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    val f = plc.plant.faults
    val off = plc.state == RideState.POWER_OFF
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("RIDE CONFIGURATION  (ride power must be OFF)") {
                CoasterPicker(vm, off)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Trains", 11.sp, modifier = Modifier.width(90.dp))
                    (1..3).forEach { n ->
                        HmiButton("$n", enabled = off, active = vm.trainCount == n, activeColor = Hmi.Info) { vm.reconfigure(trains = n) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Dispatch target", 11.sp, modifier = Modifier.width(90.dp))
                    listOf(30.0, 45.0, 60.0, 90.0).forEach { t ->
                        HmiButton("${t.toInt()} s", active = plc.dispatchTarget == t, activeColor = Hmi.Info) { plc.dispatchTarget = t }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Sim speed", 11.sp, modifier = Modifier.width(90.dp))
                    listOf(1.0, 2.0, 4.0).forEach { s ->
                        HmiButton("${s.toInt()}×", active = vm.timeScale == s, activeColor = Hmi.Info) { vm.timeScale = s }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Sound", 11.sp, modifier = Modifier.width(90.dp))
                    HmiButton(if (vm.soundOn) "ON" else "OFF", active = vm.soundOn, activeColor = Hmi.Info) { vm.soundOn = !vm.soundOn }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Volume", 11.sp, modifier = Modifier.width(90.dp))
                    listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%").forEach { (v, l) ->
                        HmiButton(l, active = vm.volume == v, activeColor = Hmi.Info) { vm.volume = v }
                    }
                }
            }
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
                }
                FaultToggle("Station gate stuck open", f.gateStuck) { f.gateStuck = it }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Restraint fault row", 11.sp, modifier = Modifier.weight(1f))
                    (listOf(-1) + (0 until plc.profile.rows)).forEach { r ->
                        HmiButton(if (r < 0) "–" else "${r + 1}", active = f.restraintRow == r,
                            activeColor = if (r < 0) Hmi.Info else Hmi.Bad) { f.restraintRow = r }
                    }
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


@Composable
private fun DispatchTimer(plc: com.abemu.coaster.sim.RidePlc, modifier: Modifier) {
    HmiFrame("DISPATCH TIMER  (target ${plc.dispatchTarget.toInt()} s)", modifier) {
        val since = plc.sinceDispatch
        val target = plc.dispatchTarget
        val color = when {
            since == null -> Hmi.Dim
            since <= target * 0.85 -> Hmi.Ok
            since <= target -> Hmi.Warn
            else -> Hmi.Bad
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                since?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "--:--",
                fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color,
            )
            HmiLabel("  since last dispatch", 10.sp, color = Hmi.Dim)
        }
        // Progress bar toward the target interval
        val frac = ((since ?: 0.0) / target).coerceIn(0.0, 1.5).toFloat() / 1.5f
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRect(Color(0xFF9AA1A8))
            drawRect(color, size = Size(size.width * frac, size.height))
            val tx = size.width / 1.5f
            drawLine(Color.Black, Offset(tx, 0f), Offset(tx, size.height), 2f)
        }
        ValueBox("Last interval", plc.lastInterval?.let { "%.0f s (%+.0f)".format(it, it - target) } ?: "—")
        ValueBox("Average (last 20)", plc.avgInterval?.let { "%.0f s".format(it) } ?: "—")
        val riders = plc.profile.ridersPerTrain
        ValueBox("Capacity at average", plc.avgInterval?.let { "${(3600 / it * riders).toInt()} riders/h" } ?: "—")
    }
}

@Composable
private fun StandStatus(name: String, estop: Boolean, ready: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HmiLabel(name, 11.sp, modifier = Modifier.weight(1f))
        Indicator(estop, if (flasher()) Hmi.Bad else Color(0xFF7A1010), Hmi.Ok)
        HmiLabel(if (estop) " E-STOP  " else " OK      ", 10.sp, bold = estop)
        if (ready != null) {
            Indicator(ready, Color.White, Hmi.Off)
            HmiLabel(" RDY", 10.sp)
        }
    }
}

@Composable
private fun CoasterPicker(vm: SimViewModel, enabled: Boolean) {
    val lib = com.abemu.coaster.sim.CoasterLibrary.all
    ProfileType.entries.forEach { type ->
        HmiLabel(type.label.uppercase(), 10.sp, bold = true, color = Hmi.Dim)
        lib.filter { it.type == type }.chunked(3).forEach { row ->
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
        if (p.type == ProfileType.LIFT) append(" · lift ${p.spec.height.toInt()} m @ ${p.liftSpeed} m/s")
        else append(" · launch ${p.launchSpeed.toInt()} m/s, top hat ${p.maxHeight.toInt()} m")
        append(" · track ${p.length.toInt()} m · ${p.rows} cars × ${p.spec.ridersPerCar} riders")
    }
    HmiLabel(facts, 11.sp, bold = true)
    if (p.spec.note.isNotEmpty()) HmiLabel(p.spec.note, 10.sp, color = Hmi.Dim)
    if (!enabled) HmiLabel("Turn RIDE POWER OFF to change coaster or train count.", 10.sp, color = Hmi.Warn)
}
