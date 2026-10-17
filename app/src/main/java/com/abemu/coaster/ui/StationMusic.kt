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
import kotlinx.coroutines.delay

/** Gap between the end of one play of the station theme and the start of the next. */
private const val GAP_MS = 30_000L

/**
 * Loops the current ride's station theme (assets/station_music/<rideId>.mp3) with a 30 s gap between plays.
 * Rides without a theme stay silent; switching ride, powering the ride off or turning the sound off stops it.
 */
@Composable
fun StationMusic(vm: SimViewModel) {
    val context = LocalContext.current
    val rideId = vm.plc.profile.spec.id
    vm.frame.longValue // re-check the ride state each update
    // Music starts once the ride is up and running (RIDE START lit solid) and plays until ride power goes off.
    var rideOpen by remember(vm.plc) { mutableStateOf(false) }
    when (vm.plc.state) {
        RideState.RUNNING -> rideOpen = true
        RideState.POWER_OFF -> rideOpen = false
        else -> {}
    }
    val enabled = rideOpen && vm.soundOn && vm.soundEnabled[SoundKind.STATION_MUSIC] != false
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
        try {
            while (true) {
                player.seekTo(0)
                player.start()
                // Track the master volume while playing.
                while (player.isPlaying) {
                    player.setVolume(vm.volume, vm.volume)
                    delay(200)
                }
                delay(GAP_MS)
            }
        } finally {
            player.release()
        }
    }
}
