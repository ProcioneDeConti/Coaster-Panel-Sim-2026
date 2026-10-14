package com.abemu.coaster.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.BlockKind
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Every sound the emulator makes; each can be switched on or off independently. */
enum class SoundKind(val label: String) {
    TRACK_CLEAR("Track clear warning"),
    FAULT_HORN("Fault / E-Stop horn"),
    DISPATCH("Dispatch chime"),
    LAUNCH_WARNING("Launch warning beeps"),
    LIFT_MOTOR("Lift motor"),
    ANTI_ROLLBACK("Anti-rollback clack"),
    BRAKES("Brake pneumatics"),
    LSM("LSM launch"),
    STATION_PNEUMATICS("Gates & restraints"),
}

/**
 * Real-time synthesiser. The alarm voice (track clear, fault horn, cues) has priority as before;
 * ride sounds (lift, clack, brakes, LSM, pneumatics) are layered underneath it.
 */
class RideAudio {
    // Alarm voice
    @Volatile var trackClear = false
    @Volatile var faultAlarm = false
    @Volatile var volume = 0.5f

    // Ride sounds
    @Volatile var liftLevel = 0.0      // 0..1, lift motor load/speed
    @Volatile var clackRateHz = 0.0    // anti-rollback dog clicks per second (0 = none)
    @Volatile var lsmLevel = -1.0      // 0..1 launch speed fraction while firing, < 0 = silent

    private class Cue(val freqs: DoubleArray, val stepMs: Int)

    @Volatile private var cue: Cue? = null
    @Volatile private var hissTrigger = 0
    @Volatile private var thumpTrigger = 0
    @Volatile private var running = true
    private val rate = 44100

    fun dispatchChime() { cue = Cue(doubleArrayOf(784.0, 1047.0), 180) }
    fun launchWarning() { cue = Cue(doubleArrayOf(1200.0, 0.0, 1200.0, 0.0, 1200.0), 120) }
    /** Pneumatic exhaust (brake valve, air gate). */
    fun hiss() { hissTrigger++ }
    /** Mechanical clunk (restraints locking/unlocking). */
    fun thump() { thumpTrigger++ }

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
        val dt = 1.0 / rate

        // Alarm voice state
        var phase = 0.0
        var n = 0L
        var cueStart = -1L
        var activeCue: Cue? = null
        var env = 0.0
        var freq = 0.0
        var tcStart = -1L

        // Ride sound state
        var liftAmp = 0.0
        var liftPh = 0.0
        var clackPh = 0.0
        var clackEnv = 0.0
        var lsmAmp = 0.0
        var lsmPh = 0.0
        var lsmFreq = 150.0
        var hissEnv = 0.0
        var hissSeen = 0
        var thumpEnv = 0.0
        var thumpPh = 0.0
        var thumpSeen = 0
        var noise = 12345
        var hp = 0.0
        var lastN = 0.0

        while (running) {
            if (hissTrigger != hissSeen) { hissSeen = hissTrigger; hissEnv = 1.0 }
            if (thumpTrigger != thumpSeen) { thumpSeen = thumpTrigger; thumpEnv = 1.0; thumpPh = 0.0 }
            for (i in chunk.indices) {
                n++
                val ms = n * 1000 / rate

                // ---------------- alarm voice (unchanged character) ----------------
                cue?.let { activeCue = it; cue = null; cueStart = n }
                var target = 0.0
                val c = activeCue
                if (c != null) {
                    val step = ((n - cueStart) * 1000 / rate / c.stepMs).toInt()
                    if (step >= c.freqs.size) activeCue = null else target = c.freqs[step]
                }
                val soft = activeCue == null && trackClear
                if (trackClear) { if (tcStart < 0) tcStart = n } else tcStart = -1
                val tcMs = if (tcStart >= 0) (n - tcStart) * 1000 / rate else 0L
                if (activeCue == null) target = when {
                    trackClear -> if ((tcMs / 450) % 2 == 0L) 659.3 else 493.9
                    faultAlarm -> if (ms % 700 < 400) 1000.0 else 0.0
                    else -> 0.0
                }
                if (target > 0) freq = target
                val attack = if (soft) 0.0003 else 0.002
                env = if (target > 0) minOf(1.0, env + attack) else maxOf(0.0, env - 0.002)
                phase += 2 * PI * freq / rate
                if (phase > 2 * PI) phase -= 2 * PI
                val alarm = if (soft) (sin(phase) + 0.2 * sin(3 * phase) + 0.06 * sin(5 * phase)) / 1.15
                else (sin(phase) + 0.35 * sin(3 * phase) + 0.15 * sin(5 * phase)) / 1.3
                var mix = alarm * env * 0.8

                // ---------------- ride sounds ----------------
                noise = noise * 1103515245 + 12345
                val wn = ((noise ushr 8) and 0xFFFF) / 32768.0 - 1.0
                hp = 0.97 * (hp + wn - lastN) // one-pole high-pass for hiss
                lastN = wn

                // Lift motor: low hum with a little ripple, smoothed amplitude.
                liftAmp += (liftLevel - liftAmp) * 0.0004
                if (liftAmp > 0.001) {
                    liftPh += 2 * PI * (48.0 + 12.0 * liftAmp) * dt
                    mix += liftAmp * 0.22 * (sin(liftPh) + 0.5 * sin(2 * liftPh) + 0.25 * sin(3 * liftPh)) / 1.75
                }

                // Anti-rollback: periodic clicks as the dogs pass each rack tooth.
                val cr = clackRateHz
                if (cr > 0.1) {
                    clackPh += cr * dt
                    if (clackPh >= 1.0) { clackPh -= 1.0; clackEnv = 1.0 }
                }
                if (clackEnv > 0.0005) {
                    mix += clackEnv * 0.35 * (0.6 * wn + 0.4 * sin(2 * PI * 2200.0 * n * dt))
                    clackEnv *= exp(-dt / 0.006)
                }

                // LSM: rising electrical whine while the launch fires.
                val lv = lsmLevel
                lsmAmp += ((if (lv >= 0) 1.0 else 0.0) - lsmAmp) * 0.0008
                if (lv >= 0) lsmFreq = 160.0 + 1300.0 * lv
                if (lsmAmp > 0.001) {
                    lsmPh += 2 * PI * lsmFreq * dt
                    mix += lsmAmp * 0.2 * (sin(lsmPh) + 0.3 * sin(2 * lsmPh) + 0.1 * (if (sin(lsmPh * 3) > 0) 1 else -1))
                }

                // Pneumatic hiss.
                if (hissEnv > 0.001) {
                    mix += hissEnv * 0.45 * hp
                    hissEnv *= exp(-dt / 0.22)
                }

                // Mechanical thump.
                if (thumpEnv > 0.001) {
                    thumpPh += 2 * PI * 85.0 * dt
                    mix += thumpEnv * 0.5 * sin(thumpPh)
                    thumpEnv *= exp(-dt / 0.06)
                }

                chunk[i] = (mix * volume * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            track.write(chunk, 0, chunk.size)
        }
        track.stop()
        track.release()
    }.apply { isDaemon = true; start() }

    fun release() { running = false }
}

/** Watches the simulation and drives [RideAudio]. */
@Composable
fun RideSounds(vm: SimViewModel) {
    val audio = remember { RideAudio() }
    DisposableEffect(Unit) { onDispose { audio.release() } }
    LaunchedEffect(Unit) {
        var lastDispatch = vm.plc.dispatchCount
        var lastLaunch = vm.plc.launchState
        var lastBrakes = vm.plc.plant.brakes.map { it.isOpen }
        var lastGates = vm.plc.plant.gatesOpenCmd
        var lastUnlock = vm.plc.plant.restraintUnlockCmd
        var lastPlc = vm.plc
        while (true) {
            delay(40)
            val plc = vm.plc
            val plant = plc.plant
            if (plc !== lastPlc) {
                lastPlc = plc
                lastBrakes = plant.brakes.map { it.isOpen }
                lastDispatch = plc.dispatchCount
            }
            fun on(k: SoundKind) = vm.soundOn && vm.soundEnabled[k] != false
            audio.volume = vm.volume
            audio.trackClear = on(SoundKind.TRACK_CLEAR) && plc.trackClearActive
            audio.faultAlarm = on(SoundKind.FAULT_HORN) && plc.hornActive

            // Lift motor and anti-rollback clack (lift coasters).
            val profile = plc.profile
            if (profile.type == ProfileType.LIFT || profile.blocks.any { it.kind == BlockKind.LIFT }) {
                val frac = (plant.chainSpeed / profile.liftSpeed).coerceIn(0.0, 1.0)
                val loaded = plant.trains.any { profile.inRange(it.s, profile.liftStart, profile.liftEnd) }
                audio.liftLevel = if (on(SoundKind.LIFT_MOTOR)) frac * (if (loaded) 1.0 else 0.6) else 0.0
                // Dogs click roughly every 0.6 m of travel while a train is on the lift.
                audio.clackRateHz = if (on(SoundKind.ANTI_ROLLBACK) && loaded && plant.chainSpeed > 0.1) plant.chainSpeed / 0.6 else 0.0
            } else {
                audio.liftLevel = 0.0
                audio.clackRateHz = 0.0
            }

            // LSM whine while firing (station launch) or a tunnel launch is accelerating a train.
            val firing = plc.launchState == LaunchState.FIRING
            val tunnelLaunching = profile.blocks.any { b ->
                b.exitLaunch > 0 && plant.brakes[b.index].isOpen &&
                    plant.trains.any { t -> b.stopPos != null && profile.inRange(t.s, b.stopPos - 0.5, b.end) && t.v < b.exitLaunch - 0.2 && t.v > 0.1 }
            }
            audio.lsmLevel = if (on(SoundKind.LSM) && (firing || tunnelLaunching)) {
                val top = maxOf(profile.launchSpeed, profile.blocks.maxOf { it.exitLaunch }, 1.0)
                (plant.trains.maxOfOrNull { abs(it.v) } ?: 0.0).div(top).coerceIn(0.0, 1.0)
            } else -1.0

            // Brake valves: hiss whenever a brake changes state.
            val brakes = plant.brakes.map { it.isOpen }
            if (brakes != lastBrakes && on(SoundKind.BRAKES)) audio.hiss()
            lastBrakes = brakes

            // Station pneumatics.
            if (plant.gatesOpenCmd != lastGates && on(SoundKind.STATION_PNEUMATICS)) audio.hiss()
            if (plant.restraintUnlockCmd != lastUnlock && on(SoundKind.STATION_PNEUMATICS)) audio.thump()
            lastGates = plant.gatesOpenCmd
            lastUnlock = plant.restraintUnlockCmd

            if (on(SoundKind.DISPATCH) && plc.dispatchCount > lastDispatch) audio.dispatchChime()
            if (on(SoundKind.LAUNCH_WARNING) && plc.launchState == LaunchState.CHARGING && lastLaunch != LaunchState.CHARGING) audio.launchWarning()
            lastDispatch = plc.dispatchCount
            lastLaunch = plc.launchState
        }
    }
}
