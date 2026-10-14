package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Hmi {
    val Screen = Color(0xFFD9DCDF)
    val Panel = Color(0xFFC4C8CC)
    val Header = Color(0xFF1E3550)
    val Text = Color(0xFF15191D)
    val Dim = Color(0xFF5E666E)
    val Ok = Color(0xFF1E9E48)
    val Bad = Color(0xFFD42020)
    val Warn = Color(0xFFE5A400)
    val Info = Color(0xFF2C6FD1)
    val Off = Color(0xFF8E959C)
}

enum class HmiPage(val key: String, val title: String) {
    OVERVIEW("F1", "OVERVIEW"), STATION("F2", "STATION"), ALARMS("F3", "ALARMS"),
    MAINT("F4", "MANUAL / MAINT"), IO("F5", "I/O STATUS"), SIM("F6", "INSTRUCTOR"),
    EDITOR("DEV", "TRACK EDITOR"),
}

fun stateColor(s: RideState) = when (s) {
    RideState.POWER_OFF -> Hmi.Off
    RideState.STOPPED -> Hmi.Info
    RideState.STARTING -> Hmi.Warn
    RideState.RUNNING -> Hmi.Ok
    RideState.CSTOP -> Hmi.Warn
    RideState.ESTOP -> Hmi.Bad
}

fun sevColor(s: Severity) = when (s) {
    Severity.ESTOP -> Hmi.Bad
    Severity.CSTOP -> Color(0xFFE56A00)
    Severity.WARNING -> Hmi.Warn
    Severity.INFO -> Hmi.Info
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

/** PanelView-style operator terminal: bezel, title bar, alarm banner, page, function keys. */
@Composable
fun HmiTerminal(vm: SimViewModel, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(HmiPage.OVERVIEW) }
    vm.frame.longValue
    val plc = vm.plc

    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF2E3238), Color(0xFF1A1C20))))
            .padding(10.dp),
    ) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Allen-Bradley", color = Color(0xFFD5DAE0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("PanelView Plus 1000", color = Color(0xFFB5BCC5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Hmi.Screen)
                .border(3.dp, Color.Black),
        ) {
            TitleBar(vm, page)
            AlarmBanner(vm) { page = HmiPage.ALARMS }
            Box(Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                when (page) {
                    HmiPage.OVERVIEW -> OverviewPage(vm)
                    HmiPage.STATION -> StationPage(vm)
                    HmiPage.ALARMS -> AlarmsPage(vm)
                    HmiPage.MAINT -> MaintPage(vm)
                    HmiPage.IO -> IoPage(vm)
                    HmiPage.SIM -> SimPage(vm) { page = HmiPage.EDITOR }
                    HmiPage.EDITOR -> EditorPage(vm) { page = HmiPage.SIM }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HmiPage.entries.filter { it != HmiPage.EDITOR }.forEach { p ->
                FunctionKey(p.key, p.title, page == p, Modifier.weight(1f)) { page = p }
            }
        }
    }
}

@Composable
private fun TitleBar(vm: SimViewModel, page: HmiPage) {
    val plc = vm.plc
    Row(
        Modifier.fillMaxWidth().background(Hmi.Header).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(plc.profile.name.uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("  ›  ${page.title}", color = Color(0xFFAFC4DD), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.background(stateColor(plc.state), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(plc.state.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.border(1.dp, Color(0xFFAFC4DD), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("MODE: ${plc.inputs.mode.label}", color = Color.White, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(LocalTime.now().format(clockFmt), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AlarmBanner(vm: SimViewModel, onClick: () -> Unit) {
    val plc = vm.plc
    val top = plc.alarms.firstOut ?: plc.alarms.active.firstOrNull()
    val blink = flasher()
    val (bg, txt) = when {
        top == null -> Color(0xFFBFC4C9) to Hmi.Dim
        !top.acked && blink -> sevColor(top.severity) to Color.White
        !top.acked -> Color(0xFF4A1010) to Color.White
        else -> sevColor(top.severity).copy(alpha = 0.85f) to Color.White
    }
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (top == null) {
            Text(plc.statusText.ifEmpty { "No active alarms" }, color = txt, fontSize = 12.sp, maxLines = 1)
        } else {
            Text(top.since, color = txt, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            val fo = if (plc.alarms.isFirstOut(top)) "1ST OUT · " else ""
            Text("  $fo${top.severity.label}  ", color = txt, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(top.text, color = txt, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val n = plc.alarms.active.size
            if (n > 1) Text("+${n - 1} more", color = txt, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FunctionKey(key: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(
                Brush.verticalGradient(
                    if (selected) listOf(Color(0xFF4F8AD6), Color(0xFF2A5C9E)) else listOf(Color(0xFF5B6168), Color(0xFF3A3F45)),
                ),
                RoundedCornerShape(5.dp),
            )
            .border(1.dp, Color.Black, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(key, color = Color(0xFFCFD6DE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- shared HMI widgets

@Composable
fun HmiButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    activeColor: Color = Hmi.Ok,
    fontSize: TextUnit = 11.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(
                when {
                    !enabled -> Brush.verticalGradient(listOf(Color(0xFFCCCCCC), Color(0xFFB8B8B8)))
                    active -> Brush.verticalGradient(listOf(activeColor.copy(alpha = 0.85f), activeColor))
                    else -> Brush.verticalGradient(listOf(Color(0xFFF2F2F2), Color(0xFFC2C6CA)))
                },
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, Color(0xFF6A7178), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (!enabled) Color(0xFF8A8A8A) else if (active) Color.White else Hmi.Text,
        )
    }
}

/** Momentary HMI button that reports press/release (hold-to-run). */
@Composable
fun HmiHoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onChange)
    val en by rememberUpdatedState(enabled)
    Box(
        modifier
            .background(
                if (held) Brush.verticalGradient(listOf(Hmi.Warn, Color(0xFFC08800)))
                else if (enabled) Brush.verticalGradient(listOf(Color(0xFFF2F2F2), Color(0xFFC2C6CA)))
                else Brush.verticalGradient(listOf(Color(0xFFCCCCCC), Color(0xFFB8B8B8))),
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, Color(0xFF6A7178), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (en) { held = true; cb(true) }
                    waitForUpOrCancellation()
                    if (held) { held = false; cb(false) }
                }
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (enabled) Hmi.Text else Color(0xFF8A8A8A))
    }
}

@Composable
fun Indicator(on: Boolean, onColor: Color = Hmi.Ok, offColor: Color = Hmi.Off, size: Int = 12) {
    Box(
        Modifier.size(size.dp).background(if (on) onColor else offColor, RoundedCornerShape(2.dp))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(2.dp)),
    )
}

@Composable
fun HmiLabel(text: String, size: TextUnit = 12.sp, bold: Boolean = false, color: Color = Hmi.Text, modifier: Modifier = Modifier) {
    Text(text, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color, modifier = modifier)
}

@Composable
fun HmiFrame(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Hmi.Panel, RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF8C939A), RoundedCornerShape(3.dp))) {
        Text(
            title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF51606F)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
fun ValueBox(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HmiLabel(label, 11.sp, color = Hmi.Dim, modifier = Modifier.weight(1f))
        Box(Modifier.background(Color.White).border(1.dp, Color(0xFF7A8189)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Hmi.Text)
        }
    }
}

@Composable
fun Spacer6() = Spacer(Modifier.height(6.dp))

@Composable
fun FullPage(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
