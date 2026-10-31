package com.abemu.coaster.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.AutoOperator.Phase
import java.util.Locale
import kotlin.random.Random

/**
 * Desk-display PA: while the virtual crew runs the ride, the station announcements follow what it does
 * (gates, restraint check, dispatch, unload, delays, weather) with the odd safety reminder between trains.
 * Uses the pre-baked recordings, or the device voice for rides without them. Routine spiels are skipped if
 * one is already playing; delay and weather announcements cut in.
 */
@Composable
fun DeskPa(vm: SimViewModel) {
    val context = LocalContext.current
    val visible = rememberAppVisible()
    val spiels = remember { loadSpiels(context).associateBy { it.label } }
    val pa = remember { PaPlayer(context) }
    DisposableEffect(pa) { onDispose { pa.release() } }
    val s = remember { State() }

    vm.frame.longValue // re-check each update
    val on = vm.deskMode && visible && vm.soundOn && vm.soundEnabled[SoundKind.DESK_PA] != false
    if (!on) { pa.stop(); s.reset(); return }
    pa.volume = vm.volume

    val rideId = vm.plc.profile.spec.id
    fun say(label: String, urgent: Boolean = false) {
        val sp = spiels[label] ?: return
        if (pa.busy && !urgent) return
        pa.play(sp, recording(context, sp, rideId), vm.plc.profile.spec.name)
    }

    val now = System.nanoTime() / 1e9
    val phase = vm.autoOp.phase
    val prev = s.phase
    if (phase != prev) {
        s.phase = phase
        when (phase) {
            Phase.UNLOAD -> say("UNLOAD")
            // Long boarding welcome now and then; otherwise the short gates-opening call.
            Phase.BOARD -> say(if (Random.nextInt(3) == 0) "BOARDING" else "AIR GATES")
            Phase.SECURE -> say("GATES CLOSING")
            Phase.CHECK -> if (prev == Phase.SECURE) say("RESTRAINT CHECK")
            Phase.WAIT -> when {
                prev == Phase.DISPATCH -> say("DISPATCH")
                s.delayed -> say("BACK IN OPERATION", urgent = true)
            }
            Phase.MAINT_CALLED -> if (!s.delayed) { s.delayed = true; s.delaySince = now; say("DELAY SHORT", urgent = true) }
            else -> {}
        }
        // The crew is back to cycling trains: the delay is over.
        if (phase == Phase.WAIT || phase == Phase.UNLOAD) s.delayed = false
    }
    // A long repair gets the maintenance announcement once.
    if (s.delayed && !s.maintSaid && now - s.delaySince > 60 && phase in MAINT_PHASES) { s.maintSaid = true; say("DELAY MAINT", urgent = true) }
    if (!s.delayed) s.maintSaid = false

    val hold = vm.plc.weather.hold
    if (hold && !s.weather) say("WEATHER HOLD", urgent = true)
    if (!hold && s.weather) say("BACK IN OPERATION", urgent = true)
    s.weather = hold

    // Filler between trains: a reminder every 3–6 minutes while the crew waits for the next train.
    if (s.nextFiller == 0.0) s.nextFiller = now + 120
    if (phase == Phase.WAIT && !s.delayed && !hold && now > s.nextFiller && !pa.busy) {
        say(FILLERS.random())
        s.nextFiller = now + 180 + Random.nextDouble() * 180
    }
}

private val MAINT_PHASES = setOf(Phase.MAINT_CALLED, Phase.MAINT_WORKING, Phase.RECOVER, Phase.JOG)
private val FILLERS = listOf("SAFETY CHECK", "RIDER REQS", "SMOKING", "SINGLE RIDERS")

private class State {
    var phase: Phase? = null
    var delayed = false
    var delaySince = 0.0
    var maintSaid = false
    var weather = false
    var nextFiller = 0.0
    fun reset() { phase = null; delayed = false; maintSaid = false; weather = false; nextFiller = 0.0 }
}

/** One announcement at a time: a recording through MediaPlayer, or the device voice when there isn't one. */
private class PaPlayer(context: android.content.Context) {
    private val ctx = context.applicationContext
    private var player: MediaPlayer? = null
    private var ttsReady = false
    @Volatile private var ttsTalking = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { st ->
        if (st == TextToSpeech.SUCCESS) { ttsReady = true; tts.language = Locale.US; tts.setPitch(0.92f); tts.setSpeechRate(0.95f) }
    }.also {
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { ttsTalking = true; PaBus.speaking = true }
            override fun onDone(id: String?) { ttsTalking = false; PaBus.speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
        })
    }
    var volume = 0.5f
        set(v) { field = v; player?.setVolume(v, v) }
    val busy get() = player != null || ttsTalking

    fun play(sp: Spiel, file: String?, rideName: String) {
        stop()
        if (file != null) {
            player = runCatching {
                ctx.assets.openFd(file).use { afd ->
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        setVolume(volume, volume)
                        setOnCompletionListener { mp -> if (player === mp) { mp.release(); player = null; PaBus.speaking = false } }
                        prepare()
                        start()
                    }
                }
            }.onFailure { Log.w("DeskPa", "can't play $file", it) }.getOrNull()
            PaBus.speaking = player != null
        } else if (ttsReady) {
            val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            tts.speak(sp.text.replace("[ride]", rideName), TextToSpeech.QUEUE_FLUSH, params, sp.label)
        }
    }

    fun stop() {
        player?.run { runCatching { stop() }; release() }
        player = null
        if (ttsTalking) tts.stop()
        ttsTalking = false
        PaBus.speaking = false
    }

    fun release() { stop(); tts.shutdown() }
}
