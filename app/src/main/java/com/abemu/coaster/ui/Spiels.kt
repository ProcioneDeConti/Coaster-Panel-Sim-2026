package com.abemu.coaster.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * A station announcement. [label] is its ID (names the recordings; the desk-mode PA looks spiels up by it),
 * [title] the button text, [section] the heading it is grouped under. [text] may use [ride] for the ride name.
 */
class Spiel(val label: String, val text: String, val title: String = label, val section: String = "") {
    /** Audio file stem, matching tools/spiels/generate.py. */
    val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/**
 * Spiels from spiels/spiels.txt (packaged as the asset spiels.txt): a '== ID | Button title ==' line, then the
 * text, which may span several lines. '## Section' starts a group of buttons; other lines starting with # are comments.
 */
fun parseSpiels(src: String): List<Spiel> {
    val out = mutableListOf<Spiel>()
    var label: String? = null
    var title = ""
    var section = ""
    val lines = mutableListOf<String>()
    fun flush() { label?.let { out += Spiel(it, lines.joinToString(" ").trim(), title, section) } }
    for (raw in src.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("## ")) { flush(); label = null; section = line.removePrefix("## ").trim(); continue }
        if (line.startsWith("#")) continue
        val m = Regex("==\\s*(.+?)\\s*==").matchEntire(line)
        if (m != null) {
            flush()
            val parts = m.groupValues[1].split("|", limit = 2).map { it.trim() }
            label = parts[0]; title = parts.getOrElse(1) { parts[0] }; lines.clear()
        } else if (label != null && line.isNotEmpty()) lines += line
    }
    flush()
    return out
}

internal fun loadSpiels(context: Context): List<Spiel> =
    runCatching { context.assets.open("spiels.txt").bufferedReader().use { parseSpiels(it.readText()) } }
        .onFailure { Log.w("Spiels", "spiels.txt missing", it) }.getOrDefault(emptyList())

/** Pre-baked recording for [sp] on ride [rideId] (assets/spiels/<slug>__<ride>.mp3 or <slug>.mp3), if there is one. */
internal fun recording(context: Context, sp: Spiel, rideId: String): String? {
    val files = runCatching { context.assets.list("spiels")?.toSet() }.getOrNull().orEmpty()
    val name = if (sp.text.contains("[ride]")) "${sp.slug}__$rideId.mp3" else "${sp.slug}.mp3"
    return if (name in files) "spiels/$name" else null
}

/**
 * Station auto-spiel panel. Each button plays its pre-baked recording, or speaks the text with the device voice
 * when there is no recording for this ride. A new press replaces the announcement playing.
 */
@Composable
fun SpielPanel(rideId: String, rideName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val spiels = remember { loadSpiels(context) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var voiceIdx by remember { mutableStateOf(0) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                // US English voices, male voices first (network voices sound most natural), then the rest.
                val all = engine?.voices.orEmpty().filter { it.locale.language == "en" && it.locale.country == "US" }
                voices = all.sortedWith(compareBy({ v -> maleRank(v.name) }, { v -> if (v.isNetworkConnectionRequired) 0 else 1 }, { it.name }))
                voices.firstOrNull()?.let { engine?.voice = it }
                engine?.setPitch(0.92f)
                engine?.setSpeechRate(0.95f)
                // Light the playing button until its announcement finishes.
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { PaBus.speaking = true }
                    override fun onDone(id: String?) { if (playing == id) { playing = null; PaBus.speaking = false } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
                })
                ready = true
            }
        }
        engine
    }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    fun stopAll() {
        tts.stop()
        player[0]?.run { stop(); release() }
        player[0] = null
        playing = null
        PaBus.speaking = false
    }
    DisposableEffect(tts) { onDispose { stopAll(); tts.shutdown() } }

    fun play(sp: Spiel) {
        stopAll()
        val file = recording(context, sp, rideId)
        if (file != null) {
            val afd = context.assets.openFd(file)
            player[0] = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { if (playing == sp.label) { playing = null; PaBus.speaking = false } }
                prepare()
                start()
            }
            PaBus.speaking = true
        } else {
            tts.speak(sp.text.replace("[ride]", rideName), TextToSpeech.QUEUE_FLUSH, null, sp.label)
        }
        playing = sp.label
    }

    val sections = remember(spiels) { spiels.groupBy { it.section.ifEmpty { "Announcements" } } }
    var menu by remember { mutableStateOf<String?>(null) }
    val nowPlaying = spiels.firstOrNull { it.label == playing }
    fun nextVoice() {
        stopAll()
        voiceIdx = (voiceIdx + 1) % voices.size
        tts.voice = voices[voiceIdx]
        tts.speak("This is the ${voiceLabel(voices[voiceIdx].name).lowercase()} voice for $rideName.", TextToSpeech.QUEUE_FLUSH, null, "voice")
    }

    // A wall-mounted PA touch panel: dark bezel, status bar, a home screen of menus, a list per menu,
    // and a now-playing bar along the bottom.
    Box(modifier.background(Pa.Bezel, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Column(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(4.dp)).background(Pa.Screen)) {
            // Status bar
            Row(Modifier.fillMaxWidth().background(Pa.Bar).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (menu != null) {
                    Text("‹ Home", color = Pa.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { menu = null }.padding(end = 10.dp))
                }
                Text(menu ?: "Station PA", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (ready) rideName else "Voice loading…", color = Pa.Dim, fontSize = 11.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val open = menu?.let { sections[it] }
                if (open == null) {
                    // Home: one tile per menu, plus the voice setting.
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (sections.keys.toList() + VOICE_TILE).chunked(3).forEach { row ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { name ->
                                    val voice = name == VOICE_TILE
                                    val sub = if (voice) voices.getOrNull(voiceIdx)?.let { voiceLabel(it.name) } ?: "Default"
                                        else "${sections[name]?.size ?: 0} spiels"
                                    PaTile(name, sub, Modifier.weight(1f).fillMaxSize(),
                                        enabled = if (voice) ready && voices.size > 1 else true,
                                        lit = !voice && sections[name]?.any { it.label == playing } == true) {
                                        if (voice) nextVoice() else menu = name
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    // Menu: a scrolling list; tap a line to play it, tap it again to stop.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        open.forEach { sp ->
                            val on = playing == sp.label
                            Row(Modifier.fillMaxWidth().background(if (on) Pa.Accent.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable(enabled = ready) { if (on) stopAll() else play(sp) }
                                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(sp.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(sp.text.replace("[ride]", rideName), color = Pa.Dim, fontSize = 10.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (on) "■" else "▶", color = if (on) Pa.Accent else Pa.Dim, fontSize = 14.sp,
                                    modifier = Modifier.padding(start = 10.dp))
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Pa.Line))
                        }
                    }
                }
            }
            // Now playing
            Row(Modifier.fillMaxWidth().background(Pa.Bar).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(nowPlaying?.let { "♪  ${it.title}" } ?: "Ready", color = if (nowPlaying != null) Pa.Accent else Pa.Dim,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("STOP", color = if (nowPlaying != null) Color.White else Pa.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(if (nowPlaying != null) Pa.Stop else Pa.Line, RoundedCornerShape(4.dp))
                        .clickable(enabled = nowPlaying != null) { stopAll() }.padding(horizontal = 14.dp, vertical = 5.dp))
            }
        }
    }
}

private const val VOICE_TILE = "Voice"

/** PA touch panel colours: a dark touchscreen, distinct from the grey PLC HMI. */
private object Pa {
    val Bezel = Color(0xFF111316)
    val Screen = Color(0xFF1A2230)
    val Bar = Color(0xFF0F151F)
    val Tile = Color(0xFF263247)
    val Line = Color(0xFF2E3A4E)
    val Accent = Color(0xFF4FC3F7)
    val Dim = Color(0xFF8C9AB0)
    val Stop = Color(0xFFC62828)
}

@Composable
private fun PaTile(title: String, sub: String, modifier: Modifier, enabled: Boolean, lit: Boolean, onClick: () -> Unit) {
    Column(modifier.background(if (lit) Pa.Accent.copy(alpha = 0.3f) else Pa.Tile, RoundedCornerShape(8.dp))
        .border(1.dp, if (lit) Pa.Accent else Pa.Line, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, color = if (enabled) Color.White else Pa.Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Pa.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Google TTS US English voices that are male (by voice code), in preference order. */
private val maleVoices = listOf("iom", "iol", "tpd")

private fun maleRank(name: String): Int = maleVoices.indexOfFirst { name.contains("-x-$it") }.let { if (it < 0) maleVoices.size else it }

/** Short button label for a voice, e.g. "MALE 1 (NET)". */
private fun voiceLabel(name: String): String {
    val r = maleRank(name)
    val kind = if (r < maleVoices.size) "MALE ${r + 1}" else name.substringAfter("-x-", name).substringBefore("-").uppercase()
    return kind + if (name.endsWith("network")) " (NET)" else ""
}
