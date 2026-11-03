package com.abemu.coaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.Branch
import com.abemu.coaster.sim.Coil
import com.abemu.coaster.sim.CoilType
import com.abemu.coaster.sim.Contact
import com.abemu.coaster.sim.Ladder
import com.abemu.coaster.sim.LadderEl
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Rung

@Composable
fun LogicPage(vm: SimViewModel) {
    var safetyTab by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiButton("LADDER (MainProgram)", active = !safetyTab, activeColor = Hmi.Info) { safetyTab = false }
            HmiButton("SAFETY TASK", active = safetyTab, activeColor = Hmi.Bad) { safetyTab = true }
        }
        if (safetyTab) SafetyView(vm) else LadderView(vm)
    }
}

// ---------------------------------------------------------------- ladder

private val ElW = 96.dp
private val ElH = 58.dp
private val TagH = 22.dp
private val SymH = 18.dp
/** Wire height within an element: centre of the symbol row. */
private val WireY = TagH + SymH / 2
private val Wire = Color(0xFF2B2F33)
private val Energized = Color(0xFF14A34A)

@Composable
private fun LadderView(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    // Permissives are only populated after the first powered scan, so rebuild when they appear.
    val rungs = remember(plc, plc.permissives.size) { Ladder.build(plc) }
    val routines = remember(rungs) { listOf("ALL") + rungs.map { it.routine }.distinct() }
    var routine by remember(plc) { mutableStateOf("ALL") }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        routines.forEach { r -> HmiButton(r, active = routine == r, activeColor = Hmi.Info, fontSize = 10.sp) { routine = r } }
        Box(Modifier.weight(1f))
        HmiLabel(if (plc.powered) "REM RUN" else "REM RUN · ride power off", 10.sp, bold = true, color = Hmi.Ok)
    }
    LazyColumn(Modifier.fillMaxSize().background(Color.White).border(1.dp, Color(0xFF8C939A))) {
        val shown = rungs.withIndex().filter { routine == "ALL" || it.value.routine == routine }
        itemsIndexed(shown) { _, (n, rung) -> RungView(n, rung) }
    }
}

@Composable
private fun RungView(n: Int, rung: Rung) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("  ${rung.routine} · ${rung.comment}", fontSize = 10.sp, color = Color(0xFF2E7D32), modifier = Modifier.padding(start = 30.dp))
        Row {
            Text("%03d".format(n), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Hmi.Dim,
                modifier = Modifier.width(30.dp).padding(top = WireY - 7.dp), textAlign = TextAlign.Center)
            BoxWithConstraints(Modifier.weight(1f)) {
                val minW = maxWidth
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).widthIn(min = minW)
                        .drawBehind {
                            drawLine(Wire, Offset(1f, 0f), Offset(1f, size.height), 3f)
                            drawLine(Wire, Offset(size.width - 1, 0f), Offset(size.width - 1, size.height), 3f)
                        },
                ) {
                    WireSeg(Modifier.width(8.dp))
                    rung.inputs.forEach { El(it) }
                    WireSeg(Modifier.weight(1f))
                    CoilView(rung.coil)
                    WireSeg(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun El(el: LadderEl) = when (el) {
    is Contact -> ContactView(el)
    is Branch -> BranchView(el)
}

@Composable
private fun WireSeg(modifier: Modifier) {
    Canvas(modifier.height(ElH)) {
        val y = WireY.toPx()
        drawLine(Wire, Offset(0f, y), Offset(size.width, y), 2f)
    }
}

@Composable
private fun TagText(tag: String) {
    // Zero-width spaces let long tags wrap at '_' / '.' rather than mid-word.
    Text(tag.replace("_", "_​").replace(".", ".​"), fontSize = 8.sp, lineHeight = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 2, color = Hmi.Text,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(TagH).padding(horizontal = 2.dp))
}

@Composable
private fun ContactView(c: Contact) {
    val on = c.passes
    Column(Modifier.width(ElW).height(ElH)) {
        TagText(c.tag)
        Canvas(Modifier.fillMaxWidth().height(SymH)) {
            val y = size.height / 2
            val cx = size.width / 2
            val g = 7.dp.toPx()
            val h = 7.dp.toPx()
            val col = if (on) Energized else Wire
            drawLine(Wire, Offset(0f, y), Offset(cx - g, y), 2f)
            drawLine(Wire, Offset(cx + g, y), Offset(size.width, y), 2f)
            if (on) drawRect(Energized.copy(alpha = 0.25f), Offset(cx - g, y - h), Size(2 * g, 2 * h))
            drawLine(col, Offset(cx - g, y - h), Offset(cx - g, y + h), 3f)
            drawLine(col, Offset(cx + g, y - h), Offset(cx + g, y + h), 3f)
            if (c.nc) drawLine(col, Offset(cx - g + 3, y + h - 2), Offset(cx + g - 3, y - h + 2), 2f)
        }
        Text(c.mnemonic, fontSize = 8.sp, color = Hmi.Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BranchView(b: Branch) {
    val legs = b.legs.size
    Column(
        Modifier.width(IntrinsicSize.Max).drawBehind {
            val y0 = WireY.toPx()
            val y1 = y0 + (legs - 1) * ElH.toPx()
            drawLine(Wire, Offset(1f, y0), Offset(1f, y1), 2f)
            drawLine(Wire, Offset(size.width - 1, y0), Offset(size.width - 1, y1), 2f)
        },
    ) {
        b.legs.forEach { leg ->
            Row(Modifier.fillMaxWidth()) {
                WireSeg(Modifier.width(6.dp))
                leg.forEach { El(it) }
                WireSeg(Modifier.weight(1f))
                WireSeg(Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun CoilView(c: Coil) {
    val on = c.value()
    Column(Modifier.width(ElW + 14.dp).height(ElH)) {
        TagText(c.tag)
        Canvas(Modifier.fillMaxWidth().height(SymH)) {
            val y = size.height / 2
            val cx = size.width / 2
            val r = 8.dp.toPx()
            val col = if (on) Energized else Wire
            drawLine(Wire, Offset(0f, y), Offset(cx - r, y), 2f)
            drawLine(Wire, Offset(cx + r, y), Offset(size.width, y), 2f)
            if (on) drawCircle(Energized.copy(alpha = 0.25f), r, Offset(cx, y))
            drawArc(col, 120f, 120f, false, Offset(cx - r, y - r), Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            drawArc(col, -60f, 120f, false, Offset(cx - r, y - r), Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        val letter = when (c.type) { CoilType.OTE -> "OTE"; CoilType.OTL -> "OTL (L)"; CoilType.OTU -> "OTU (U)" }
        Text(letter, fontSize = 8.sp, color = Hmi.Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- safety task

@Composable
private fun SafetyView(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    val s = plc.safety
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("GUARDLOGIX SAFETY TASK") {
                ValueBox("Safety state", "SAFETY LOCKED")
                ValueBox("Safety signature", s.signature)
                ValueBox("Safety task", if (s.ioFault) "I/O FAULT" else "RUNNING")
                Box(
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                        .background(if (s.outputsOn) Hmi.Ok else if (flasher()) Hmi.Bad else Color(0xFF7A1010))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (s.outputsOn) "SAFETY OUTPUTS ON" else "SAFETY OUTPUTS OFF – TRIPPED", color = Color.White, fontWeight = FontWeight.Bold)
                }
                HmiLabel(
                    when {
                        s.outputsOn -> "Brake valves powered, drives enabled."
                        !s.chainOk -> "Safety chain open – clear the cause, then FAULT RESET."
                        plc.state == RideState.ESTOP || !plc.powered -> "Stop requested by the standard task."
                        else -> "Chain healthy – press FAULT RESET to re-enable."
                    }, 11.sp, color = Hmi.Dim,
                )
            }
            HmiFrame("SAFETY OUTPUTS (1756-OBV8S)") {
                SafetyRow("SO0  Brake valve power", s.outputsOn)
                SafetyRow(if (plc.profile.type == ProfileType.LIFT) "SO1  Lift VFD safe torque off" else "SO1  LSM safe torque off", s.outputsOn)
                SafetyRow("SO2  MSR1 enable", s.outputsOn)
                HmiLabel("Brakes are spring-applied: with brake valve power off they close regardless of the standard task, " +
                    "and forced brake solenoids have no effect.", 10.sp, color = Hmi.Dim)
            }
        }
        HmiFrame("DUAL-CHANNEL E-STOP INPUTS (1756-IB16S)", Modifier.weight(1.2f).fillMaxHeight()) {
            Row {
                listOf("STATION" to 1.6f, "CH A" to 0.6f, "CH B" to 0.6f, "STATUS" to 1.4f).forEach { (h, w) ->
                    HmiLabel(h, 10.sp, bold = true, color = Hmi.Dim, modifier = Modifier.weight(w))
                }
            }
            s.stations.forEachIndexed { k, name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel(name.replace('_', ' '), 11.sp, modifier = Modifier.weight(1.6f))
                    Box(Modifier.weight(0.6f)) { Indicator(s.chA[k], Hmi.Ok, Hmi.Bad) }
                    Box(Modifier.weight(0.6f)) { Indicator(s.chB[k], Hmi.Ok, Hmi.Bad) }
                    val st = when {
                        s.discrepancy[k] -> "DISCREPANCY"
                        s.chA[k] != s.chB[k] -> "MISMATCH…"
                        !s.chA[k] -> "OPEN"
                        else -> "OK"
                    }
                    HmiLabel(st, 11.sp, bold = st != "OK", color = if (st == "OK") Hmi.Ok else Hmi.Bad, modifier = Modifier.weight(1.4f))
                }
            }
            HmiLabel(
                "Each E-Stop has two contacts wired to separate safety inputs. If they disagree for more than 0.5 s " +
                    "the safety task latches a discrepancy fault; it clears only after both channels open together. " +
                    "Safety tags cannot be forced while the safety task is locked.", 10.sp, color = Hmi.Dim,
            )
            HmiLabel("STANDARD TASK VIEW", 10.sp, bold = true, color = Hmi.Dim)
            SafetyRow("ESTOP_CHAIN_OK (standard inputs)", plc.estopPbOk && plc.loadEstopOk && plc.unloadEstopOk && plc.remoteEstopOk)
            SafetyRow("Standard task stopped (E-STOP)", plc.state == RideState.ESTOP)
        }
    }
}

@Composable
private fun SafetyRow(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Indicator(on, Hmi.Ok, Hmi.Off)
        HmiLabel("  $label", 11.sp)
    }
}
