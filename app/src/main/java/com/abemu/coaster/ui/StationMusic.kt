package com.abemu.coaster.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.RideState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Gap between the end of one play of the station theme and the start of the next. */
private const val GAP_MS = 30_000L
/** Fade in at the start of each play and out at its end, in ms. */
private const val FADE_MS = 3_000f
/** Fade out when the music is switched off mid-play. */
private const val STOP_FADE_MS = 1_500f
/** Music level while a PA announcement plays. */
private const val DUCK = 0.25f
/** Music level while a radio call is on air: a slight dip so the call stands out. */
private const val RADIO_DUCK = 0.6f
private const val TICK_MS = 50L

/**
 * Loops the current ride's station theme (assets/station_music/<rideId>.mp3) with a 30 s gap between plays,
 * fading in and out and ducking under PA announcements. Rides without a theme stay silent; switching ride,
 * an E-stop, powering the ride off, turning the sound off or leaving the app stops it.
 */
@Composable
fun StationMusic(vm: SimViewModel) {
    val context = LocalContext.current
    val rideId = vm.plc.profile.spec.id
    val visible = rememberAppVisible()
    vm.frame.longValue // re-check the ride state each update
    // Music starts once the ride is up and running (RIDE START lit solid) and plays until ride power goes off.
    var rideOpen by remember(vm.plc) { mutableStateOf(false) }
    when (vm.plc.state) {
        RideState.RUNNING -> rideOpen = true
        RideState.POWER_OFF -> rideOpen = false
        else -> {}
    }
    val enabled = visible && rideOpen && vm.plc.state != RideState.ESTOP &&
        vm.soundOn && vm.soundEnabled[SoundKind.STATION_MUSIC] != false
    LaunchedEffect(rideId, enabled) {
        if (!enabled) return@LaunchedEffect
        val file = "station_music/$rideId.mp3"
        val files = runCatching { context.assets.list("station_music")?.toSet() }.getOrNull().orEmpty()
        if ("$rideId.mp3" !in files) return@LaunchedEffect
        val player = runCatching {
            context.assets.openFd(file).use { afd ->
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
            }
        }.onFailure { Log.w("StationMusic", "can't load $file", it) }.getOrNull() ?: return@LaunchedEffect
        var duck = 1f
        var level = 0f
        fun gain(): Float {
            // Fade by position in the track: up over the first FADE_MS, down over the last FADE_MS.
            val pos = player.currentPosition.toFloat()
            val left = (player.duration - player.currentPosition).toFloat()
            val fade = minOf(1f, pos / FADE_MS, left / FADE_MS).coerceIn(0f, 1f)
            // Duck smoothly (~0.5 s) rather than snapping when the PA starts or stops.
            val target = when {
                PaBus.speaking -> DUCK
                RadioState.onAir != null -> RADIO_DUCK
                else -> 1f
            }
            duck += (target - duck) * 0.1f
            return vm.volume * fade * duck
        }
        try {
            while (true) {
                player.seekTo(0)
                player.setVolume(0f, 0f)
                player.start()
                while (player.isPlaying) {
                    level = gain()
                    player.setVolume(level, level)
                    delay(TICK_MS)
                }
                delay(GAP_MS)
            }
        } finally {
            // Switched off mid-play: fade out instead of cutting off.
            withContext(NonCancellable) {
                runCatching {
                    if (player.isPlaying) {
                        val steps = (STOP_FADE_MS / TICK_MS).toInt()
                        for (k in steps downTo 0) { val v = level * k / steps; player.setVolume(v, v); delay(TICK_MS) }
                    }
                }
                player.release()
            }
        }
    }
}
