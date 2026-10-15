package com.abemu.coaster.sim

/**
 * Ride operations radio channel: the supervisor and maintenance call the operator when things happen on the ride.
 * Watches the PLC each scan and queues pre-recorded clips (ids from spiels/radio.txt); the UI plays them in order.
 */
class RadioDesk {
    private class Pending(val due: Double, val clip: String)

    private val pending = mutableListOf<Pending>()
    private val lastSaid = HashMap<String, Double>()
    /** Transmissions so far, newest first: (sim time, clip id). */
    val log = ArrayDeque<Pair<Double, String>>()
    private var t = 0.0

    private var plcRef: RidePlc? = null
    private var prevState = RideState.POWER_OFF
    private var opened = false
    private var seenAlarms = emptySet<String>()
    private var maintCalled = false
    private var downT = 0.0
    private var prevWind = false
    private var prevLightning = false
    private var prevArmed = false
    private var stackCalled = false
    private var lastHistory = 0L
    private var chatterT = 120.0
    private val chatterBag = mutableListOf<List<Pair<String, Double>>>()

    /** Next clip due for playback, removed from the queue; null when nothing is due. */
    fun next(): String? {
        val p = pending.filter { it.due <= t }.minByOrNull { it.due } ?: return null
        pending.remove(p)
        log.addFirst(t to p.clip)
        while (log.size > 30) log.removeLast()
        return p.clip
    }

    /** Queue [clip] after [delay] s unless it was said in the last [quiet] s. */
    fun say(clip: String, delay: Double = 0.0, quiet: Double = 90.0) {
        val last = lastSaid[clip]
        if (last != null && t - last < quiet) return
        if (pending.any { it.clip == clip }) return
        lastSaid[clip] = t
        pending += Pending(t + delay, clip)
    }

    fun update(dt: Double, plc: RidePlc, on: Boolean) {
        t += dt
        if (plc !== plcRef) {
            plcRef = plc
            pending.clear()
            prevState = plc.state; opened = false; seenAlarms = emptySet(); maintCalled = false
            prevWind = plc.weather.windHold; prevLightning = plc.weather.lightningHold
            prevArmed = plc.relaunchArmed; stackCalled = false; lastHistory = plc.alarms.historyVersion
            return
        }
        if (!on) { pending.clear(); return }
        val st = plc.state
        val running = st == RideState.RUNNING

        if (st == RideState.POWER_OFF) opened = false
        if (running && !opened) { opened = true; if (prevState == RideState.STARTING) say("opening", 2.0, 1800.0) }
        if (st == RideState.ESTOP && prevState != RideState.ESTOP) {
            say("estop", 3.0, 120.0)
            say("estop_maint", 14.0, 120.0)
        }

        // New stop-level faults: maintenance responds to the first of each kind.
        val active = plc.alarms.active
        val codes = active.map { it.code }.toSet()
        for (code in codes - seenAlarms) {
            val clip = maintClip(code) ?: continue
            if (clip == "rollback") {
                say("rollback", 2.0)
                say("rollback_maint", 12.0)
            } else say(clip, 6.0)
            maintCalled = true
        }
        seenAlarms = codes

        if (plc.relaunchArmed && !prevArmed) { say("rollback_clear", 1.0, 30.0); maintCalled = false }
        prevArmed = plc.relaunchArmed

        // Back up and running after a fault: maintenance signs off, or the supervisor notes the ride is back.
        val faulted = active.any { it.severity.ordinal <= Severity.CSTOP.ordinal }
        downT = if (running) downT else downT + dt
        if (running && prevState != RideState.RUNNING && opened && !faulted) {
            when {
                maintCalled -> say("maint_clear", 3.0, 60.0)
                downT > 90 -> say("resume", 4.0, 300.0)
            }
            maintCalled = false
        }
        if (running) downT = 0.0

        // Weather.
        val wx = plc.weather
        if (wx.windHold && !prevWind) say("wind_hold", 2.0, 300.0)
        if (wx.lightningHold && !prevLightning) say("lightning_hold", 2.0, 300.0)
        if ((prevWind || prevLightning) && !wx.hold) say("weather_clear", 3.0, 300.0)
        prevWind = wx.windHold; prevLightning = wx.lightningHold

        // Slow dispatching: trains stacked on the brakes for a while.
        if (running && plc.stackT > 30 && !stackCalled) {
            stackCalled = true
            val first = lastSaid["stacking"]
            if (first != null && t - first < 600) say("stacking_2", 0.0, 120.0) else say("stacking", 0.0, 120.0)
        }
        if (plc.stackT == 0.0) stackCalled = false

        // A train dispatched with a seatbelt undone (logged by the PLC at dispatch).
        if (plc.alarms.historyVersion != lastHistory) {
            lastHistory = plc.alarms.historyVersion
            if (plc.alarms.history.firstOrNull()?.code == "CHECK") say("belt", 8.0, 120.0)
        }
        // General park chatter while the ride is up and nothing is wrong.
        if (running && !faulted && pending.isEmpty()) {
            chatterT -= dt
            if (chatterT <= 0) {
                chatterT = 150 + Math.random() * 270
                if (chatterBag.isEmpty()) chatterBag += CHATTER.shuffled()
                var delay = 0.0
                chatterBag.removeAt(0).forEach { (clip, gap) -> delay += gap; say(clip, delay, 0.0) }
            }
        }
        prevState = st
    }

    companion object {
        /** Chatter conversations: clip ids with the gap (s) before each; replies follow their call. */
        private val CHATTER: List<List<Pair<String, Double>>> = listOf(
            listOf("chatter_1" to 0.0, "chatter_2" to 1.0),
            listOf("chatter_3" to 0.0, "chatter_4" to 150.0),
            listOf("chatter_9" to 0.0, "chatter_10" to 1.5),
            listOf("chatter_16" to 0.0, "chatter_17" to 1.0),
            listOf("chatter_20" to 0.0, "chatter_21" to 1.0),
            listOf("chatter_23" to 0.0, "chatter_24" to 2.0),
        ) + listOf(5, 6, 7, 8, 11, 12, 13, 14, 15, 18, 19, 22, 25, 26, 27, 28, 29, 30).map { listOf("chatter_$it" to 0.0) }

        /** Maintenance clip for a newly active alarm code, or null if it is not a maintenance call. */
        fun maintClip(code: String): String? = when {
            code == "ROLLBACK" -> "rollback"
            code.startsWith("BRK") -> "maint_brakes"
            code == "LIFT_VFD" || code == "LIFT_OL" -> "maint_lift"
            code == "LSM" -> "maint_launch"
            code == "AIR" -> "maint_air"
            code == "SW_POS" || code == "SW_OVR" -> "maint_switch"
            code.startsWith("STALL") || code.startsWith("UNEXP") || code.startsWith("OVR") -> "maint_sensor"
            code.startsWith("SAFE") -> "maint_safety"
            code.startsWith("ROW") -> "maint_restraint"
            else -> null
        }
    }
}
