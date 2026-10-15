package com.abemu.coaster.sim

import kotlin.math.exp
import kotlin.random.Random

/** Park attendance: guests per hour who head for this ride's entrance. */
enum class Demand(val label: String, val perHour: Int) {
    LIGHT("LIGHT", 400), MODERATE("MODERATE", 800), BUSY("BUSY", 1300), PEAK("PEAK", 1900)
}

/**
 * The ride's line. Guests arrive at the [demand] rate (more of them balk as the posted wait climbs), board
 * the train on each dispatch, and drift away while the ride is down. The queue closes automatically during
 * a weather hold; the instructor or operator can also close it.
 */
class GuestQueue(seed: Int = 11) {
    private val rnd = Random(seed)
    var demand = Demand.MODERATE
    /** Line open to new guests (operator setting). */
    var open = true
    var inLine = 0; private set
    var riders = 0; private set
    var balked = 0; private set
    var walkedOff = 0; private set
    /** Riders on the last dispatched train, and the seats it had. */
    var lastLoad = 0 to 0; private set
    /** Posted wait, minutes, recalculated like a real wait-time board every 5 minutes. */
    var postedWaitMin = 5; private set
    var closedReason = ""; private set

    private var t = 0.0
    private var arrivalAcc = 0.0
    private var leaveAcc = 0.0
    private var postT = 0.0
    private var lastDispatchCount = -1
    private var downT = 0.0
    /** (time, riders) for the throughput window. */
    private val loads = ArrayDeque<Pair<Double, Int>>()

    /** Riders per hour over the last 15 minutes. */
    val ridersPerHour: Int get() {
        if (loads.isEmpty()) return 0
        val span = (t - loads.first().first).coerceIn(60.0, 900.0)
        return (loads.sumOf { it.second } * 3600 / span).toInt()
    }

    fun reset() {
        inLine = 0; riders = 0; balked = 0; walkedOff = 0; loads.clear(); lastLoad = 0 to 0
        postedWaitMin = 5; lastDispatchCount = -1; downT = 0.0
    }

    fun update(dt: Double, plc: RidePlc) {
        t += dt
        val wxHold = plc.weather.hold
        val accepting = open && !wxHold
        closedReason = when { wxHold -> "WEATHER"; !open -> "CLOSED"; else -> "" }

        // Arrivals: fewer guests join as the posted wait grows (half balk at ~90 minutes).
        if (accepting) {
            arrivalAcc += demand.perHour / 3600.0 * dt
            while (arrivalAcc >= 1) {
                arrivalAcc -= 1
                if (rnd.nextDouble() < exp(-postedWaitMin / 130.0)) inLine++ else balked++
            }
        }

        // Down time: guests start leaving after a few minutes without a dispatch, faster in a weather hold.
        val running = plc.state == RideState.RUNNING
        downT = if (running && !wxHold) 0.0 else downT + dt
        if (downT > 180 && inLine > 0) {
            leaveAcc += inLine * (if (wxHold) 0.02 else 0.008) / 60 * dt
            while (leaveAcc >= 1 && inLine > 0) { leaveAcc -= 1; inLine--; walkedOff++ }
        }

        // Boarding: each dispatch takes the train that was loaded in the station. Single-rider gaps leave a few seats empty.
        val dc = plc.dispatchCount
        if (lastDispatchCount < 0) lastDispatchCount = dc
        if (dc > lastDispatchCount) {
            repeat(dc - lastDispatchCount) {
                val seats = plc.profile.ridersPerTrain
                val fill = (seats - rnd.nextInt(0, maxOf(1, seats / 10) + 1)).coerceAtLeast(0)
                val n = minOf(inLine, fill)
                inLine -= n
                riders += n
                lastLoad = n to seats
                loads.addLast(t to n)
            }
            lastDispatchCount = dc
        }
        while (loads.isNotEmpty() && t - loads.first().first > 900) loads.removeFirst()

        postT += dt
        if (postT >= 300 || postedWaitMin == 5 && riders == 0) {
            postT = 0.0
            postedWaitMin = estimateWaitMin(plc)
        }
    }

    /** Wait for a guest joining now: the line divided by the ride's current hourly capacity, rounded up to 5 minutes. */
    fun estimateWaitMin(plc: RidePlc): Int {
        val seats = plc.profile.ridersPerTrain
        val interval = plc.avgInterval ?: plc.dispatchTarget
        val perMin = seats * 60.0 / interval.coerceAtLeast(10.0)
        val raw = inLine / perMin
        return maxOf(5, ((raw + 4.99) / 5).toInt() * 5)
    }
}
