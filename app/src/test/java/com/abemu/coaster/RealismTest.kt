package com.abemu.coaster

import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.GuestQueue
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stacking on the brake runs, wind on the launch, per-seat restraints and the restraint walk check. */
class RealismTest {
    private val dt = 0.02

    private fun step(plc: RidePlc, secs: Double) {
        var t = 0.0
        while (t < secs) { plc.scan(dt); plc.plant.step(dt); t += dt }
    }

    private fun press(plc: RidePlc, set: (Boolean) -> Unit) { set(true); step(plc, 0.1); set(false); step(plc, 0.1) }

    private fun start(plc: RidePlc) {
        plc.inputs.mode = KeyMode.AUTO; plc.inputs.loadFootSwitch = true; plc.inputs.unloadFootSwitch = true
        press(plc) { plc.inputs.powerOn = it }
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 7.0)
        assertEquals(RideState.RUNNING, plc.state)
    }

    /** Load and dispatch the train in the station; false if it never became ready. */
    private fun dispatch(plc: RidePlc, walk: Boolean = false): Boolean {
        press(plc) { plc.inputs.restraintOpen = it }
        press(plc) { plc.inputs.gatesOpen = it }
        step(plc, 2.0)
        press(plc) { plc.inputs.gatesClose = it }
        press(plc) { plc.inputs.restraintClose = it }
        step(plc, 7.0)
        if (walk) for (r in 0 until plc.profile.rows) plc.checkCar(r)
        press(plc) { plc.inputs.loadReady = it }
        press(plc) { plc.inputs.unloadReady = it }
        var waited = 0.0
        while (!plc.dispatchReady && waited < 120) { step(plc, 0.5); waited += 0.5 }
        if (!plc.dispatchReady) return false
        plc.inputs.dispatchL = true; plc.inputs.dispatchR = true
        step(plc, 0.2)
        plc.inputs.dispatchL = false; plc.inputs.dispatchR = false
        return true
    }

    private fun stopFaults(plc: RidePlc) = plc.alarms.active.filter { it.severity.ordinal <= Severity.CSTOP.ordinal }.map { it.text }

    private fun cycle(plc: RidePlc, dispatches: Int, holdSec: Double = 0.0): Int {
        var done = 0
        var guard = 0.0
        var maxStack = 0
        while (done < dispatches && guard < 4000) {
            if (plc.trainInStation && !plc.releaseLatch[0]) {
                if (holdSec > 0) step(plc, holdSec)
                maxStack = maxOf(maxStack, plc.stackedTrains)
                assertTrue("${plc.profile.name}: dispatch not ready " + plc.permissives.filter { !it.ok } + stopFaults(plc), dispatch(plc))
                done++
            }
            step(plc, 0.5); guard += 0.5
            maxStack = maxOf(maxStack, plc.stackedTrains)
            assertTrue("${plc.profile.name}: " + stopFaults(plc), stopFaults(plc).isEmpty())
        }
        assertEquals(dispatches, done)
        return maxStack
    }

    @Test fun stationApproachOnMcbrRides() {
        val p = RideProfile.create(CoasterLibrary.byId("cyclone"))
        val names = p.blocks.map { it.name }
        assertEquals("Station Approach", names.last())
        assertEquals("Final Brake / Transfer", p.blocks[p.transferBlock].name)
        assertEquals(4, p.maxTrains)
        // Rides without the approach block keep three trains.
        assertEquals(3, RideProfile.create(CoasterLibrary.byId("tt2")).maxTrains)
    }

    @Test fun fourTrainsRunOnEveryStackingRide() {
        CoasterLibrary.builtIn.map { RideProfile.create(it) }.filter { it.maxTrains == 4 }.forEach { p ->
            val plc = RidePlc(Plant(p, 4))
            start(plc)
            cycle(plc, 8)
        }
    }

    @Test fun lateDispatchStacksTrains() {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId("cyclone")), 4))
        start(plc)
        cycle(plc, 3)
        // Hold every train in the station for two minutes: the others stack up on the brakes behind it.
        val stacked = cycle(plc, 3, holdSec = 120.0)
        assertTrue("never stacked ($stacked)", stacked >= 2)
        assertTrue(plc.alarms.history.any { it.code == "STACK" })
        assertTrue(plc.stackEvents > 0)
    }

    /** Wind bearing giving the strongest headwind at the top of the launch track. */
    private fun headOn(plant: Plant): Double {
        plant.windMs = 1.0
        return (0 until 360 step 5).maxBy { plant.windFromDeg = it.toDouble(); plant.headwind(plant.profile.launchEnd - 5) }.toDouble()
    }

    private fun launchOnce(id: String, windMs: Double, derate: Boolean = false): RidePlc {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId(id)), 1))
        plc.plant.faults.lsmDerate = derate
        plc.plant.windFromDeg = headOn(plc.plant)
        plc.plant.windMs = windMs
        start(plc)
        assertTrue(dispatch(plc))
        var t = 0.0
        while (t < 120 && plc.launchState != LaunchState.ROLLBACK && plc.occ[2] == -1) { step(plc, 0.5); t += 0.5 }
        return plc
    }

    @Test fun headwindRollsBackADeratedLaunch() {
        CoasterLibrary.builtIn.filter { it.type == ProfileType.LAUNCH && !it.lsmLift }.forEach { sp ->
            // A healthy launch shrugs off a gale; a derated one clears in light wind and rolls back in a strong headwind.
            assertTrue("${sp.id} healthy launch rolled back in a 36 mph headwind", launchOnce(sp.id, 16.0).launchState != LaunchState.ROLLBACK)
            assertTrue("${sp.id} derated launch rolled back in calm air", launchOnce(sp.id, 0.0, derate = true).launchState != LaunchState.ROLLBACK)
            assertTrue("${sp.id} derated launch rolled back in a 20 mph headwind", launchOnce(sp.id, 9.0, derate = true).launchState != LaunchState.ROLLBACK)
            assertEquals("${sp.id} derated launch in a 36 mph headwind", LaunchState.ROLLBACK, launchOnce(sp.id, 16.0, derate = true).launchState)
        }
    }

    @Test fun rollbackSurvivesPowerCycle() {
        // Cycling ride power during a rollback used to drop the launch state while the ROLLBACK alarm stayed
        // latched, so it could never be inspected or reset and the ride refused to start until it was rebuilt.
        val plc = launchOnce("stormchaser", 16.0, derate = true)
        assertEquals(LaunchState.ROLLBACK, plc.launchState)
        plc.plant.windMs = 0.0
        step(plc, 30.0)
        press(plc) { plc.inputs.powerOff = it }
        press(plc) { plc.inputs.powerOn = it }
        assertEquals(LaunchState.ROLLBACK, plc.launchState)
        assertTrue(plc.rollbackAtRest)
        plc.signOffRollbackInspection()
        press(plc) { plc.inputs.faultReset = it }
        assertTrue("rollback alarm still latched", !plc.alarms.isActive("ROLLBACK"))
        assertTrue(plc.relaunchHold)
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 8.0)
        assertEquals(RideState.RUNNING, plc.state)
    }

    @Test fun queueBoardsAfterRideRebuild() {
        // A rebuilt controller restarts its dispatch count at 0; the queue must not wait for it to catch up.
        val q = GuestQueue()
        val a = RidePlc(Plant(RideProfile.create(CoasterLibrary.default(ProfileType.LIFT)), 1))
        start(a)
        q.update(600.0, a) // let a line build up
        repeat(2) { assertTrue(dispatch(a)); q.update(dt, a) }
        val before = q.riders
        assertTrue(before > 0)
        val b = RidePlc(Plant(RideProfile.create(CoasterLibrary.default(ProfileType.LIFT)), 1))
        start(b)
        q.update(dt, b)
        assertTrue(dispatch(b)); q.update(dt, b)
        assertTrue("no riders boarded after the rebuild", q.riders > before)
    }

    @Test fun rollbackRecoveryNeedsInspectionAndAuthorization() {
        val plc = launchOnce("stormchaser", 16.0, derate = true)
        assertEquals(LaunchState.ROLLBACK, plc.launchState)
        plc.plant.windMs = 0.0
        step(plc, 30.0)
        assertTrue(plc.rollbackAtRest)
        press(plc) { plc.inputs.faultReset = it }
        assertEquals(LaunchState.ROLLBACK, plc.launchState)
        plc.signOffRollbackInspection()
        press(plc) { plc.inputs.faultReset = it }
        assertTrue(plc.relaunchHold)
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 30.0)
        // Back at the launch position but held until authorized.
        assertEquals(LaunchState.IDLE, plc.launchState)
        assertEquals("Relaunch authorized", plc.authorizeRelaunch())
        var t = 0.0
        while (t < 60 && plc.occ[2] == -1) { step(plc, 0.5); t += 0.5 }
        assertTrue("relaunch did not crest", plc.occ[2] != -1)
        assertTrue(!plc.relaunchHold)
        assertEquals(1, plc.rollbacks)
    }

    @Test fun radioCallsOnEstopAndRollback() {
        val radio = com.abemu.coaster.sim.RadioDesk()
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId("stormchaser")), 1))
        plc.plant.faults.lsmDerate = true
        plc.plant.windFromDeg = headOn(plc.plant); plc.plant.windMs = 16.0
        val said = mutableListOf<String>()
        fun run(secs: Double) { var t = 0.0; while (t < secs) { plc.scan(dt); plc.plant.step(dt); radio.update(dt, plc, true); radio.next()?.let { said += it }; t += dt } }
        radio.update(dt, plc, true)
        plc.inputs.mode = KeyMode.AUTO; plc.inputs.loadFootSwitch = true; plc.inputs.unloadFootSwitch = true
        plc.inputs.powerOn = true; run(0.1); plc.inputs.powerOn = false; run(0.1)
        plc.inputs.rideStart = true; run(0.1); plc.inputs.rideStart = false; run(10.0)
        assertTrue(said.toString(), "opening" in said)
        assertTrue(dispatch(plc))
        run(60.0)
        assertTrue(said.toString(), "rollback" in said && "rollback_maint" in said)
        plc.inputs.estopPressed = true; run(20.0)
        assertTrue(said.toString(), "estop" in said && "estop_maint" in said)
    }

    @Test fun restraintWalkCheck() {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId("cyclone")), 1))
        plc.restraintWalk = true
        start(plc)
        assertTrue("dispatched without the walk", !dispatch(plc, walk = false))
        assertTrue(plc.alarms.history.any { it.text.startsWith("Load ready refused – restraint check") })
        // A seat that won't lock: the walk finds it and the row stays unchecked.
        plc.plant.faults.restraintSeat = 5
        press(plc) { plc.inputs.restraintOpen = it }
        press(plc) { plc.inputs.restraintClose = it }
        step(plc, 7.0)
        val per = plc.profile.spec.ridersPerCar
        assertTrue(plc.checkCar(5 / per).contains("WILL NOT LOCK"))
        assertTrue(!plc.carChecked[5 / per])
        plc.plant.faults.restraintSeat = -1
        assertTrue(plc.checkCar(5 / per).startsWith("Car"))
        assertTrue(plc.carChecked[5 / per])
        assertTrue("walked train not dispatched: " + plc.permissives.filter { !it.ok }, dispatch(plc, walk = true))
        plc.plant.trains.forEach { t -> assertTrue(t.beltOn.indices.all { !t.seatTaken[it] || t.beltOn[it] }) }
    }
}
