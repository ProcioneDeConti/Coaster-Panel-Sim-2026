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
import kotlin.math.tanh

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
    AMBIENCE("Ambience (wind / air)"),
    STATION_MUSIC("Station music"),
    DESK_PA("Auto spiels (desk PA)"),
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
    @Volatile var ambience = false     // quiet outdoor air bed under everything

    private class Cue(val freqs: DoubleArray, val stepMs: Int)

    @Volatile private var cue: Cue? = null
    @Volatile private var hissTrigger = 0
    @Volatile private var thumpTrigger = 0
    @Volatile private var running = true
    private val rate = 44100

    /** Dispatch tone: a quick falling two-tone "dee-doot". */
    fun dispatchChime() { chimeTrigger++ }
    fun launchWarning() { cue = Cue(doubleArrayOf(1000.0, 0.0, 1000.0, 0.0, 1000.0), 120) }
    @Volatile private var chimeTrigger = 0
    /** Pneumatic exhaust (brake valve, air gate). */
    fun hiss() { hissTrigger++ }
    /** Mechanical clunk (restraints locking/unlocking). */
    fun thump(locking: Boolean = true) { thumpLocking = locking; thumpTrigger++ }
    @Volatile private var thumpLocking = true

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
        var hornPh = 0.0
        var hornEnv = 0.0
        var hornLp = 0.0
        var chimeSeen = 0
        var chimeT = -1.0
        var carrierPh = 0.0
        var roarLp = 0.0
        var lsmSpeed = 0.0
        var wooshHi = 0.0
        var wooshLo = 0.0
        var thumpT = -1.0
        var thumpLock = true
        var knockPh = 0.0
        var rumbleLp = 0.0

        // Ride sound state
        var liftAmp = 0.0
        var liftPh = 0.0
        var clackPh = 0.0
        var clackEnv = 0.0
        var clackT = -1.0
        var clackLp = 0.0
        val svLow = DoubleArray(6)
        val svBand = DoubleArray(6)
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

        // Realism: per-event variation (so repeats never sound identical), a short open-air reverb on the whole
        // mix, and an optional ambience bed.
        val rng = kotlin.random.Random(System.nanoTime())
        fun vary(spread: Double) = 1.0 + (rng.nextDouble() * 2 - 1) * spread
        // Anti-rollback: each click gets its own pitch, level, decay and slightly detuned steel modes.
        // Ten dense, short-lived modes: solid heavy steel rather than a few long rings (which sound like a tin can).
        val clackModeF = doubleArrayOf(160.0, 218.0, 280.0, 355.0, 425.0, 503.0, 593.0, 705.0, 823.0, 988.0)
        val clackModeA = doubleArrayOf(0.55, 0.5, 0.45, 0.4, 0.32, 0.26, 0.2, 0.14, 0.1, 0.07)
        val clackModeD = doubleArrayOf(0.034, 0.03, 0.026, 0.022, 0.018, 0.015, 0.012, 0.01, 0.008, 0.006)
        val cF = DoubleArray(10)
        val cD = DoubleArray(10)
        var cPitch = 1.0
        var cLevel = 1.0
        var cDecay = 1.0
        var clackNext = 1.0
        var pLevel = 1.0
        var pPitch = 1.0
        var pRing = 1.0
        var pBal = 1.0
        // Restraints: click spacing, thunk timing, pitch and level vary per operation.
        val clickAt = doubleArrayOf(0.0, 0.085, 0.17)
        var thunkAt = 0.47
        var tPitch = 1.0
        var tLevel = 1.0
        // Hiss: decay, level and brightness vary per valve operation.
        var hissDecay = 0.22
        var hissLevel = 1.0
        var hissCut = 0.5
        var hissLp = 0.0
        // Reverb: early reflections off the station roof/platform, then a short diffuse tail (Freeverb-style
        // damped combs into all-passes), sized for an open-sided station canopy.
        val erBuf = DoubleArray(2048)
        var erPos = 0
        val combLen = intArrayOf(797, 863, 929, 991)
        val combBuf = combLen.map { DoubleArray(it) }
        val combPos = IntArray(4)
        val combLp = DoubleArray(4)
        val apLen = intArrayOf(389, 241)
        val apBuf = apLen.map { DoubleArray(it) }
        val apPos = IntArray(2)
        // Ambience: slow gusting wind (low-passed noise) and faint high air.
        var windLp = 0.0
        var windLp2 = 0.0
        var airHi = 0.0
        var gustPh = rng.nextDouble() * 10

        while (running) {
            if (hissTrigger != hissSeen) {
                hissSeen = hissTrigger; hissEnv = 1.0
                hissDecay = 0.22 * vary(0.3); hissLevel = vary(0.15); hissCut = 0.35 + rng.nextDouble() * 0.4
            }
            if (thumpTrigger != thumpSeen) {
                thumpSeen = thumpTrigger; thumpT = 0.0; thumpLock = thumpLocking
                clickAt[1] = 0.085 * vary(0.15); clickAt[2] = clickAt[1] + 0.085 * vary(0.15)
                thunkAt = clickAt[2] + 0.3 * vary(0.2)
                tPitch = vary(0.06); tLevel = vary(0.12)
            }
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
                // Track clear: panel sounder, hi-lo an octave apart (A5 / A4), 450 ms each.
                val soft = activeCue == null && trackClear
                if (trackClear) { if (tcStart < 0) tcStart = n } else tcStart = -1
                val tcMs = if (tcStart >= 0) (n - tcStart) * 1000 / rate else 0L
                if (activeCue == null && trackClear) target = if ((tcMs / 450) % 2 == 0L) 880.0 else 440.0
                if (target > 0) freq = target
                // Soft swell for the sounder; short rounded edges for the launch beeps.
                val attack = if (soft) 0.0003 else 0.005
                env = if (target > 0) minOf(1.0, env + attack) else maxOf(0.0, env - 0.005)
                phase += 2 * PI * freq / rate
                if (phase > 2 * PI) phase -= 2 * PI
                val alarm = if (soft) (sin(phase) + 0.2 * sin(3 * phase) + 0.06 * sin(5 * phase)) / 1.15
                else (sin(phase) + 0.15 * sin(2 * phase)) / 1.15
                var mix = alarm * env * (if (soft) 0.5 else 0.3)

                // Fault horn: mechanical buzzer - a raspy, slightly clipped 360 Hz tone with a 60 Hz armature
                // rattle, rounded off by a low-pass so it buzzes rather than beeps. 400 ms on / 300 ms off.
                val hornOn = faultAlarm && activeCue == null && !trackClear && ms % 700 < 400
                hornEnv = if (hornOn) minOf(1.0, hornEnv + 0.004) else maxOf(0.0, hornEnv - 0.003)
                if (hornEnv > 0.0005) {
                    hornPh += 2 * PI * 360.0 * dt
                    if (hornPh > 2 * PI) hornPh -= 2 * PI
                    val rasp = tanh(2.5 * sin(hornPh)) * (0.75 + 0.25 * sin(2 * PI * 60.0 * n * dt))
                    hornLp += (rasp - hornLp) * 0.35
                    mix += hornLp * hornEnv * 0.32
                }

                // ---------------- ride sounds ----------------
                noise = noise * 1103515245 + 12345
                val wn = ((noise ushr 8) and 0xFFFF) / 32768.0 - 1.0
                hp = 0.97 * (hp + wn - lastN) // one-pole high-pass for hiss
                lastN = wn

                // Lift motor: low hum with a little ripple, smoothed amplitude.
                liftAmp += (liftLevel - liftAmp) * 0.0004
                if (liftAmp > 0.001) {
                    // Chain drive: motor hum plus a low mechanical rumble (low-passed noise).
                    liftPh += 2 * PI * (48.0 + 12.0 * liftAmp) * dt
                    rumbleLp += (wn - rumbleLp) * 0.02
                    mix += liftAmp * (0.11 * (sin(liftPh) + 0.4 * sin(2 * liftPh) + 0.15 * sin(3 * liftPh)) / 1.55 + 0.35 * rumbleLp)
                }

                // Anti-rollback: periodic clicks as the dogs pass each rack tooth.
                val cr = clackRateHz
                if (cr > 0.1) {
                    clackPh += cr * dt
                    if (clackPh >= clackNext) {
                        clackPh -= clackNext; clackEnv = 1.0; clackT = 0.0
                        clackNext = vary(0.06) // teeth and dog motion aren't perfectly even
                        cPitch = vary(0.05); cLevel = vary(0.15); cDecay = vary(0.15)
                        // The ping varies on its own too: each dog strikes the rack a little differently.
                        pLevel = vary(0.3); pPitch = vary(0.02); pRing = vary(0.2); pBal = vary(0.25)
                        for (k in 0 until 10) { cF[k] = clackModeF[k] * cPitch * vary(0.02); cD[k] = clackModeD[k] * cDecay }
                    }
                }
                var clackDry = 0.0
                var clackPing = 0.0
                if (clackT >= 0 && clackT < 0.12) {
                    // Steel dog striking the rack: a hard noise burst rung through two resonant steel bands (~950 Hz
                    // and ~2.3 kHz, state-variable band-passes) - noisy and unpitched, so it clacks rather than
                    // sounding like a tuned drum - with a short, quiet layer of dense steel modes. Kept dry.
                    val d = clackT
                    val burst = wn * exp(-d / (0.007 * cDecay))
                    // Bands: ~95 Hz broad (Q ~1.4, the weight of a heavy part); ~240 Hz, ~575 Hz and ~1.05 kHz kept
                    // fairly broad (Q ~4) so they add bite without ringing like glass; then the ping - ~6.5 kHz and
                    // ~8.9 kHz, narrow (Q ~12) - which is the only part sent to the reverb.
                    for (k in 0 until 6) {
                        val fc = doubleArrayOf(95.0, 238.0, 575.0, 1050.0, 6500.0, 8900.0)[k] * cPitch * (if (k >= 4) pPitch else 1.0)
                        val damp = when (k) { 0 -> 0.7; 4, 5 -> 0.085 / pRing; else -> 0.25 }
                        val g = 2 * sin(PI * fc / rate)
                        svLow[k] += g * svBand[k]
                        val high = burst - svLow[k] - damp * svBand[k]
                        svBand[k] += g * high
                    }
                    // Thud: low-passed noise (~200 Hz) with a fast decay - mass without a pitch.
                    clackLp += (wn * exp(-d / (0.012 * cDecay)) - clackLp) * 0.007
                    var ring = 0.0
                    for (k in 0 until 10) ring += clackModeA[k] * sin(2 * PI * cF[k] * d) * exp(-d / cD[k])
                    clackDry = 0.12 * cLevel * (1.8 * svBand[0] + 0.6 * svBand[1] + 0.9 * svBand[2] + 0.9 * svBand[3] +
                        0.15 * burst + 9.0 * clackLp + 0.05 * ring)
                    clackPing = 0.12 * cLevel * pLevel * (0.2 * svBand[4] + 0.1 * pBal * svBand[5])
                    clackT += dt
                }

                // LSM: rising electrical whine while the launch fires.
                val lv = lsmLevel
                lsmAmp += ((if (lv >= 0) 1.0 else 0.0) - lsmAmp) * 0.0002
                if (lv >= 0) lsmFreq = lv
                if (lsmAmp > 0.001) {
                    // LSM launch, three layers driven by train speed (0..1 of launch speed):
                    // Speed arrives from the UI every 40 ms; glide toward it per sample so the pitch sweeps smoothly.
                    lsmSpeed += (lsmFreq - lsmSpeed) * 0.00012
                    val speed = lsmSpeed
                    fun lp(fc: Double) = 1 - exp(-2 * PI * fc * dt) // one-pole low-pass coefficient

                    // 1. Rising hum / whine: the drive's AC frequency climbs with speed - a smooth tone with a little
                    //    3rd harmonic, sweeping ~180 Hz -> ~1.03 kHz.
                    val whineF = 180.0 + 850.0 * speed
                    lsmPh += 2 * PI * whineF * dt
                    if (lsmPh > 2 * PI) lsmPh -= 2 * PI
                    val whine = (sin(lsmPh) + 0.25 * sin(2 * lsmPh) + 0.12 * sin(3 * lsmPh)) / 1.37

                    // 2. Magnetic roar / buzz: the stator fins ring each time a train magnet passes, so a gritty buzz
                    //    at the pole-passing rate (~35 Hz -> ~330 Hz) chops low-passed noise into a roar.
                    val poleF = 35.0 + 295.0 * speed
                    carrierPh += 2 * PI * poleF * dt
                    if (carrierPh > 2 * PI) carrierPh -= 2 * PI
                    val pulse = 0.5 + 0.5 * tanh(3.0 * sin(carrierPh))
                    roarLp += (wn - roarLp) * lp(350.0 + 900.0 * speed)
                    val roar = roarLp * (0.35 + 0.65 * pulse) * 2.2 + 0.25 * tanh(2.0 * sin(carrierPh))

                    // 3. Woosh: air rushing past the accelerating train - band-passed noise that brightens and grows
                    //    roughly with speed squared.
                    wooshHi += (wn - wooshHi) * lp(400.0 + 2600.0 * speed)
                    wooshLo += (wooshHi - wooshLo) * lp(120.0 + 300.0 * speed)
                    val woosh = (wooshHi - wooshLo) * 2.5

                    // The noise layers stay near their starting level instead of swamping the whine at speed.
                    mix += lsmAmp * (0.05 * whine * (0.4 + 0.6 * speed) + 0.12 * roar * (0.35 + 0.3 * speed) +
                        0.06 * woosh * (0.35 + 0.65 * speed * speed))
                }

                // Dispatch tone: a quick falling two-tone "dee-doot" from the panel sounder - 1100 Hz for 130 ms,
                // a 20 ms gap, then 820 Hz for 200 ms. Slightly hollow (odd harmonics) with fast edges.
                if (chimeTrigger != chimeSeen) { chimeSeen = chimeTrigger; chimeT = 0.0 }
                if (chimeT >= 0) {
                    val t = chimeT
                    fun tone(f: Double, start: Double, len: Double): Double {
                        val d = t - start
                        if (d < 0 || d > len) return 0.0
                        val edge = minOf(1.0, d / 0.004, (len - d) / 0.012)
                        val ph = 2 * PI * f * d
                        return edge * (sin(ph) + 0.3 * sin(3 * ph) + 0.1 * sin(5 * ph)) / 1.4
                    }
                    mix += 0.3 * (tone(1100.0, 0.0, 0.13) + tone(820.0, 0.15, 0.2))
                    chimeT += dt
                    if (chimeT > 0.4) chimeT = -1.0
                }

                // Pneumatic hiss.
                if (hissEnv > 0.001) {
                    hissLp += (hp - hissLp) * hissCut
                    mix += hissEnv * 0.25 * hissLevel * hissLp
                    hissEnv *= exp(-dt / hissDecay)
                }

                // Restraints. Locking: the pawls ratchet over three teeth (click, click, click), a pause, then the
                // bar bottoms out (thunk). Unlocking: the pawls are pulled clear in one solid clunk.
                if (thumpT >= 0) {
                    val t = thumpT
                    fun click(at: Double): Double {
                        val d = t - at
                        if (d < 0 || d > 0.03) return 0.0
                        val f = 3800.0 * tPitch
                        return wn * exp(-d / 0.0012) * 0.5 + (0.3 * sin(2 * PI * 0.76 * f * d) + 0.5 * sin(2 * PI * f * d) +
                            0.2 * sin(2 * PI * 1.37 * f * d)) * exp(-d / 0.004)
                    }
                    fun knock(at: Double, weight: Double): Double {
                        val d = t - at
                        if (d < 0 || d > 0.4) return 0.0
                        knockPh += 2 * PI * tPitch * (50.0 + 60.0 * exp(-d / 0.02)) * dt
                        return weight * (sin(knockPh) * exp(-d / 0.07) + 0.6 * wn * exp(-d / 0.006))
                    }
                    mix += 0.3 * tLevel * if (thumpLock) click(clickAt[0]) + click(clickAt[1]) + click(clickAt[2]) + knock(thunkAt, 1.0)
                    else knock(0.0, 0.8) + 0.5 * click(0.012)
                    thumpT += dt
                    if (thumpT > 1.0) { thumpT = -1.0; knockPh = 0.0 }
                }

                mix += clackPing // the anti-rollback ping alone picks up the station reverb

                // Ambience bed: slow gusting wind with a faint layer of high air.
                if (ambience) {
                    gustPh += dt
                    val gust = 0.55 + 0.25 * sin(2 * PI * 0.07 * gustPh) + 0.2 * sin(2 * PI * 0.17 * gustPh + 1.3)
                    windLp += (wn - windLp) * 0.008
                    windLp2 += (windLp - windLp2) * 0.02
                    airHi += (hp - airHi) * 0.3
                    mix += gust * (0.09 * windLp2 * 4.0 + 0.004 * airHi)
                }

                // Reverb: early reflections (11 / 19 / 29 ms) then a short damped tail; ~15 % wet.
                erBuf[erPos] = mix
                fun tap(ms: Int) = erBuf[(erPos - ms * rate / 1000 + erBuf.size) % erBuf.size]
                val early = 0.22 * tap(11) + 0.15 * tap(19) + 0.1 * tap(29)
                erPos = (erPos + 1) % erBuf.size
                var tail = 0.0
                for (k in 0 until 4) {
                    val b = combBuf[k]
                    val out = b[combPos[k]]
                    combLp[k] = out * 0.65 + combLp[k] * 0.35
                    b[combPos[k]] = mix * 0.25 + combLp[k] * 0.72
                    combPos[k] = (combPos[k] + 1) % combLen[k]
                    tail += out
                }
                for (k in 0 until 2) {
                    val b = apBuf[k]
                    val buf = b[apPos[k]]
                    val inp = tail
                    tail = -inp + buf
                    b[apPos[k]] = inp + buf * 0.5
                    apPos[k] = (apPos[k] + 1) % apLen[k]
                }
                mix += early + 0.15 * tail + clackDry

                chunk[i] = (mix * volume * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            track.write(chunk, 0, chunk.size)
        }
        track.stop()
        track.release()
    }.apply { isDaemon = true; start() }

    fun release() { running = false }
}

/**
 * TEMPORARY debug: play the ride sounds without running a ride (INSTRUCTOR page, SOUND TEST).
 * Held sounds stay on until switched off; one-shots fire on [audio]. Ignores the per-sound enables.
 */
object SoundTest {
    @Volatile var trackClear = false
    @Volatile var horn = false
    @Volatile var lift = false
    @Volatile var clack = false
    @Volatile var lsm = false
    /** When the LSM test started; the whine sweeps from standstill to full speed over 4 s, then holds. */
    @Volatile var lsmStartMs = 0L
    @Volatile var audio: RideAudio? = null

    fun allOff() { trackClear = false; horn = false; lift = false; clack = false; lsm = false }
}

/** Watches the simulation and drives [RideAudio]. */
@Composable
fun RideSounds(vm: SimViewModel) {
    val audio = remember { RideAudio() }
    DisposableEffect(Unit) { SoundTest.audio = audio; onDispose { SoundTest.audio = null; audio.release() } }
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
            audio.ambience = on(SoundKind.AMBIENCE)

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
            if (plant.restraintUnlockCmd != lastUnlock && on(SoundKind.STATION_PNEUMATICS)) audio.thump(locking = !plant.restraintUnlockCmd)
            lastGates = plant.gatesOpenCmd
            lastUnlock = plant.restraintUnlockCmd

            if (on(SoundKind.DISPATCH) && plc.dispatchCount > lastDispatch) audio.dispatchChime()
            if (on(SoundKind.LAUNCH_WARNING) && plc.launchState == LaunchState.CHARGING && lastLaunch != LaunchState.CHARGING) audio.launchWarning()
            lastDispatch = plc.dispatchCount
            lastLaunch = plc.launchState

            // Debug sound test overrides.
            if (SoundTest.trackClear) audio.trackClear = true
            if (SoundTest.horn) audio.faultAlarm = true
            if (SoundTest.lift) audio.liftLevel = 1.0
            if (SoundTest.clack) audio.clackRateHz = 5.0
            if (SoundTest.lsm) audio.lsmLevel = ((System.currentTimeMillis() - SoundTest.lsmStartMs) / 4000.0).coerceIn(0.0, 1.0)
        }
    }
}
