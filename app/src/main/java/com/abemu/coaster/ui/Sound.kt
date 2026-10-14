package com.abemu.coaster.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.LaunchState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * All ride sounds come from this one synthesiser so they share a single, consistent level.
 * Continuous sounds (track clear horn, fault alarm) follow flags; short cues are queued.
 */
class RideAudio {
    @Volatile var trackClear = false
    @Volatile var faultAlarm = false
    @Volatile var volume = 0.6f

    private class Cue(val freqs: DoubleArray, val stepMs: Int)

    @Volatile private var cue: Cue? = null
    @Volatile private var running = true
    private val rate = 44100

    fun dispatchChime() { cue = Cue(doubleArrayOf(784.0, 1047.0), 180) }
    fun launchWarning() { cue = Cue(doubleArrayOf(1200.0, 0.0, 1200.0, 0.0, 1200.0), 120) }

    private val thread = Thread {
        // Audio-priority thread so UI/GC work can't starve the buffer (a common cause of crackle).
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        track.play()
        val chunk = ShortArray(rate / 50) // 20 ms
        var phase = 0.0
        var n = 0L // samples since start, drives the patterns
        var cueStart = -1L
        var activeCue: Cue? = null
        var env = 0.0
        var freq = 0.0
        var soft = false
        var tcStart = -1L // sample at which the current track clear warning began
        while (running) {
            for (i in chunk.indices) {
                n++
                val ms = n * 1000 / rate
                cue?.let { activeCue = it; cue = null; cueStart = n }
                var target = 0.0
                val c = activeCue
                if (c != null) {
                    val step = ((n - cueStart) * 1000 / rate / c.stepMs).toInt()
                    if (step >= c.freqs.size) activeCue = null else target = c.freqs[step]
                }
                soft = activeCue == null && trackClear
                if (trackClear) { if (tcStart < 0) tcStart = n } else tcStart = -1
                val tcMs = if (tcStart >= 0) (n - tcStart) * 1000 / rate else 0L
                if (activeCue == null) target = when {
                    // Hi-lo track clear horn, 450 ms per tone, always starting on the high tone.
                    trackClear -> if ((tcMs / 450) % 2 == 0L) 659.3 else 493.9
                    // Fault / E-stop alarm: 1 kHz pulsed 400 ms on, 300 ms off.
                    faultAlarm -> if (ms % 700 < 400) 1000.0 else 0.0
                    else -> 0.0
                }
                // Track clear fades in gently; pitch changes are instant (no glide).
                if (target > 0) freq = target
                val attack = if (soft) 0.0003 else 0.002
                env = if (target > 0) minOf(1.0, env + attack) else maxOf(0.0, env - 0.002)
                phase += 2 * PI * freq / rate
                if (phase > 2 * PI) phase -= 2 * PI
                // Fundamental plus odd harmonics for a horn-like timbre, normalised to unit peak.
                val v = if (soft) {
                    // Same horn character as before, with the upper harmonics turned down to take the edge off.
                    (sin(phase) + 0.2 * sin(3 * phase) + 0.06 * sin(5 * phase)) / 1.15
                } else {
                    (sin(phase) + 0.35 * sin(3 * phase) + 0.15 * sin(5 * phase)) / 1.3
                }
                chunk[i] = (v * env * volume * 0.8 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            track.write(chunk, 0, chunk.size)
        }
        track.stop()
        track.release()
    }.apply { isDaemon = true; start() }

    fun release() { running = false }
}

@Composable
fun RideSounds(vm: SimViewModel) {
    val audio = remember { RideAudio() }
    DisposableEffect(Unit) { onDispose { audio.release() } }
    LaunchedEffect(Unit) {
        var lastDispatch = vm.plc.dispatchCount
        var lastLaunch = vm.plc.launchState
        while (true) {
            delay(50)
            val plc = vm.plc
            val on = vm.soundOn
            audio.volume = vm.volume
            audio.trackClear = on && plc.trackClearActive
            audio.faultAlarm = on && plc.hornActive
            if (on && plc.dispatchCount > lastDispatch) audio.dispatchChime()
            if (on && plc.launchState == LaunchState.CHARGING && lastLaunch != LaunchState.CHARGING) audio.launchWarning()
            lastDispatch = plc.dispatchCount
            lastLaunch = plc.launchState
        }
    }
}
