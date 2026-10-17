package com.abemu.coaster.sim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ride operations radio channel: the supervisor and maintenance call the operator when things happen on the ride.
 * Watches the PLC each scan and queues pre-recorded clips (ids from spiels/radio.txt); the UI plays them in order.
 */
class RadioDesk {
    private class Pending(val due: Double, val clip: String)

    /** Something the operator can key up and say: their clip, then what the other end does about it. */
    class Reply(val label: String, val clip: String, val after: List<Pair<String, Double>> = emptyList(), val callsMaint: Boolean = false)

    private val pending = mutableListOf<Pending>()
    private val lastSaid = HashMap<String, Double>()
    /** Transmissions so far, newest first: (sim time, clip id). */
    val log = ArrayDeque<Pair<Double, String>>()
    private var t = 0.0

    private var plcRef: RidePlc? = null
    private var prevState = RideState.POWER_OFF
    private var opened = false
    private var openDispatches = 0L
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
    private val contextBags = HashMap<String, MutableList<List<Pair<String, Double>>>>()

    /** The call the operator is expected to answer (base clip id), and when it went out. Observable for the PTT lamp. */
    var prompt by mutableStateOf<String?>(null); private set
    private var promptT = 0.0

    /** Replies on offer right now: answers to [prompt] first, then the calls the operator can always make. */
    val replies: List<Reply> get() = (prompt?.let { REPLIES[it] } ?: emptyList()) + GENERAL

    fun reply(r: Reply) {
        pending.removeAll { it.clip.endsWith("_noreply") }
        prompt = null
        say(r.clip, 0.3, 0.0)
        var d = 0.3
        r.after.forEach { (clip, gap) -> d += gap; say(clip, d, 20.0) }
        if (r.callsMaint) maintCalled = true
    }

    /** Next clip due for playback, removed from the queue; null when nothing is due. */
    fun next(): String? {
        val p = pending.filter { it.due <= t }.minByOrNull { it.due } ?: return null
        pending.remove(p)
        log.addFirst(t to p.clip)
        if (p.clip in REPLIES) { prompt = p.clip; promptT = t }
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

    /** [night], [postedWaitMin] and [windMph] steer which park chatter fits the moment. */
    fun update(dt: Double, plc: RidePlc, on: Boolean, night: Boolean = false, postedWaitMin: Int = 0, windMph: Double = 0.0) {
        t += dt
        if (plc !== plcRef) {
            plcRef = plc
            pending.clear()
            prevState = plc.state; opened = false; seenAlarms = emptySet(); maintCalled = false
            prevWind = plc.weather.windHold; prevLightning = plc.weather.lightningHold
            prevArmed = plc.relaunchArmed; stackCalled = false; lastHistory = plc.alarms.historyVersion
            return
        }
        if (!on) { pending.clear(); prompt = null; return }
        // Unanswered calls: the supervisor chases the ones that need a status; anything else just lapses.
        prompt?.let { p ->
            if (t - promptT > 25) {
                prompt = null
                NOREPLY[p]?.let { say(it, 0.0, 120.0) }
            }
        }
        val st = plc.state
        val running = st == RideState.RUNNING

        if (st == RideState.POWER_OFF) opened = false
        // Only a return to running after the ride was already open counts as "back up".
        val wasOpen = opened
        if (running && !opened) { opened = true; openDispatches = plc.dispatchCount.toLong(); if (prevState == RideState.STARTING) say("opening", 2.0, 1800.0) }
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
        if (running && prevState != RideState.RUNNING && wasOpen && !faulted) {
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
        if (!wx.windHold && wx.windMph >= wx.windLimitMph - 4) say("wind_warn", 1.0, 900.0)
        wx.strikeMi?.let { if (it > wx.lightningMi && it <= 20 && !wx.lightningHold) say("storm_advise", 2.0, 900.0) }
        prevWind = wx.windHold; prevLightning = wx.lightningHold

        // Down with nothing on the board and nobody called: the supervisor wants to know why.
        if (opened && st == RideState.STOPPED && !faulted && !maintCalled && !wx.hold && downT > 150) say("down_query", 0.0, 600.0)

        // Slow dispatching: trains stacked on the brakes for a while.
        // Trains staged behind the station at start-up aren't stacking: wait until every train has gone round once.
        val cycled = plc.dispatchCount - openDispatches >= plc.plant.trains.size
        if (running && cycled && plc.stackT > 30 && !stackCalled) {
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
                // Half the time, pick something that fits the moment (night, a long line, a windy day).
                val moods = listOfNotNull("night".takeIf { night }, "busy".takeIf { postedWaitMin >= 45 }, "windy".takeIf { windMph >= 18 })
                val mood = moods.randomOrNull()?.takeIf { Math.random() < 0.5 }
                val bag = if (mood != null) contextBags.getOrPut(mood) { mutableListOf() } else chatterBag
                if (bag.isEmpty()) bag += (if (mood != null) CONTEXT_CHATTER.getValue(mood) else CHATTER).shuffled()
                var delay = 0.0
                bag.removeAt(0).forEach { (clip, gap) -> delay += gap; say(clip, delay, 0.0) }
            }
        }
        prevState = st
    }

    companion object {
        /** Chatter conversations: clip ids with the gap (s) before each; replies follow their call. */
        private val CHATTER: List<List<Pair<String, Double>>> = listOf(
            listOf("chatter_1" to 0.0, "chatter_2" to 1.0),
            listOf("chatter_3" to 0.0, "chatter_4" to 150.0),
            listOf("chatter_9" to 0.0, "chatter_10" to 1.5, "chatter_37" to 240.0),
            listOf("chatter_16" to 0.0, "chatter_17" to 1.0, "chatter_45" to 120.0),
            listOf("chatter_7" to 0.0, "chatter_38" to 240.0),
            listOf("chatter_27" to 0.0, "chatter_34" to 300.0),
            listOf("chatter_40" to 0.0, "chatter_41" to 1.5),
            listOf("chatter_42" to 0.0, "chatter_43" to 1.0),
            listOf("chatter_20" to 0.0, "chatter_21" to 1.0),
            listOf("chatter_23" to 0.0, "chatter_24" to 2.0),
        ) + listOf(5, 6, 8, 11, 12, 13, 14, 15, 18, 19, 22, 25, 26, 28, 29, 30, 31, 32, 33, 36, 39, 44)
            .map { listOf("chatter_$it" to 0.0) }

        private val CONTEXT_CHATTER: Map<String, List<List<Pair<String, Double>>>> = mapOf(
            "night" to listOf(listOf("chatter_n1" to 0.0), listOf("chatter_n2" to 0.0), listOf("chatter_n3" to 0.0),
                listOf("chatter_n4" to 0.0), listOf("chatter_n5" to 0.0), listOf("chatter_n6" to 0.0, "chatter_n7" to 1.5)),
            "busy" to listOf(listOf("chatter_b1" to 0.0), listOf("chatter_b2" to 0.0), listOf("chatter_b3" to 0.0, "chatter_b4" to 1.5),
                listOf("chatter_b5" to 0.0)),
            "windy" to listOf(listOf("chatter_w1" to 0.0), listOf("chatter_w2" to 0.0), listOf("chatter_w3" to 0.0, "chatter_w4" to 1.5),
                listOf("chatter_w5" to 0.0)),
        )

        private val COPY = Reply("COPY", "op_copy")
        private val NEED_MAINT = Reply("NEED MAINTENANCE", "op_need_maint", listOf("maint_request" to 4.0), callsMaint = true)
        private val STACK = listOf(Reply("PICKING IT UP", "op_stacking"), Reply("SLOW LOAD, CATCHING UP", "op_slow_load"))

        /** Calls that expect an answer from the operator, and what they can say back. */
        val REPLIES: Map<String, List<Reply>> = mapOf(
            "opening" to listOf(Reply("OPENING NOW", "op_open")),
            "estop" to listOf(Reply("ALL OK, RESETTING", "op_estop_ok"), NEED_MAINT),
            "estop_noreply" to listOf(Reply("ALL OK, RESETTING", "op_estop_ok"), NEED_MAINT),
            "down_query" to listOf(Reply("WORKING A FAULT", "op_down_fault"), NEED_MAINT),
            "down_noreply" to listOf(Reply("WORKING A FAULT", "op_down_fault"), NEED_MAINT),
            "stacking" to STACK,
            "stacking_2" to STACK,
            "belt" to listOf(Reply("WILL RECHECK SEATS", "op_belt")),
            "wind_hold" to listOf(Reply("HOLDING DISPATCHES", "op_hold")),
            "lightning_hold" to listOf(Reply("HOLDING, QUEUE CLOSED", "op_lightning")),
            "weather_clear" to listOf(Reply("RESUMING", "op_resume")),
            "rollback" to listOf(Reply("GATES CLOSED, HOLDING", "op_rollback")),
            "wind_warn" to listOf(COPY),
            "storm_advise" to listOf(COPY),
            "chatter_b1" to listOf(Reply("POSTED WAIT IS ACCURATE", "op_line")),
            "chatter_44" to listOf(COPY),
        )

        /** Calls the operator can make at any time. */
        val GENERAL = listOf(
            Reply("RADIO CHECK", "op_radio_check", listOf("radio_check_reply" to 1.5)),
            Reply("REQUEST MAINTENANCE", "op_request_maint", listOf("maint_request" to 4.0), callsMaint = true),
            Reply("REQUEST A BREAKER", "op_request_break", listOf("break_reply" to 2.0)),
        )

        /** The supervisor's follow-up when a call that needs a status goes unanswered. */
        private val NOREPLY = mapOf("estop" to "estop_noreply", "down_query" to "down_noreply")

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
