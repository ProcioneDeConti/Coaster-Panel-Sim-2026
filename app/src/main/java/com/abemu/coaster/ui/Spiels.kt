package com.abemu.coaster.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
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

/** A station announcement. [text] may use [ride] for the ride name. */
class Spiel(val label: String, val text: String) {
    /** Audio file stem, matching tools/spiels/generate.py. */
    val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/**
 * Spiels from spiels/spiels.txt (packaged as the asset spiels.txt): a '== LABEL ==' line, then the text, which may
 * span several lines. Lines starting with # are comments.
 */
fun parseSpiels(src: String): List<Spiel> {
    val out = mutableListOf<Spiel>()
    var label: String? = null
    val lines = mutableListOf<String>()
    fun flush() { label?.let { out += Spiel(it, lines.joinToString(" ").trim()) } }
    for (raw in src.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#")) continue
        val m = Regex("==\\s*(.+?)\\s*==").matchEntire(line)
        if (m != null) { flush(); label = m.groupValues[1]; lines.clear() } else if (label != null && line.isNotEmpty()) lines += line
    }
    flush()
    return out
}

private fun loadSpiels(context: Context): List<Spiel> =
    runCatching { context.assets.open("spiels.txt").bufferedReader().use { parseSpiels(it.readText()) } }
        .onFailure { Log.w("Spiels", "spiels.txt missing", it) }.getOrDefault(emptyList())

/** Pre-baked recording for [sp] on ride [rideId] (assets/spiels/<slug>__<ride>.mp3 or <slug>.mp3), if there is one. */
private fun recording(context: Context, sp: Spiel, rideId: String): String? {
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
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (playing == id) playing = null }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (playing == id) playing = null }
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
                setOnCompletionListener { if (playing == sp.label) playing = null }
                prepare()
                start()
            }
        } else {
            tts.speak(sp.text.replace("[ride]", rideName), TextToSpeech.QUEUE_FLUSH, null, sp.label)
        }
        playing = sp.label
    }

    HmiFrame("AUTO-SPIELS" + if (!ready) "  (voice loading…)" else "", modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            spiels.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { sp ->
                        HmiButton(sp.label, Modifier.weight(1f), enabled = ready, active = playing == sp.label,
                            activeColor = Hmi.Info, fontSize = 10.sp, maxLines = 2, hPad = 4.dp) { play(sp) }
                    }
                    repeat(3 - row.size) { Text("", Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HmiButton("VOICE: " + (voices.getOrNull(voiceIdx)?.let { voiceLabel(it.name) } ?: "DEFAULT"), Modifier.weight(1f),
                    enabled = ready && voices.size > 1, fontSize = 10.sp) {
                    stopAll()
                    voiceIdx = (voiceIdx + 1) % voices.size
                    tts.voice = voices[voiceIdx]
                    tts.speak("This is the ${voiceLabel(voices[voiceIdx].name).lowercase()} voice for $rideName.", TextToSpeech.QUEUE_FLUSH, null, "voice")
                }
                HmiButton("STOP ANNOUNCEMENT", Modifier.weight(1f), enabled = ready, fontSize = 10.sp) { stopAll() }
            }
        }
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
