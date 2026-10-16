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
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HmiFrame("$chassis CHASSIS · $slots SLOTS · 1756-PA75 POWER SUPPLY") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).background(Color(0xFF6E757C)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                PowerSupply()
                for (s in 0 until slots) {
                    val m = mods.firstOrNull { it.slot == s }
                    if (m == null) EmptySlot(s) else ModuleCard(plc, m, sel == s) { sel = s }
                }
            }
        }
        mods.firstOrNull { it.slot == sel }?.let { ModuleDetail(vm, plc, it) }
    }
}

@Composable
private fun PowerSupply() {
    Column(
        Modifier.width(54.dp).height(300.dp).background(Color(0xFF3A3F45), RoundedCornerShape(2.dp)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PA75", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(Hmi.Ok, CircleShape))
        Text("PWR", color = Color(0xFFB5BCC5), fontSize = 7.sp)
    }
}

@Composable
private fun EmptySlot(slot: Int) {
    Column(
        Modifier.width(48.dp).height(300.dp).background(Color(0xFF50565C), RoundedCornerShape(2.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$slot", color = Color(0xFFB5BCC5), fontSize = 9.sp)
        Text("EMPTY", color = Color(0xFF8A9096), fontSize = 7.sp, modifier = Modifier.padding(top = 130.dp))
    }
}

private fun safetyModule(m: IoModule) = m.kind == ModuleKind.SAFETY_IN || m.kind == ModuleKind.SAFETY_OUT

@Composable
private fun ModuleCard(plc: RidePlc, m: IoModule, selected: Boolean, onClick: () -> Unit) {
    val safety = safetyModule(m) || m.kind == ModuleKind.CPU
    val faulted = m.kind == ModuleKind.SAFETY_IN && plc.safety.ioFault
    Column(
        Modifier.width(62.dp).height(300.dp)
            .background(Color(0xFF2B2F33), RoundedCornerShape(2.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Hmi.Info else Color.Black, RoundedCornerShape(2.dp))
            .clickable { Haptics.tick(); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Safety modules carry a red stripe, like the real hardware.
        Box(Modifier.fillMaxWidth().height(5.dp).background(if (safety) Color(0xFFC62828) else Color(0xFF51606F)))
        Text("${m.slot}", color = Color(0xFFB5BCC5), fontSize = 9.sp)
        Text(m.catalog.removePrefix("1756-"), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (m.kind == ModuleKind.CPU) {
            CpuLeds(plc)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            val okColor = if (faulted) (if (flasher()) Hmi.Bad else Color(0xFF4A1010)) else Hmi.Ok
            Box(Modifier.size(6.dp).background(okColor, CircleShape))
            Text(" OK", color = Color(0xFFB5BCC5), fontSize = 7.sp)
        }
        if (m.kind == ModuleKind.ANALOG) {
            Text("8 CH\nANALOG", color = Color(0xFFB5BCC5), fontSize = 7.sp, textAlign = TextAlign.Center)
            return@Column
        }
        // Point LEDs in two columns: 0–7 and 8–15.
        val per = if (m.kind == ModuleKind.SAFETY_OUT) 8 else 16
        val half = if (per <= 8) per else 8
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0 until 2) {
                if (col == 1 && per <= 8) break
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (k in col * half until (col + 1) * half) {
                        val p = m.points.getOrNull(k)
                        val on = p?.value(plc) == true
                        val forced = p != null && plc.forces[p.tag] != null
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%2d".format(k), color = Color(0xFF8A9096), fontSize = 6.sp, fontFamily = FontFamily.Monospace)
                            Box(
                                Modifier.padding(start = 2.dp).size(width = 9.dp, height = 7.dp)
                                    .background(if (on) (if (m.kind == ModuleKind.OUTPUT || m.kind == ModuleKind.SAFETY_OUT) Color(0xFFFFC400) else Color(0xFF39E26A)) else Color(0xFF1A1C1F))
                                    .border(1.dp, if (forced) ForceAmber else Color(0xFF111111)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CpuLeds(plc: RidePlc) {
    val display = when {
        plc.safety.ioFault -> "I/O FLT"
        plc.forces.any -> "FRC"
        else -> "Rem Run"
    }
    Box(Modifier.padding(vertical = 4.dp).background(Color.Black).padding(horizontal = 3.dp, vertical = 2.dp)) {
        Text(display, color = Color(0xFF7CFF8A), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CpuLed("RUN", Hmi.Ok)
        CpuLed("FORCE", if (plc.forces.any) ForceAmber else null)
        CpuLed("SD", null)
        CpuLed("OK", Hmi.Ok)
        CpuLed("SAFE", Hmi.Ok)
        CpuLed("I/O", if (plc.safety.ioFault) (if (flasher()) Hmi.Bad else null) else Hmi.Ok)
    }
}

@Composable
private fun CpuLed(label: String, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color ?: Color(0xFF1A1C1F), CircleShape))
        Text(" $label", color = Color(0xFFB5BCC5), fontSize = 7.sp)
    }
}

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
                LazyColumn(Modifier.fillMaxWidth().height(170.dp).background(Color.White).border(1.dp, Color(0xFF8C939A))) {
                    items(m.points.withIndex().toList()) { (k, p) ->
                        val forced = plc.forces[p.tag]
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            Indicator(p.value(plc), Hmi.Ok, Color(0xFF5E666E), 10)
                            Text(" ${m.address(k)}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Hmi.Dim, modifier = Modifier.width(130.dp))
                            Text(p.tag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text, modifier = Modifier.weight(1f))
                            if (forced != null) Text("F=${if (forced) 1 else 0} ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A6A00))
                            if (p.forcible && !safetyModule(m)) {
                                HmiButton("1", enabled = canForce, active = forced == true, activeColor = Hmi.Warn, fontSize = 9.sp) { plc.force(p.tag, true) }
                                HmiButton("0", enabled = canForce, active = forced == false, activeColor = Hmi.Warn, fontSize = 9.sp) { plc.force(p.tag, false) }
                                HmiButton("✕", enabled = canForce && forced != null, fontSize = 9.sp) { plc.force(p.tag, null) }
                            }
                        }
                    }
                }
            }
        }
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
