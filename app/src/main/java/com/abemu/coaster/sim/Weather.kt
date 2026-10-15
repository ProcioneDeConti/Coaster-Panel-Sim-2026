package com.abemu.coaster.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Instructor weather setting. AUTO wanders between clear, breezy and stormy spells on its own. */
enum class WeatherPreset(val label: String) {
    CLEAR("CLEAR"), BREEZY("BREEZY"), HIGH_WIND("HIGH WIND"), STORM("STORM CELL"), AUTO("AUTO")
}

/**
 * Park weather as the ride's field instruments see it: an anemometer on the tallest structure
 * ([windMph], instantaneous including gusts) and a lightning detector ([strikeMi], distance of the most
 * recent strike in the last minute, or null). Runs in simulated seconds, so sim speed fast-forwards storms.
 */
class Weather(seed: Int = 7) {
    private val rnd = Random(seed)
    var preset = WeatherPreset.CLEAR
        set(v) { field = v; startSpell(v) }

    /** What the sky is actually doing right now (differs from [preset] only in AUTO). */
    var current = WeatherPreset.CLEAR; private set
    var windMph = 6.0; private set
    var strikeMi: Double? = null; private set
    /** Storm cell distance in miles (negative = approaching side). Only meaningful during a storm. */
    var cellMi = 0.0; private set

    private var baseWind = 6.0
    private var gust = 0.0
    private var gustT = 0.0
    private var t = 0.0
    private var spellT = 0.0
    private var strikeAge = 999.0
    private var nextStrike = 0.0

    private fun startSpell(p: WeatherPreset) {
        current = if (p == WeatherPreset.AUTO) WeatherPreset.CLEAR else p
        spellT = 0.0
        if (current == WeatherPreset.STORM) cellMi = -25.0
    }

    fun update(dt: Double) {
        t += dt
        spellT += dt
        if (preset == WeatherPreset.AUTO && spellT > 600) {
            // Every 10 simulated minutes the weather may change.
            spellT = 0.0
            current = when (current) {
                WeatherPreset.STORM -> if (cellMi > 25) WeatherPreset.BREEZY else WeatherPreset.STORM
                else -> when (rnd.nextInt(10)) {
                    0, 1 -> WeatherPreset.STORM.also { cellMi = -25.0 }
                    2, 3, 4 -> WeatherPreset.BREEZY
                    5 -> WeatherPreset.HIGH_WIND
                    else -> WeatherPreset.CLEAR
                }
            }
        }
        // Storm cell drifts through at ~25 mph: 25 mi out → overhead in an hour, gone an hour later.
        if (current == WeatherPreset.STORM) {
            cellMi += 25.0 / 3600 * dt
            if (cellMi > 30 && preset == WeatherPreset.STORM) cellMi = -25.0 // fixed STORM preset: next cell
        }
        val stormNear = if (current == WeatherPreset.STORM) (1 - abs(cellMi) / 12).coerceIn(0.0, 1.0) else 0.0
        val target = when (current) {
            WeatherPreset.CLEAR -> 6.0
            WeatherPreset.BREEZY -> 18.0
            WeatherPreset.HIGH_WIND -> 32.0
            WeatherPreset.STORM -> 10.0 + 22.0 * stormNear
            WeatherPreset.AUTO -> 6.0
        }
        baseWind += (target - baseWind) * (dt / 90).coerceAtMost(1.0) + sin(t / 37) * 0.02
        // Gusts: short bursts, stronger in high wind and under a storm.
        gustT -= dt
        if (gustT <= 0) {
            gustT = 4 + rnd.nextDouble() * 12
            gust = rnd.nextDouble() * (0.25 * baseWind + 14 * stormNear + 2)
        }
        gust *= (1 - dt / 3).coerceAtLeast(0.0)
        windMph = max(0.0, baseWind + gust + sin(t * 1.7) * 0.6)

        // Lightning: strikes scatter within a few miles of the cell, more often as it builds.
        strikeAge += dt
        if (strikeAge > 60) strikeMi = null
        if (current == WeatherPreset.STORM && abs(cellMi) < 22) {
            nextStrike -= dt
            if (nextStrike <= 0) {
                nextStrike = 8 + rnd.nextDouble() * 30
                strikeMi = max(0.3, abs(cellMi) + (rnd.nextDouble() - 0.5) * 6)
                strikeAge = 0.0
            }
        }
    }
}

/**
 * Weather hold logic, run by the ride PLC from the anemometer and lightning detector. A hold starts when the
 * wind reaches [windLimitMph] or lightning strikes within [lightningMi]; it clears once conditions have stayed
 * below the limits for the all-clear time (the wind must also drop 5 mph under the limit).
 */
class WeatherInterlock(val windLimitMph: Double) {
    val lightningMi = 8.0
    val windClearSec = 600.0
    val lightningClearSec = 900.0

    var windMph = 0.0
    var strikeMi: Double? = null

    var windHold = false; private set
    var lightningHold = false; private set
    /** Seconds until the hold may clear (counts down only while conditions are below the limits). */
    var windClearIn = 0.0; private set
    var lightningClearIn = 0.0; private set
    val hold get() = windHold || lightningHold

    fun scan(dt: Double) {
        if (windMph >= windLimitMph) { windHold = true; windClearIn = windClearSec }
        else if (windHold) {
            if (windMph < windLimitMph - 5) windClearIn -= dt else windClearIn = windClearSec
            if (windClearIn <= 0) windHold = false
        }
        val s = strikeMi
        if (s != null && s <= lightningMi) { lightningHold = true; lightningClearIn = lightningClearSec }
        else if (lightningHold) {
            lightningClearIn -= dt
            if (lightningClearIn <= 0) lightningHold = false
        }
    }

    val reason: String get() = when {
        lightningHold && windHold -> "lightning + high wind"
        lightningHold -> "lightning within ${lightningMi.toInt()} mi"
        windHold -> "wind ≥ ${windLimitMph.toInt()} mph"
        else -> ""
    }
}
