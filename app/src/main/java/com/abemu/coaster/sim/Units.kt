package com.abemu.coaster.sim

/** Display units: the sim runs in SI internally, the HMI shows imperial. */
object Units {
    const val MPH = 2.23694 // per m/s
    const val FT = 3.28084  // per m
    fun mph(ms: Double) = ms * MPH
    fun fps(ms: Double) = ms * FT
    fun ft(m: Double) = m * FT
}
