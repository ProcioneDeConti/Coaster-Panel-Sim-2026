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
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the simulated controller and runs it in real time: fixed 20 ms PLC scans with the
 * physics stepped once per scan, caught up once per display frame. Everything runs on the main thread, so the UI can read PLC
 * fields directly; [frame] ticks each update to drive recomposition.
 */
/** HMI login levels. PINs are fixed for this emulator. */
enum class AccessLevel(val label: String, val pin: String?) {
    OPERATOR("OPERATOR", null), MAINTENANCE("MAINTENANCE", "2580"), INSTRUCTOR("INSTRUCTOR", "1234");

    companion object {
        fun forPin(pin: String) = entries.firstOrNull { it.pin == pin }
    }
}

/** Screen arrangements for large tablets. */
enum class ScreenLayout(val label: String) {
    HMI_CONSOLE("HMI + CONSOLE"), HMI_MAP_CONSOLE("HMI + MAP + CONSOLE"), MAP_CONSOLE("MAP + CONSOLE");
    fun next() = entries[(ordinal + 1) % entries.size]
}

class SimViewModel : ViewModel() {
    var layout by mutableStateOf(ScreenLayout.HMI_CONSOLE)
    /** Serves the live map to browsers on other devices. */
    val mapServer = com.abemu.coaster.web.MapServer()
    var webMapOn by mutableStateOf(false); private set

    fun setWebMap(on: Boolean) {
        if (on) mapServer.start() else mapServer.stop()
        webMapOn = mapServer.running
    }

    override fun onCleared() { mapServer.stop() }

    /** Loads editor tags for a coaster; set by the activity (tags live in app storage). */
    var tagLoader: (String) -> List<LayoutTag> = { emptyList() }
    /** Apply editor tags to the block layout when the ride is (re)built. */
    var useTags by mutableStateOf(true)
    /** What the last build did with each tag. */
    var tagReport by mutableStateOf(emptyList<String>()); private set
    /** Tags the last build couldn't use. */
    var tagRejected by mutableStateOf(emptySet<LayoutTag>()); private set
    var coasterId by mutableStateOf(CoasterLibrary.default(ProfileType.LIFT).id); private set
    var trainCount by mutableIntStateOf(3); private set
    var plc by mutableStateOf(build(coasterId, 3)); private set
    var timeScale by mutableDoubleStateOf(1.0)
    var soundOn by mutableStateOf(true)
    /** Per-sound on/off; missing entries count as on. */
    val soundEnabled = mutableStateMapOf<com.abemu.coaster.ui.SoundKind, Boolean>()
    var volume by mutableFloatStateOf(0.5f)
    var hapticsOn by mutableStateOf(true)
    val frame = mutableLongStateOf(0L)
    val trends = com.abemu.coaster.sim.TrendRecorder()
    var access by mutableStateOf(AccessLevel.OPERATOR)
    /** Training drill in progress (or just finished, until dismissed). */
    var drill by mutableStateOf<com.abemu.coaster.sim.Drill?>(null); private set
    val drillResults = androidx.compose.runtime.mutableStateListOf<com.abemu.coaster.sim.DrillResult>()

    private val scanDt = 0.02

    init {
        viewModelScope.launch {
            var last = System.nanoTime()
            var acc = 0.0
            while (true) {
                // Step once per display frame (60/120 Hz); the timeout keeps the sim running at
                // ~20 Hz when no frames come (app in background, screen off) for the web map.
                withTimeoutOrNull(50) { awaitFrame() }
                val now = System.nanoTime()
                acc = (acc + (now - last) / 1e9 * timeScale).coerceAtMost(0.5)
                last = now
                while (acc >= scanDt) {
                    plc.scan(scanDt)
                    plc.plant.step(scanDt)
                    trends.update(plc, scanDt)
                    drill?.let { d ->
                        val was = d.finished
                        d.update(scanDt)
                        if (d.finished && !was) drillResults.add(0, d.result())
                    }
                    acc -= scanDt
                }
                mapServer.publish(plc, drill)
                frame.longValue++
            }
        }
    }

    private fun profile(id: String): RideProfile {
        val base = RideProfile.create(CoasterLibrary.byId(id))
        if (!useTags) { tagReport = emptyList(); tagRejected = emptySet(); return base }
        val tagged = TagLayout.apply(base, tagLoader(id))
        tagReport = tagged.report
        tagRejected = tagged.rejected
        return tagged.profile
    }

    private fun build(id: String, trains: Int) = profile(id).let { p ->
        RidePlc(Plant(p, trains.coerceAtMost(p.initialStops.size.coerceAtMost(3))))
    }

    /**
     * Start a training drill: rebuild the scenario's ride, run it headlessly to the scenario's trigger
     * point, then hand over to the trainee with the fault injected.
     */
    fun startDrill(sc: com.abemu.coaster.sim.Scenario) {
        val id = CoasterLibrary.default(sc.type).id
        val fresh = build(id, sc.trains)
        fresh.dispatchTarget = plc.dispatchTarget
        fresh.inputs.singleButtonDispatch = plc.inputs.singleButtonDispatch
        if (sc.warmDispatches >= 0) com.abemu.coaster.sim.Autopilot.warmUp(fresh, sc.warmDispatches, sc.trigger)
        coasterId = id
        trainCount = fresh.plant.trains.size
        trends.clear()
        plc = fresh
        drill = com.abemu.coaster.sim.Drill(sc, fresh).also { it.begin() }
    }

    fun abortDrill() {
        drill?.let { d -> if (!d.finished) { d.abort(); drillResults.add(0, d.result()) } }
    }

    fun dismissDrill() { if (drill?.finished == true) drill = null }

    /** Change ride profile / train count. Only allowed with ride power off, like a real config change. */
    fun reconfigure(id: String = coasterId, trains: Int = trainCount): Boolean {
        if (plc.state != RideState.POWER_OFF) return false
        if (drill?.finished == false) return false
        drill = null
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
