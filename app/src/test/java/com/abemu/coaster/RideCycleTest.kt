package com.abemu.coaster

import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.CoasterSpec
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
        repeat((secs / dt).toInt()) {
            plc.scan(dt); plc.plant.step(dt)
            // Safety invariant: never two train fronts in the same block.
            val p = plc.profile
            val perBlock = plc.plant.trains.groupBy { p.blockAt(it.s).index }
            perBlock.forEach { (b, ts) ->
                assertTrue("${p.name}: ${ts.size} trains in ${p.blocks[b].tag} ${p.blocks[b].name}", ts.size <= 1)
            }
        }
    }

    @Test fun millenniumForceBlocks() {
        val p = RideProfile.create(CoasterLibrary.byId("millennium"))
        assertEquals(listOf("Load Station", "Lift Hill", "Main Course / Final Brake", "Unload Station"), p.blocks.map { it.name })
        runCycles(CoasterLibrary.byId("millennium"), 3, 6, NOOP)
    }

    private fun runCycles(type: ProfileType, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc =
        runCycles(CoasterLibrary.default(type), trains, dispatches, configure)

    private fun runCycles(spec: CoasterSpec, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc {
        val plc = RidePlc(Plant(RideProfile.create(spec), trains))
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
                throw AssertionError("${spec.name}: alarms: " + bad.map { it.text } + " trains=" +
                    plc.plant.trains.map { "${it.id}@${"%.1f".format(it.s)}/${"%.2f".format(it.v)}" })
            }
        }
        println("${spec.name} x$trains: $done dispatches, max speed ${"%.1f".format(maxV)} m/s, t=$guard")
        return plc
    }

    @Test fun liftThreeTrains() { runCycles(ProfileType.LIFT, 3, 8, NOOP) }
    @Test fun liftOneTrain() { runCycles(ProfileType.LIFT, 1, 3, NOOP) }
    @Test fun launchThreeTrains() { runCycles(ProfileType.LAUNCH, 3, 8, NOOP) }

    @Test fun launchRollback() {
        CoasterLibrary.all.filter { it.type == ProfileType.LAUNCH }.forEach { rollback(it) }
    }

    private fun rollback(spec: CoasterSpec) {
        val plc = RidePlc(Plant(RideProfile.create(spec), 1))
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
        assertTrue("${spec.name} no rollback", plc.alarms.isActive("ROLLBACK"))
        assertEquals(RideState.STOPPED, plc.state)
        plc.plant.faults.launchUnderspeed = false
        press(plc) { plc.inputs.faultReset = it }
        step(plc, 120.0)
        val t = plc.plant.trains[0]
        assertTrue("${spec.name} train at ${t.s}", kotlin.math.abs(t.s - 70.0) < 1.0)
    }

    @Test fun everyCoasterRunsCleanly() {
        CoasterLibrary.all.forEach { runCycles(it, 3, 5, NOOP) }
    }

    @Test fun standEstopIsFirstOut() {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        plc.inputs.mode = KeyMode.AUTO
        press(plc) { plc.inputs.powerOn = it }
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 7.0)
        plc.inputs.loadStandEstop = true
        step(plc, 0.5)
        plc.plant.faults.airLeak = true // second, later fault
        step(plc, 10.0)
        assertEquals(RideState.ESTOP, plc.state)
        assertEquals("ESTOP_LOAD", plc.alarms.firstOut?.code)
        assertTrue(plc.alarms.isActive("AIR"))
        // Releasing the stand E-Stop and clearing the leak lets a reset return to STOPPED.
        plc.inputs.loadStandEstop = false
        plc.plant.faults.airLeak = false
        step(plc, 15.0)
        press(plc) { plc.inputs.faultReset = it }
        assertEquals(plc.alarms.active.map { it.code + "/" + it.condition }.toString() + " psi=" + plc.plant.airPsi,
            RideState.STOPPED, plc.state)
        assertEquals(null, plc.alarms.firstOut)
    }

    @Test fun diagnosticsCoverAlarmCodes() {
        val codes = listOf("ESTOP_PB", "ESTOP_LOAD", "ESTOP_UNLOAD", "SAFETY", "AIR", "LIFT_VFD", "LIFT_OL", "LSM", "LSM_SPD",
            "ROLLBACK", "COLLIDE", "GATE", "ROW3", "BRK2", "STALL1", "UNEXP2", "OVR3")
        codes.forEach { assertTrue("no diagnosis for $it", com.abemu.coaster.sim.Diagnostics.forCode(it) != null) }
        assertEquals(null, com.abemu.coaster.sim.Diagnostics.forCode("BRKX"))
    }

    companion object { val NOOP: (RidePlc) -> Unit = {} }
}
