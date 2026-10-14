package com.abemu.coaster.sim

import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Severity(val label: String) { ESTOP("E-STOP"), CSTOP("C-STOP"), WARNING("WARNING"), INFO("INFO") }

class Alarm(val code: String, var text: String, val severity: Severity) {
    var active = false
    var acked = false
    var silenced = false
    var since = ""
    /** True while the underlying condition is still present this scan. */
    var condition = false
}

data class AlarmEvent(val time: String, val code: String, val text: String, val severity: Severity, val event: String)

class AlarmManager {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val alarms = LinkedHashMap<String, Alarm>()
    val history = ArrayDeque<AlarmEvent>()

    private fun now() = LocalTime.now().format(fmt)

    private fun log(a: Alarm, event: String) {
        history.addFirst(AlarmEvent(now(), a.code, a.text, a.severity, event))
        while (history.size > 300) history.removeLast()
    }

    /** Latch an alarm. Event-type alarms (no persistent condition) clear on the next reset. */
    fun raise(code: String, text: String, severity: Severity) {
        val a = alarms.getOrPut(code) { Alarm(code, text, severity) }
        a.text = text
        if (!a.active) {
            a.active = true
            a.acked = false
            a.silenced = false
            a.since = now()
            log(a, "ACTIVE")
        }
    }

    /** Evaluate a persistent condition each scan; latches the alarm while true. */
    fun condition(code: String, text: String, severity: Severity, present: Boolean) {
        if (present) raise(code, text, severity)
        alarms[code]?.condition = present
    }

    fun info(code: String, text: String) {
        history.addFirst(AlarmEvent(now(), code, text, Severity.INFO, "EVENT"))
    }

    /** Fault reset: clears every latched alarm whose condition is gone. */
    fun reset(): Int {
        var remaining = 0
        for (a in alarms.values) if (a.active) {
            if (a.condition) remaining++ else {
                a.active = false
                log(a, "CLEARED")
            }
        }
        return remaining
    }

    fun acknowledgeAll() {
        for (a in alarms.values) if (a.active && !a.acked) {
            a.acked = true
            log(a, "ACK")
        }
    }

    fun silence() { for (a in alarms.values) a.silenced = true }

    fun isActive(code: String) = alarms[code]?.active == true

    val active: List<Alarm>
        get() = alarms.values.filter { it.active }.sortedWith(compareBy({ it.severity.ordinal }, { it.since }))

    fun anyActive(sev: Severity) = alarms.values.any { it.active && it.severity == sev }
    val anyUnacked get() = alarms.values.any { it.active && !it.acked }
    val hornDemand get() = alarms.values.any { it.active && !it.silenced && it.severity.ordinal <= Severity.CSTOP.ordinal }
}
