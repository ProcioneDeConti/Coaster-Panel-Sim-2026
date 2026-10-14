package com.abemu.coaster.sim

/** I/O forces installed on standard tags, keyed by tag name. Safety tags cannot be forced. */
class Forces {
    private val map = LinkedHashMap<String, Boolean>()
    operator fun get(tag: String): Boolean? = map[tag]
    operator fun set(tag: String, v: Boolean?) { if (v == null) map.remove(tag) else map[tag] = v }
    val all: Map<String, Boolean> get() = map
    val any get() = map.isNotEmpty()
    fun clear() = map.clear()
}

/**
 * GuardLogix safety task. Reads the E-Stop circuits on dual-channel safety inputs and drives the
 * safety outputs that power the brake valves and enable the lift/LSM drives (STO). It runs after
 * the standard task each scan and trips on its own inputs or on a stop request from the standard task.
 */
class SafetyController {
    val stations = listOf("CONSOLE", "LOAD_STAND", "UNLOAD_STAND", "REMOTE")
    val chA = BooleanArray(stations.size) { true }
    val chB = BooleanArray(stations.size) { true }
    /** Latched dual-channel discrepancy per station; clears when both channels open together. */
    val discrepancy = BooleanArray(stations.size)
    private val discT = DoubleArray(stations.size)
    var ioFault = false; private set
    /** Safety outputs off. Latched until a reset with a healthy chain. */
    var tripped = true; private set
    private var prevReset = false
    val outputsOn get() = !tripped
    val anyDiscrepancy get() = discrepancy.any { it }
    val chainOk get() = !ioFault && !anyDiscrepancy && chA.all { it } && chB.all { it }
    /** Safety signature shown on the HMI; fixed because the safety program is locked. */
    val signature = "3F2A-91C4-07DE"

    /**
     * [pressed] is the physical state of each station's E-Stop. [weldedB] is a station whose
     * channel B contact is welded closed (-1 for none).
     */
    fun scan(dt: Double, pressed: BooleanArray, weldedB: Int, ioFaultInjected: Boolean, reset: Boolean, stopRequest: Boolean) {
        ioFault = ioFaultInjected
        for (k in stations.indices) {
            // NC contacts: true = closed = healthy. A module fault reads all channels as safe state (off).
            chA[k] = !ioFault && !pressed[k]
            chB[k] = !ioFault && (k == weldedB || !pressed[k])
            discT[k] = if (chA[k] != chB[k]) discT[k] + dt else 0.0
            if (discT[k] > 0.5) discrepancy[k] = true
            if (!chA[k] && !chB[k] && !ioFault) discrepancy[k] = false
        }
        if (!chainOk || stopRequest) tripped = true
        if (reset && !prevReset && tripped && chainOk && !stopRequest) tripped = false
        prevReset = reset
    }
}
