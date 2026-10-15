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

/** Park lighting setting. AUTO follows the tablet's clock (night from 20:00 to 07:00). */
enum class Lighting(val label: String) { DAY("DAY"), NIGHT("NIGHT"), AUTO("AUTO (CLOCK)") }

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
    /** Park weather (persists across ride changes) and this ride's line. */
    val weather = com.abemu.coaster.sim.Weather()
    val queue = com.abemu.coaster.sim.GuestQueue()
    /** Shows debug-only panels (sound test) on the INSTRUCTOR page. */
    var debugTools by mutableStateOf(false)
    /** Operators must walk the train (check every car) before the load side goes ready. */
    var restraintWalk by mutableStateOf(false)
    /** Park lighting: night operation dims the HMI and console, makes lamps glow and lights the track on the plan view. */
    var lighting by mutableStateOf(Lighting.DAY)
    val night: Boolean get() = when (lighting) {
        Lighting.DAY -> false
        Lighting.NIGHT -> true
        Lighting.AUTO -> java.time.LocalTime.now().hour.let { it >= 20 || it < 7 }
    }
    /** Radio chatter from the supervisor and maintenance. */
    var radioOn by mutableStateOf(true)
    val radio = com.abemu.coaster.sim.RadioDesk()
    var access by mutableStateOf(AccessLevel.OPERATOR)
    /** Training drill in progress (or just finished, until dismissed). */
    var drill by mutableStateOf<com.abemu.coaster.sim.Drill?>(null); private set
    val drillResults = androidx.compose.runtime.mutableStateListOf<com.abemu.coaster.sim.DrillResult>()

    private val scanDt = 0.02

    init {
        viewModelScope.launch {
            var last = System.nanoTime()
            var acc = 0.0
            var lastUi = 0L
            while (true) {
                // Fixed 30 Hz tick, not the display rate: the sim catches up in 20 ms scans, and redrawing
                // at 120 Hz kept the main thread and GPU busy full-time (more than a whole core at idle).
                delay(33)
                val now = System.nanoTime()
                acc = (acc + (now - last) / 1e9 * timeScale).coerceAtMost(0.5)
                last = now
                while (acc >= scanDt) {
                    weather.update(scanDt)
                    plc.weather.windMph = weather.windMph
                    plc.weather.strikeMi = weather.strikeMi
                    plc.plant.windMs = weather.windMph * 0.44704
                    plc.plant.windFromDeg = weather.dirDeg
                    plc.restraintWalk = restraintWalk
                    plc.scan(scanDt)
                    plc.plant.step(scanDt)
                    trends.update(plc, scanDt)
                    queue.update(scanDt, plc)
                    radio.update(scanDt, plc, radioOn, night, queue.postedWaitMin, weather.windMph)
                    drill?.let { d ->
                        val was = d.finished
                        d.update(scanDt)
                        if (d.finished && !was) drillResults.add(0, d.result())
                    }
                    acc -= scanDt
                }
                mapServer.publish(plc, drill)
                com.abemu.coaster.ui.Night.on = night
                // Redraw at 30 fps while something moves, otherwise ~10 fps (enough for lamp flashing and
                // HMI values). Taps on Compose state still recompose immediately.
                val moving = plc.plant.trains.any { kotlin.math.abs(it.v) > 0.01 } ||
                    plc.plant.gatePos in 0.001..0.999
                if (moving || now - lastUi >= 100_000_000L) { lastUi = now; frame.longValue++ }
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
        RidePlc(Plant(p, trains.coerceAtMost(p.maxTrains)))
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
        queue.reset()
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
        if (id != coasterId) queue.reset()
        coasterId = id
        trainCount = fresh.plant.trains.size
        plc = fresh
        trends.clear()
        return true
    }
}
