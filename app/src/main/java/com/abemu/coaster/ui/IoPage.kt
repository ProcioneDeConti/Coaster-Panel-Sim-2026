package com.abemu.coaster.ui

import com.abemu.coaster.sim.Units

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.AccessLevel
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.IoMap
import com.abemu.coaster.sim.IoModule
import com.abemu.coaster.sim.ModuleKind
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import kotlin.math.abs

private val ForceAmber = Color(0xFFFFB000)

@Composable
fun IoPage(vm: SimViewModel) {
    vm.frame.longValue
    val plc = vm.plc
    val mods = remember(plc) { IoMap.build(plc) }
    var rack by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiButton("RACK", active = rack, activeColor = Hmi.Info) { rack = true }
            HmiButton("TAG LIST", active = !rack, activeColor = Hmi.Info) { rack = false }
            Box(Modifier.weight(1f))
            if (plc.forces.any) {
                HmiLabel("${plc.forces.all.size} force(s) installed  ", 11.sp, bold = true, color = Color(0xFF9A6A00))
                HmiButton("REMOVE ALL FORCES", enabled = vm.access >= AccessLevel.MAINTENANCE, active = true, activeColor = Hmi.Warn) {
                    plc.removeAllForces()
                }
            }
        }
        if (rack) RackView(vm, plc, mods) else TagList(plc, mods, Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- rack

@Composable
private fun RackView(vm: SimViewModel, plc: RidePlc, mods: List<IoModule>) {
    var sel by remember(plc) { mutableIntStateOf(1) }
    val (chassis, slots) = IoMap.chassisFor(mods.size)
    val tm = rememberTextMeasurer()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HmiFrame("$chassis CHASSIS · $slots SLOTS · 1756-PA75 POWER SUPPLY") {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
                // Sheet-metal chassis: top rail with slot numbers, the modules, bottom rail with the catalog plate.
                Column(
                    Modifier.width(IntrinsicSize.Max)
                        .background(ChassisMetal, RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0xFF2E3338), RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp),
                ) {
                    ChassisRail(slots, top = true, chassis)
                    Row(horizontalArrangement = Arrangement.spacedBy(SlotGap)) {
                        PowerSupplyFace(tm)
                        for (s in 0 until slots) {
                            val m = mods.firstOrNull { it.slot == s }
                            if (m == null) SlotFiller(tm) else ModuleFace(tm, plc, m, sel == s) { sel = s }
                        }
                    }
                    ChassisRail(slots, top = false, chassis)
                }
            }
        }
        mods.firstOrNull { it.slot == sel }?.let { ModuleDetail(vm, plc, it) }
    }
}

private val SlotGap = 3.dp
private val ChassisMetal = Brush.verticalGradient(listOf(Color(0xFFA9AFB5), Color(0xFF8A9198), Color(0xFF7B828A), Color(0xFF959CA3)))

/** Top rail carries the slot numbers over each slot; the bottom rail the chassis plate. Both have mounting holes. */
@Composable
private fun ChassisRail(slots: Int, top: Boolean, chassis: String) {
    Box(Modifier.fillMaxWidth().height(18.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val u = 1.dp.toPx()
            for (x in listOf(-4 * u, size.width + 4 * u)) {
                drawRoundRect(Color(0xFF2A2E33), Offset(x - 2.5f * u, size.height / 2 - 3.5f * u), Size(5 * u, 7 * u), CornerRadius(2.5f * u))
            }
            val y = if (top) u else size.height - u
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), u * 0.6f)
        }
        if (top) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(SlotGap)) {
                Box(Modifier.width(PsWidth))
                for (s in 0 until slots) {
                    Box(Modifier.width(SlotWidth), contentAlignment = Alignment.Center) {
                        Box(Modifier.background(Color(0xFF23272B), RoundedCornerShape(2.dp)).padding(horizontal = 5.dp)) {
                            Text("$s", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⏚", color = Color(0xFF23272B), fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.background(Color(0xFF23272B), RoundedCornerShape(2.dp)).padding(horizontal = 6.dp)) {
                    Text("$chassis · SERIES C", color = Color(0xFFD6DADE), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text("CONTROLLOGIX CHASSIS", color = Color(0xFF3A4046), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun safetyModule(m: IoModule) = m.kind == ModuleKind.SAFETY_IN || m.kind == ModuleKind.SAFETY_OUT

@Composable
private fun ModuleDetail(vm: SimViewModel, plc: RidePlc, m: IoModule) {
    val canForce = vm.access >= AccessLevel.MAINTENANCE
    HmiFrame("SLOT ${m.slot} · ${m.catalog} · ${m.desc}", Modifier.fillMaxWidth()) {
        when (m.kind) {
            ModuleKind.CPU -> {
                ValueBox("Controller", "GuardLogix 5580 · Remote Run")
                ValueBox("Scans", plc.scanCount.toString())
                ValueBox("I/O forces", if (plc.forces.any) "ENABLED · ${plc.forces.all.size} installed" else "none installed")
                plc.forces.all.forEach { (t, v) -> HmiLabel("  F  $t = ${if (v) 1 else 0}", 11.sp, color = Color(0xFF9A6A00)) }
                ValueBox("Safety", "LOCKED · ${plc.safety.signature} · outputs ${if (plc.safety.outputsOn) "ON" else "OFF"}")
            }
            ModuleKind.ANALOG -> {
                val p = plc.plant
                ValueBox("Ch0 Main air (psi)", "%.1f".format(p.airPsi))
                if (plc.profile.type == ProfileType.LIFT) ValueBox("Ch1 Lift speed (m/s)", "%.2f".format(p.chainSpeed))
                p.trains.forEach { t -> ValueBox("Train ${t.id} speed / pos", "%5.1f mph / %5.0f ft".format(Units.mph(abs(t.v)), Units.ft(t.s))) }
            }
            else -> {
                if (safetyModule(m)) HmiLabel("Safety I/O – read only. Safety tags cannot be forced while the safety task is locked.", 10.sp, color = Hmi.Dim)
                else if (!canForce) HmiLabel("🔒 Log in as MAINTENANCE to install forces.", 10.sp, color = Hmi.Bad)
                else HmiLabel("⚠ Forces override the field and the program. A forced input hides the real device from all interlocks.", 10.sp, color = Color(0xFF9A6A00))
                MonitorTags(plc, m, canForce)
            }
        }
    }
}

// ---------------------------------------------------------------- monitor tags grid

private val GridLine = Color(0xFFD3D8DD)
private val ForcedCell = Color(0xFFFFE3A0)

/** Programming-software style "Monitor Tags" grid for one module's points. */
@Composable
private fun MonitorTags(plc: RidePlc, m: IoModule, canForce: Boolean) {
    val forcible = !safetyModule(m)
    val out = m.kind == ModuleKind.OUTPUT || m.kind == ModuleKind.SAFETY_OUT
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFF8C939A))) {
        Row(
            Modifier.fillMaxWidth().height(20.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFFF4F6F8), Color(0xFFD9DEE3))))
                .drawBehind { drawLine(Color(0xFF9AA2AA), Offset(0f, size.height), Offset(size.width, size.height), 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridCell("Name", Modifier.weight(1.3f), header = true)
            GridCell("Value", Modifier.width(46.dp), header = true, end = true)
            GridCell("Force Mask", Modifier.width(66.dp), header = true)
            GridCell("Style", Modifier.width(56.dp), header = true)
            GridCell("Data Type", Modifier.width(60.dp), header = true)
            GridCell("Address", Modifier.weight(1.2f), header = true)
            if (forcible) GridCell("Force", Modifier.width(112.dp), header = true)
        }
        LazyColumn(Modifier.fillMaxWidth().height(170.dp).background(Color.White)) {
            itemsIndexed(m.points) { k, p ->
                val forced = plc.forces[p.tag]
                val v = p.value(plc)
                Row(
                    Modifier.fillMaxWidth().height(22.dp).background(if (k % 2 == 0) Color.White else Color(0xFFF4F6F8))
                        .drawBehind { drawLine(GridLine, Offset(0f, size.height), Offset(size.width, size.height), 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1.3f).fillMaxHeight().gridRight().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Indicator(v, if (out) Color(0xFFE0A800) else Hmi.Ok, Color(0xFFC9CED3), 8)
                        Text(" ${p.tag}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text, maxLines = 1)
                    }
                    GridCell(if (v) "1" else "0", Modifier.width(46.dp).background(if (forced != null) ForcedCell else Color.Transparent),
                        end = true, bold = forced != null)
                    GridCell(forced?.let { if (it) "1" else "0" } ?: ".", Modifier.width(66.dp), mono = true)
                    GridCell("Decimal", Modifier.width(56.dp))
                    GridCell("BOOL", Modifier.width(60.dp))
                    GridCell(m.address(k), Modifier.weight(1.2f), mono = true, dim = true)
                    if (forcible) Row(Modifier.width(112.dp).padding(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (p.forcible) {
                            HmiButton("1", enabled = canForce, active = forced == true, activeColor = Hmi.Warn, fontSize = 9.sp, hPad = 8.dp) { plc.force(p.tag, true) }
                            HmiButton("0", enabled = canForce, active = forced == false, activeColor = Hmi.Warn, fontSize = 9.sp, hPad = 8.dp) { plc.force(p.tag, false) }
                            HmiButton("✕", enabled = canForce && forced != null, fontSize = 9.sp, hPad = 8.dp) { plc.force(p.tag, null) }
                        }
                    }
                }
            }
        }
        // Tabs along the bottom, as in the programming software.
        Row(Modifier.fillMaxWidth().background(Color(0xFFE3E7EB)).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Color.White).border(1.dp, Color(0xFF9AA2AA)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Monitor Tags", fontSize = 9.sp, color = Hmi.Text, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) { Text("Edit Tags", fontSize = 9.sp, color = Hmi.Dim) }
            Spacer(Modifier.weight(1f))
            Text("Scope: RideController  ·  ${m.points.size} tags  ", fontSize = 9.sp, color = Hmi.Dim)
        }
    }
}

private fun Modifier.gridRight() = drawBehind { drawLine(GridLine, Offset(size.width, 0f), Offset(size.width, size.height), 1f) }

@Composable
private fun GridCell(
    text: String, modifier: Modifier, header: Boolean = false, end: Boolean = false,
    mono: Boolean = false, dim: Boolean = false, bold: Boolean = false,
) {
    Box(modifier.fillMaxHeight().gridRight().padding(horizontal = 4.dp), contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text, maxLines = 1, fontSize = if (header) 9.5.sp else 10.sp,
            fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            color = if (header) Color(0xFF2B3238) else if (dim) Hmi.Dim else Hmi.Text,
        )
    }
}

// ---------------------------------------------------------------- tag list

private data class Io(val addr: String, val tag: String, val on: Boolean, val forced: Boolean)

@Composable
private fun TagList(plc: RidePlc, mods: List<IoModule>, modifier: Modifier) {
    fun rows(filter: (IoModule) -> Boolean) = mods.filter(filter).flatMap { m ->
        m.points.mapIndexed { k, p -> Io(m.address(k), p.tag, p.value(plc), plc.forces[p.tag] != null) }
    }
    val consoleSlot = mods.first { it.kind == ModuleKind.INPUT }.slot
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IoColumn("DIGITAL INPUTS – CONSOLE", rows { it.slot == consoleSlot }, Modifier.weight(1f))
        IoColumn("DIGITAL INPUTS – FIELD", rows { it.kind == ModuleKind.INPUT && it.slot != consoleSlot }, Modifier.weight(1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IoColumn("DIGITAL OUTPUTS", rows { it.kind == ModuleKind.OUTPUT }, Modifier.weight(1f))
            IoColumn("SAFETY I/O", rows { safetyModule(it) }, Modifier.weight(0.8f))
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
                    Text(io.tag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text, modifier = Modifier.weight(1f))
                    if (io.forced) Text("F", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A6A00))
                }
            }
        }
    }
}
