package com.abemu.coaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the simulated controller and runs it in real time: fixed 20 ms PLC scans with the
 * physics stepped once per scan. Everything runs on the main thread, so the UI can read PLC
 * fields directly; [frame] ticks each update to drive recomposition.
 */
class SimViewModel : ViewModel() {
    var profileType by mutableStateOf(ProfileType.LIFT); private set
    var trainCount by mutableIntStateOf(3); private set
    var plc by mutableStateOf(build(ProfileType.LIFT, 3)); private set
    var timeScale by mutableDoubleStateOf(1.0)
    var soundOn by mutableStateOf(true)
    var volume by mutableFloatStateOf(0.5f)
    val frame = mutableLongStateOf(0L)

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
                    acc -= scanDt
                }
                frame.longValue++
            }
        }
    }

    private fun build(type: ProfileType, trains: Int) = RidePlc(Plant(RideProfile.create(type), trains))

    /** Change ride profile / train count. Only allowed with ride power off, like a real config change. */
    fun reconfigure(type: ProfileType = profileType, trains: Int = trainCount): Boolean {
        if (plc.state != RideState.POWER_OFF) return false
        val old = plc.inputs
        val fresh = build(type, trains)
        fresh.inputs.mode = old.mode
        fresh.inputs.estopPressed = old.estopPressed
        fresh.inputs.singleButtonDispatch = old.singleButtonDispatch
        fresh.inputs.loadStandEstop = old.loadStandEstop
        fresh.inputs.unloadStandEstop = old.unloadStandEstop
        fresh.dispatchTarget = plc.dispatchTarget
        profileType = type
        trainCount = trains
        plc = fresh
        return true
    }
}
