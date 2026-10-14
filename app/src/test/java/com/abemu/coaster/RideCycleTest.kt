package com.abemu.coaster

import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideCycleTest {
    private val dt = 0.02

    private fun press(plc: RidePlc, set: (Boolean) -> Unit) {
        set(true); step(plc, 0.1); set(false); step(plc, 0.1)
    }

    private fun step(plc: RidePlc, secs: Double) {
        repeat((secs / dt).toInt()) { plc.scan(dt); plc.plant.step(dt) }
    }

    private fun runCycles(type: ProfileType, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc {
        val plc = RidePlc(Plant(RideProfile.create(type), trains))
        configure(plc)
        plc.inputs.mode = KeyMode.AUTO
        press(plc) { plc.inputs.powerOn = it }
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 7.0)
        assertEquals(RideState.RUNNING, plc.state)
        var done = 0
        var guard = 0.0
        var maxV = 0.0
        while (done < dispatches && guard < 3000) {
            if (plc.trainInStation && !plc.releaseLatch[0]) {
                press(plc) { plc.inputs.restraintOpen = it }
                press(plc) { plc.inputs.gatesOpen = it }
                step(plc, 2.0)
                press(plc) { plc.inputs.gatesClose = it }
                press(plc) { plc.inputs.restraintClose = it }
                step(plc, 7.0)
                press(plc) { plc.inputs.loadReady = it }
                press(plc) { plc.inputs.unloadReady = it }
                var waited = 0.0
                while (!plc.dispatchReady && waited < 120) { step(plc, 0.5); waited += 0.5 }
                assertTrue("dispatch not ready: " + plc.permissives.filter { !it.ok } + " " +
                    plc.alarms.active.map { it.text } + " occ=" + plc.occ.toList() +
                    " trains=" + plc.plant.trains.map { "${it.id}@${"%.1f".format(it.s)}/${"%.2f".format(it.v)}" },
                    plc.dispatchReady)
                plc.inputs.dispatchL = true; plc.inputs.dispatchR = true
                step(plc, 0.2)
                plc.inputs.dispatchL = false; plc.inputs.dispatchR = false
                done++
                guard += 15
            }
            step(plc, 0.5); guard += 0.5
            maxV = maxOf(maxV, plc.plant.trains.maxOf { it.v })
            val bad = plc.alarms.active.filter { it.severity.ordinal <= Severity.CSTOP.ordinal }
            if (bad.isNotEmpty() && configure === NOOP) {
                throw AssertionError("alarms: " + bad.map { it.text } + " trains=" +
                    plc.plant.trains.map { "${it.id}@${"%.1f".format(it.s)}/${"%.2f".format(it.v)}" })
            }
        }
        println("$type x$trains: $done dispatches, max speed ${"%.1f".format(maxV)} m/s, t=$guard")
        return plc
    }

    @Test fun liftThreeTrains() { runCycles(ProfileType.LIFT, 3, 8, NOOP) }
    @Test fun liftOneTrain() { runCycles(ProfileType.LIFT, 1, 3, NOOP) }
    @Test fun launchThreeTrains() { runCycles(ProfileType.LAUNCH, 3, 8, NOOP) }

    @Test fun launchRollback() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LAUNCH), 1))
        plc.plant.faults.launchUnderspeed = true
        plc.inputs.mode = KeyMode.AUTO
        press(plc) { plc.inputs.powerOn = it }
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 7.0)
        press(plc) { plc.inputs.loadReady = it }
        press(plc) { plc.inputs.unloadReady = it }
        plc.inputs.dispatchL = true; plc.inputs.dispatchR = true; step(plc, 0.2)
        plc.inputs.dispatchL = false; plc.inputs.dispatchR = false
        step(plc, 60.0)
        assertTrue(plc.alarms.isActive("ROLLBACK"))
        assertEquals(RideState.STOPPED, plc.state)
        plc.plant.faults.launchUnderspeed = false
        press(plc) { plc.inputs.faultReset = it }
        step(plc, 45.0)
        val t = plc.plant.trains[0]
        assertTrue("train at ${t.s}", kotlin.math.abs(t.s - 70.0) < 1.0)
    }

    companion object { val NOOP: (RidePlc) -> Unit = {} }
}
