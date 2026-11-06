package com.abemu.coaster.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import kotlinx.coroutines.delay

/** One line of the radio script (spiels/radio.txt). */
class RadioLine(val clip: String, val speaker: String, val text: String, val label: String = speakerName(speaker))

/** Parses '== clip | SPEAKER ==' headers followed by the spoken text (same rules as tools/spiels/radio.py). */
fun parseRadio(src: String): Map<String, RadioLine> {
    val out = LinkedHashMap<String, RadioLine>()
    var head: Triple<String, String, String?>? = null
    val lines = mutableListOf<String>()
    fun flush() { head?.let { (c, s, l) -> out[c] = RadioLine(c, s, lines.joinToString(" ").trim(), l ?: speakerName(s)) } }
    for (raw in src.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#")) continue
        val m = Regex("==\\s*(\\S+)\\s*\\|\\s*(\\w+)\\s*(?:\\|\\s*([^=]+?))?\\s*==").matchEntire(line)
        if (m != null) {
            flush()
            head = Triple(m.groupValues[1], m.groupValues[2].uppercase(), m.groupValues[3].takeIf { it.isNotBlank() }?.uppercase())
            lines.clear()
        }
        else if (head != null && line.isNotEmpty()) lines += line
    }
    flush()
    return out
}

internal fun loadRadio(context: Context): Map<String, RadioLine> =
    runCatching { context.assets.open("radio.txt").bufferedReader().use { parseRadio(it.readText()) } }
        .onFailure { Log.w("Radio", "radio.txt missing", it) }.getOrDefault(emptyMap())

/** What the console radio shows: the transmission on the air now, or the last one. */
object RadioState {
    var onAir by mutableStateOf<RadioLine?>(null)
    var last by mutableStateOf<RadioLine?>(null)
    /** Exact clip ids queued from the debug sound test; played ahead of the channel's own calls. */
    val preview = ArrayDeque<String>()
}

fun speakerName(s: String) = when (s) { "SUP" -> "SUPERVISOR"; "MAINT" -> "MAINTENANCE"; "OP" -> "YOU"; else -> s.replace('_', ' ') }

/** Plays queued radio calls one after another from assets/radio/<clip>.wav. */
@Composable
fun RadioPlayer(vm: SimViewModel) {
    val context = LocalContext.current
    val script = remember { loadRadio(context) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    // Recorded variants still to be played per call, so a variant does not repeat until all have been heard.
    val variantBags = remember { HashMap<String, MutableList<String>>() }
    val visible by rememberUpdatedState(rememberAppVisible())
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null; RadioState.onAir = null } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (!visible) {
                // Off screen: cut the call on air; queued calls wait until the app is back.
                player[0]?.let { runCatching { it.stop() }; it.release() }
                player[0] = null
                RadioState.onAir = null
                continue
            }
            if (RadioState.onAir != null) continue
            val forced = RadioState.preview.removeFirstOrNull()
            val base = forced ?: vm.radio.next() ?: continue
            // A call may have recorded variants (<id>_2, <id>_3 ...): pick one at random. Chatter ids are exact.
            val variant = Regex(Regex.escape(base) + "_\\d+")
            val clip = if (forced != null || base.startsWith("chatter_")) base else {
                val bag = variantBags.getOrPut(base) { mutableListOf() }
                if (bag.isEmpty()) bag += script.keys.filter { it == base || variant.matches(it) }.shuffled()
                bag.removeFirstOrNull() ?: base
            }
            val line = script[clip] ?: RadioLine(clip, "SUP", clip)
            RadioState.onAir = line
            RadioState.last = line
            val file = "radio/$clip.wav"
            val ok = vm.soundOn && runCatching {
                val afd = context.assets.openFd(file)
                player[0]?.release()
                player[0] = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(vm.volume, vm.volume)
                    setOnCompletionListener { RadioState.onAir = null }
                    prepare()
                    start()
                }
            }.isSuccess
            // No recording (or sound off): leave the text up for about as long as it would take to say.
            if (!ok) { delay(1500L + line.text.length * 55L); RadioState.onAir = null }
            delay(600) // a short gap between transmissions
        }
    }
}

/** Console-mounted ride-ops radio: channel display, receive lamp, on/off switch and a push-to-talk key with reply menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsoleRadio(vm: SimViewModel) {
    val on = vm.radioOn
    val line = RadioState.onAir
    val shown = line ?: RadioState.last
    var menu by remember { mutableStateOf(false) }
    val waiting = on && vm.radio.prompt != null
    if (waiting) vm.frame.longValue // keep the PTT lamp flashing
    val blink = flasher()
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF1B1D20), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF5A5F66), RoundedCornerShape(6.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(12.dp).background(if (on && line != null) Lens.Green else Color(0xFF233528), CircleShape)
                .border(1.dp, Color.Black, CircleShape))
            Text("RX", color = Color(0xFF8A929C), fontSize = 8.sp)
        }
        Column(
            Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) Color(0xFF2E3B22) else Color(0xFF15190F), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            val lcd = if (on) Color(0xFFB7E07A) else Color(0xFF3A4A2A)
            Text("CH 3  RIDE OPS" + if (shown != null && on) "   ◂ ${shown.label}" else "",
                color = lcd, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(if (on) shown?.text ?: "" else "", color = lcd.copy(alpha = if (line != null) 1f else 0.6f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 11.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.radioOn = !on }) {
            Box(Modifier.size(width = 22.dp, height = 30.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(3.dp))
                .border(1.dp, Color(0xFF6A7178), RoundedCornerShape(3.dp)), contentAlignment = if (on) Alignment.TopCenter else Alignment.BottomCenter) {
                Box(Modifier.padding(2.dp).size(width = 16.dp, height = 12.dp).background(Color(0xFFBDBDBD), RoundedCornerShape(2.dp)))
            }
            Text(if (on) "ON" else "OFF", color = Color(0xFF8A929C), fontSize = 8.sp)
        }
        // Push-to-talk: flashes while a call is waiting for the operator's answer; opens the reply menu.
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(enabled = on) { menu = !menu }) {
            Box(Modifier.size(width = 34.dp, height = 30.dp)
                .background(if (waiting && blink) Lens.Amber else Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                .border(1.dp, if (menu) Lens.Amber else Color(0xFF6A7178), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center) {
                Text("PTT", color = if (waiting && blink) Color.Black else Color(0xFFBDBDBD), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("TALK", color = Color(0xFF8A929C), fontSize = 8.sp)
        }
    }
    if (menu && on) {
        val replies = vm.radio.replies
        val answers = vm.radio.prompt?.let { com.abemu.coaster.sim.RadioDesk.REPLIES[it] }.orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            replies.forEach { r ->
                val answer = r in answers
                Text(r.label, color = if (answer) Color.Black else Color(0xFFB7E07A), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (answer) Lens.Amber else Color(0xFF2E3B22), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0xFF5A5F66), RoundedCornerShape(3.dp))
                        .clickable { vm.radio.reply(r); menu = false }
                        .padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
    }
    }
}
