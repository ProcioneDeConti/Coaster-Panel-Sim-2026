package com.abemu.coaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.LayoutTag
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.TagLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the simulated controller and runs it in real time: fixed 20 ms PLC scans with the
 * physics stepped once per scan. Everything runs on the main thread, so the UI can read PLC
 * fields directly; [frame] ticks each update to drive recomposition.
 */
/** HMI login levels. PINs are fixed for this emulator. */
enum class AccessLevel(val label: String, val pin: String?) {
    OPERATOR("OPERATOR", null), MAINTENANCE("MAINTENANCE", "2580"), INSTRUCTOR("INSTRUCTOR", "1234");

    companion object {
        fun forPin(pin: String) = entries.firstOrNull { it.pin == pin }
    }
}

class SimViewModel : ViewModel() {
    /** Loads editor tags for a coaster; set by the activity (tags live in app storage). */
    var tagLoader: (String) -> List<LayoutTag> = { emptyList() }
    /** Apply editor tags to the block layout when the ride is (re)built. */
    var useTags by mutableStateOf(true)
    /** What the last build did with each tag. */
    var tagReport by mutableStateOf(emptyList<String>()); private set
    var coasterId by mutableStateOf(CoasterLibrary.default(ProfileType.LIFT).id); private set
    var trainCount by mutableIntStateOf(3); private set
    var plc by mutableStateOf(build(coasterId, 3)); private set
    var timeScale by mutableDoubleStateOf(1.0)
    var soundOn by mutableStateOf(true)
    /** Per-sound on/off; missing entries count as on. */
    val soundEnabled = mutableStateMapOf<com.abemu.coaster.ui.SoundKind, Boolean>()
    var volume by mutableFloatStateOf(0.5f)
    val frame = mutableLongStateOf(0L)
    val trends = com.abemu.coaster.sim.TrendRecorder()
    var access by mutableStateOf(AccessLevel.OPERATOR)

    private val scanDt = 0.02

    init {
        viewModelScope.launch {
            var last = System.nanoTime()
            var acc = 0.0
            while (true) {
                delay(16)
                val now = System.nanoTime()
                acc = (acc + (now - last) / 1e9 * timeScale).coerceAtMost(0.5)
                last = now
                while (acc >= scanDt) {
                    plc.scan(scanDt)
                    plc.plant.step(scanDt)
                    trends.update(plc, scanDt)
                    acc -= scanDt
                }
                frame.longValue++
            }
        }
    }

    private fun profile(id: String): RideProfile {
        val base = RideProfile.create(CoasterLibrary.byId(id))
        if (!useTags) { tagReport = emptyList(); return base }
        val tagged = TagLayout.apply(base, tagLoader(id))
        tagReport = tagged.report
        return tagged.profile
    }

    private fun build(id: String, trains: Int) = profile(id).let { p ->
        RidePlc(Plant(p, trains.coerceAtMost(p.initialStops.size.coerceAtMost(3))))
    }

    /** Change ride profile / train count. Only allowed with ride power off, like a real config change. */
    fun reconfigure(id: String = coasterId, trains: Int = trainCount): Boolean {
        if (plc.state != RideState.POWER_OFF) return false
        val old = plc.inputs
        val fresh = build(id, trains)
        fresh.inputs.mode = old.mode
        fresh.inputs.estopPressed = old.estopPressed
        fresh.inputs.singleButtonDispatch = old.singleButtonDispatch
        fresh.inputs.loadStandEstop = old.loadStandEstop
        fresh.inputs.unloadStandEstop = old.unloadStandEstop
        fresh.dispatchTarget = plc.dispatchTarget
        coasterId = id
        trainCount = fresh.plant.trains.size
        plc = fresh
        trends.clear()
        return true
    }
}
