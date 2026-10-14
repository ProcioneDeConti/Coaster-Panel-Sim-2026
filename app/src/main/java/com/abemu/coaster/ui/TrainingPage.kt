package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.Drill
import com.abemu.coaster.sim.DrillKind
import com.abemu.coaster.sim.Scenarios

val DrillColor = Color(0xFF6A3FB5)

fun clock(s: Double) = "%d:%02d".format(s.toInt() / 60, s.toInt() % 60)

@Composable
fun TrainingPage(vm: SimViewModel) {
    vm.frame.longValue
    val d = vm.drill
    when {
        d == null -> ScenarioPicker(vm)
        d.finished -> DrillResultView(vm, d)
        else -> DrillView(vm, d)
    }
}

/** Slim strip shown under the alarm banner on every page while a drill runs. */
@Composable
fun DrillBanner(vm: SimViewModel, onClick: () -> Unit) {
    vm.frame.longValue
    val d = vm.drill ?: return
    if (d.finished) return
    val next = d.nextStep?.let { d.steps[it].text } ?: "—"
    Row(
        Modifier.fillMaxWidth().background(DrillColor).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DRILL  ${d.scenario.title.uppercase()}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("   ${clock(d.t)} / par ${clock(d.scenario.parSec)}", color = Color(0xFFE0D4F5), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("   NEXT: $next", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("score ${d.score}", color = Color(0xFFE0D4F5), fontSize = 11.sp)
    }
}

@Composable
private fun ScenarioPicker(vm: SimViewModel) {
    vm.frame.longValue
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(2.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DrillKind.entries.forEach { kind ->
                HmiFrame(kind.label) {
                    Scenarios.all.filter { it.kind == kind }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { sc ->
                                Column(
                                    Modifier.weight(1f).background(Color.White).border(1.dp, Color(0xFF8C939A)).padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        HmiLabel(sc.title, 13.sp, bold = true, modifier = Modifier.weight(1f))
                                        HmiLabel("${sc.type.label} · par ${clock(sc.parSec)}", 10.sp, color = Hmi.Dim)
                                    }
                                    HmiLabel(sc.briefing, 10.sp, color = Hmi.Dim)
                                    HmiButton("START DRILL", Modifier.fillMaxWidth(), active = true, activeColor = DrillColor) { vm.startDrill(sc) }
                                }
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        HmiFrame("RESULTS THIS SESSION", Modifier.weight(1f).fillMaxHeight()) {
            HmiLabel("Starting a drill rebuilds the ride and runs it to the start point. The drill begins the moment the screen returns.",
                10.sp, color = Hmi.Dim)
            if (vm.drillResults.isEmpty()) HmiLabel("No drills run yet.", 11.sp, color = Hmi.Dim)
            LazyColumn {
                items(vm.drillResults.toList()) { r ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        GradeChip(r.grade)
                        HmiLabel("  ${r.title}", 11.sp, modifier = Modifier.weight(1f))
                        HmiLabel("${r.score}  ${clock(r.seconds)}", 11.sp, color = Hmi.Dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeChip(grade: String) {
    val c = when (grade) { "A" -> Hmi.Ok; "B" -> Hmi.Info; "C" -> Hmi.Warn; else -> Hmi.Bad }
    Box(Modifier.width(64.dp).background(c).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(grade, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DrillView(vm: SimViewModel, d: Drill) {
    vm.frame.longValue
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("${d.scenario.kind.label} – ${d.scenario.title.uppercase()}") {
                HmiLabel(d.scenario.briefing, 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ValueBox("Time", "${clock(d.t)} / par ${clock(d.scenario.parSec)}", Modifier.weight(1f))
                    ValueBox("Score", d.score.toString(), Modifier.weight(0.6f))
                }
            }
            HmiFrame("CHECKLIST – complete in order") {
                d.steps.forEachIndexed { k, s ->
                    val done = d.done(k)
                    val next = d.nextStep == k
                    Row(
                        Modifier.fillMaxWidth().background(if (next) DrillColor.copy(alpha = 0.15f) else Color.Transparent).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (done) "✓" else if (next) "▶" else "○", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (done) Hmi.Ok else if (next) DrillColor else Hmi.Dim, modifier = Modifier.width(22.dp))
                        HmiLabel("${k + 1}. ${s.text}", 12.sp, bold = next, color = if (done) Hmi.Dim else Hmi.Text, modifier = Modifier.weight(1f))
                        d.doneTime(k)?.let { HmiLabel(clock(it), 10.sp, color = Hmi.Dim) }
                        if (d.canSignOff(k)) HmiButton("SIGN OFF", activeColor = DrillColor, active = true, fontSize = 10.sp) { d.signOff(k) }
                    }
                }
            }
            HmiButton("ABORT DRILL", Modifier.fillMaxWidth(), activeColor = Hmi.Bad, active = true) { vm.abortDrill() }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiFrame("FIELD WORK ORDERS", Modifier.weight(1.4f)) {
                HmiLabel("Send a technician. Unnecessary work orders cost points.", 10.sp, color = Hmi.Dim)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(d.workOrders) { r ->
                        val doneWo = r.id in d.repaired
                        HmiButton((if (doneWo) "✓ " else "") + r.label, Modifier.fillMaxWidth(), enabled = !doneWo, fontSize = 10.sp) { d.repair(r) }
                    }
                }
            }
            PenaltyList(vm, d, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PenaltyList(vm: SimViewModel, d: Drill, modifier: Modifier) {
    vm.frame.longValue
    HmiFrame("PENALTIES (−${d.penalties.sumOf { it.points } + d.timePenalty})", modifier) {
        if (d.penalties.isEmpty() && d.timePenalty == 0) HmiLabel("None so far.", 11.sp, color = Hmi.Dim)
        LazyColumn {
            items(d.penalties.toList()) { p ->
                Row {
                    HmiLabel(clock(p.t), 10.sp, color = Hmi.Dim, modifier = Modifier.width(40.dp))
                    HmiLabel("−${p.points}  ${p.text}", 11.sp, color = Hmi.Bad)
                }
            }
            if (d.timePenalty > 0) item { HmiLabel("−${d.timePenalty}  Over par time", 11.sp, color = Hmi.Bad) }
        }
    }
}

@Composable
private fun DrillResultView(vm: SimViewModel, d: Drill) {
    vm.frame.longValue
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HmiFrame("DRILL RESULT – ${d.scenario.title.uppercase()}", Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(140.dp).background(when (d.grade) { "A" -> Hmi.Ok; "B" -> Hmi.Info; "C" -> Hmi.Warn; else -> Hmi.Bad }).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(d.grade, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.padding(start = 12.dp)) {
                    HmiLabel("Score ${d.score} / 100", 18.sp, bold = true)
                    HmiLabel("Time ${clock(d.t)} (par ${clock(d.scenario.parSec)})", 13.sp)
                    d.failed?.let { HmiLabel(it, 13.sp, bold = true, color = Hmi.Bad) }
                }
            }
            HmiLabel("CHECKLIST", 10.sp, bold = true, color = Hmi.Dim)
            d.steps.forEachIndexed { k, s ->
                HmiLabel("${if (d.done(k)) "✓" else "✗"}  ${s.text}" + (d.doneTime(k)?.let { "  (${clock(it)})" } ?: ""), 11.sp,
                    color = if (d.done(k)) Hmi.Text else Hmi.Bad)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HmiButton("RUN AGAIN", active = true, activeColor = DrillColor) { vm.dismissDrill(); vm.startDrill(d.scenario) }
                HmiButton("CLOSE") { vm.dismissDrill() }
            }
        }
        PenaltyList(vm, d, Modifier.weight(1f).fillMaxHeight())
    }
}
