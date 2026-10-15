package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.dev.TagStore
import com.abemu.coaster.dev.TagType
import com.abemu.coaster.dev.TrackTag
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.RideProfile

/**
 * Dev tool: tap the plan view to mark features (stations, lifts, brakes, block boundaries...) on
 * the real layout. Tags are saved per coaster and exported as JSON to build accurate ride models.
 */
@Composable
fun EditorPage(vm: SimViewModel, onClose: () -> Unit) {
    vm.frame.longValue
    val plc = vm.plc
    val profile = plc.profile
    val ctx = LocalContext.current
    val tags = remember(vm.coasterId) { mutableStateListOf<TrackTag>().apply { addAll(TagStore.load(ctx, vm.coasterId)) } }
    var a by remember(vm.coasterId) { mutableStateOf<Double?>(null) }
    var b by remember(vm.coasterId) { mutableStateOf<Double?>(null) }
    var type by remember { mutableStateOf(TagType.BLOCK_BRAKE) }
    var name by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Tap the track to place the start point.") }

    val base = remember(vm.coasterId) { RideProfile.create(CoasterLibrary.byId(vm.coasterId)) }

    fun persist() = TagStore.save(ctx, vm.coasterId, profile, tags)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Column(Modifier.weight(1.55f).fillMaxHeight()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(Unit) {
                // Pinch to zoom, drag to pan. Taps pass through to the diagram for selection.
                detectTransformGestures { _, p, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 10f)
                    pan += p
                }
            },
        ) {
            PlanDiagram(
                plc,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                },
                onTapTrack = { s ->
                    when {
                        a == null || b != null || type.point -> { a = s; b = null; msg = "Start set. Tap again for the end point." }
                        else -> { b = s; msg = "Section selected – choose a type and ADD TAG." }
                    }
                    if (type.point) msg = "Point selected – ADD TAG."
                },
                overlay = { xf ->
                    tags.forEach { t ->
                        if (t.toLayout() in vm.tagRejected) {
                            // Rejected by the last build: red cross at the tag.
                            val c = xf.pt(if (t.type.point) t.sStart else profile.wrap(t.sStart + profile.ahead(t.sStart, t.sEnd) / 2))
                            drawLine(Color(0xFFFF1744), c + Offset(-11f, -11f), c + Offset(11f, 11f), strokeWidth = 5f)
                            drawLine(Color(0xFFFF1744), c + Offset(-11f, 11f), c + Offset(11f, -11f), strokeWidth = 5f)
                        }
                        if (t.type.point) {
                            val p = xf.pt(t.sStart)
                            drawCircle(Color.Black, 9f, p)
                            drawCircle(t.type.color, 7f, p)
                        } else {
                            drawPath(xf.seg(t.sStart, t.sEnd), t.type.color.copy(alpha = 0.85f), style = Stroke(16f, cap = StrokeCap.Round))
                        }
                    }
                    val sa = a
                    if (sa != null) {
                        val sb = b
                        if (sb != null) drawPath(xf.seg(sa, sb), Color.White, style = Stroke(8f, cap = StrokeCap.Round))
                        drawCircle(Color.White, 10f, xf.pt(sa), style = Stroke(3f))
                        if (sb != null) drawCircle(Color.White, 10f, xf.pt(sb), style = Stroke(3f))
                        drawCircle(Color.White, 4f, xf.pt(sa) + Offset(0f, 0f))
                    }
                },
            )
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HmiButton("+") { zoom = (zoom * 1.5f).coerceAtMost(10f) }
                HmiButton("−") { zoom = (zoom / 1.5f).coerceAtLeast(1f); if (zoom == 1f) pan = Offset.Zero }
                HmiButton("FIT") { zoom = 1f; pan = Offset.Zero }
            }
            HmiLabel("Pinch / +− to zoom · drag to pan · tap track to select", 10.sp, color = Color(0xFF90A4AE),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
            HmiLabel("ELEVATION  ━ as built   ┅ generated (before tags)", 10.sp, color = Color(0xFF90A4AE))
            TrackDiagram(plc, Modifier.fillMaxWidth().height(130.dp), ghost = base.takeIf { vm.useTags && vm.tagReport.isNotEmpty() })
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HmiFrame("TRACK EDITOR (DEV) – ${profile.name.uppercase()}") {
                HmiLabel(msg, 11.sp, color = Hmi.Info)
                val sa = a
                val sb = b
                ValueBox("Start", sa?.let { "%.1f m".format(it) } ?: "—")
                ValueBox("End", sb?.let { "%.1f m".format(it) } ?: "—")
                ValueBox("Length", if (sa != null && sb != null) "%.1f m".format(profile.ahead(sa, sb)) else "—")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HmiButton("CLEAR SELECTION", Modifier.weight(1f)) { a = null; b = null; msg = "Tap the track to place the start point." }
                    HmiButton("CLOSE EDITOR", Modifier.weight(1f)) { onClose() }
                }
            }
            HmiFrame("TAG TYPE") {
                TagType.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { t ->
                            Row(
                                Modifier.weight(1f)
                                    .background(if (type == t) Color(0xFF2A5C9E) else Color(0xFFE6E8EA))
                                    .border(1.dp, Color(0xFF6A7178))
                                    .clickable { type = t; if (t.point) b = null }
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(12.dp).background(t.color).border(1.dp, Color.Black))
                                HmiLabel("  " + t.label + if (t.point) " •" else "", 11.sp,
                                    color = if (type == t) Color.White else Hmi.Text)
                            }
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Name", 11.sp, modifier = Modifier.width(60.dp))
                    EditField(name, { name = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HmiLabel("Height m", 11.sp, modifier = Modifier.width(60.dp))
                    EditField(height, { height = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), numeric = true)
                }
                val ready = a != null && (type.point || b != null)
                HmiButton("ADD TAG", Modifier.fillMaxWidth(), enabled = ready, active = ready, activeColor = Hmi.Ok) {
                    val sa = a ?: return@HmiButton
                    val sb = if (type.point) sa else b ?: return@HmiButton
                    tags += TrackTag.create(profile, type, sa, sb, name, height.toDoubleOrNull())
                    persist()
                    msg = "Added ${type.label}${if (name.isNotBlank()) " \"$name\"" else ""}."
                    a = null; b = null; name = ""; height = ""
                }
            }
            HmiFrame("TAGS (${tags.size})") {
                if (tags.isEmpty()) HmiLabel("No tags yet.", 11.sp, color = Hmi.Dim)
                tags.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(t.type.color).border(1.dp, Color.Black))
                        if (t.toLayout() in vm.tagRejected) HmiLabel(" ✕", 11.sp, color = Hmi.Bad)
                        HmiLabel(
                            "  ${t.type.label}${if (t.name.isNotEmpty()) " – ${t.name}" else ""}  " +
                                if (t.type.point) "@ %.0f m".format(t.sStart) else "%.0f–%.0f m (%.0f m)".format(t.sStart, t.sEnd, t.lengthM(profile)) +
                                    (t.heightM?.let { " · ${it} m high" } ?: ""),
                            10.sp, modifier = Modifier.weight(1f),
                        )
                        HmiButton("✕") { tags.removeAt(i); persist(); msg = "Tag deleted." }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HmiButton("EXPORT JSON", Modifier.weight(1f), enabled = tags.isNotEmpty()) {
                        val f = TagStore.export(ctx, vm.coasterId, profile, tags)
                        msg = "Exported to ${f.absolutePath}"
                    }
                    HmiButton("DELETE ALL", Modifier.weight(1f), enabled = tags.isNotEmpty(), activeColor = Hmi.Bad) {
                        tags.clear(); persist(); msg = "All tags deleted."
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HmiButton(if (vm.useTags) "TAGS: USED" else "TAGS: IGNORED", Modifier.weight(1f), active = vm.useTags) {
                        vm.useTags = !vm.useTags
                        msg = if (vm.reconfigure()) "Ride rebuilt." else "Setting saved; turn ride power off and press APPLY."
                    }
                    HmiButton("APPLY TO RIDE", Modifier.weight(1f), activeColor = Hmi.Info) {
                        msg = if (vm.reconfigure()) "Ride rebuilt from tags." else "Turn ride power off first."
                    }
                }
                vm.tagReport.forEach { HmiLabel("• $it", 10.sp) }
            }
        }
    }
}

@Composable
internal fun EditField(value: String, onChange: (String) -> Unit, modifier: Modifier, numeric: Boolean = false) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = Hmi.Text),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.background(Color.White).border(1.dp, Color(0xFF7A8189)).padding(6.dp),
    )
}
