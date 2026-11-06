package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel

/** Bezel button that cycles the screen layout. */
@Composable
fun LayoutChip(vm: SimViewModel) {
    Box(
        Modifier.background(Color(0xFF3A3F45), RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF8A939C), RoundedCornerShape(3.dp))
            .clickable { Haptics.tick(); vm.layout = vm.layout.next() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("⊞ ${vm.layout.label}", color = Color(0xFFD5DAE0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Stand-alone live map display: plan view, ride state and block status. */
@Composable
fun MapPane(vm: SimViewModel, modifier: Modifier = Modifier) {
    vm.frame.longValue
    val plc = vm.plc
    Column(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF2E3238), Color(0xFF1A1C20)))).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE MAP", color = Color(0xFFD5DAE0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            LayoutChip(vm)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            AboutChip(vm)
        }
        Row(
            Modifier.fillMaxWidth().background(Hmi.Header).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(plc.profile.name.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.weight(1f))
            Box(Modifier.background(stateColor(plc.state), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(plc.state.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        val top = plc.alarms.firstOut ?: plc.alarms.active.firstOrNull()
        Text(
            top?.let { "${it.severity.label}  ${it.text}" } ?: plc.statusText,
            color = if (top != null) Color.White else Color(0xFFAFB8C1), fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(top?.let { sevColor(it.severity) } ?: Color(0xFF2A3036)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        PlanDiagram(plc, Modifier.fillMaxWidth().weight(1f).border(1.dp, Color.Black))
        HmiFrame("BLOCKS", Modifier.fillMaxWidth()) {
            plc.profile.blocks.forEach { b ->
                val id = plc.occ[b.index]
                val brk = plc.plant.brakes[b.index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hold = plc.holdT[b.index]
                    Indicator(id != -1, if (hold > 0) Hmi.Warn else Hmi.Bad, Hmi.Ok, 10)
                    HmiLabel(" ${b.tag}", 11.sp, bold = true, modifier = Modifier.width(34.dp))
                    HmiLabel(b.name + if (hold > 0) "  · HOLD ${hold.toInt()} s" else "", 10.sp,
                        color = if (hold > 0) Color(0xFF8A5A00) else Hmi.Dim, modifier = Modifier.weight(1f))
                    HmiLabel(if (id != -1) "T$id" + plc.pending[b.index].let { f -> if (f != -1) "+T$f" else "" } else "—", 11.sp, bold = true, modifier = Modifier.width(48.dp))
                    if (b.stopPos != null) Indicator(brk.isOpen, Hmi.Ok, Hmi.Bad, 10) else Box(Modifier.width(10.dp))
                }
            }
        }
        if (vm.webMapOn) {
            Text("🌐 " + (vm.mapServer.urls().firstOrNull() ?: "web map on port ${vm.mapServer.port}"),
                color = Color(0xFF9CC8F5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Instructor-page row: web map server switch and its address. */
@Composable
fun WebMapControls(vm: SimViewModel) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        HmiLabel("Web map", 11.sp, modifier = Modifier.width(90.dp))
        HmiButton(if (vm.webMapOn) "ON" else "OFF", active = vm.webMapOn, activeColor = Hmi.Info) { vm.setWebMap(!vm.webMapOn) }
        Column {
            when {
                vm.mapServer.error != null -> HmiLabel("Could not start: ${vm.mapServer.error}", 10.sp, color = Hmi.Bad)
                vm.webMapOn -> {
                    vm.mapServer.urls().forEach { HmiLabel(it, 11.sp, bold = true, color = Hmi.Info) }
                    HmiLabel("Open in a browser on the same Wi-Fi network.", 10.sp, color = Hmi.Dim)
                }
                else -> HmiLabel("Serves a live track map to phones and PCs on the network.", 10.sp, color = Hmi.Dim)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        HmiLabel("Remote instr.", 11.sp, modifier = Modifier.width(90.dp))
        HmiButton(if (vm.remoteInstructorOn) "ON" else "OFF", enabled = vm.webMapOn, active = vm.remoteInstructorOn,
            activeColor = Hmi.Warn) { vm.setRemoteInstructor(!vm.remoteInstructorOn) }
        Column {
            if (vm.webMapOn && vm.remoteInstructorOn) {
                vm.mapServer.urls().forEach { HmiLabel("$it/instructor", 11.sp, bold = true, color = Hmi.Info) }
                HmiLabel("Faults, weather and drills from a browser. Sign in with the instructor PIN.", 10.sp, color = Hmi.Dim)
            } else HmiLabel("Lets an instructor run faults, weather and drills from a laptop or phone (needs web map on).", 10.sp, color = Hmi.Dim)
        }
    }
  }
}
