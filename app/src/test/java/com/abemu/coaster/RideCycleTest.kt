package com.abemu.coaster

import com.abemu.coaster.sim.BlockKind
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.CoasterSpec
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.LayoutTag
import com.abemu.coaster.sim.TagLayout
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity
import com.abemu.coaster.sim.passes
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
            // Safety invariant: never two train fronts in the same block, except a moving-block station zone,
            // where a train may follow the departing train in with at least the minimum spacing.
            val p = plc.profile
            val perBlock = plc.plant.trains.groupBy { p.blockAt(it.s).index }
            perBlock.forEach { (b, ts) ->
                if (ts.size == 2 && plc.stationZone(b)) {
                    val (x, y) = ts.sortedBy { p.ahead(p.blocks[b].start, it.s) }
                    assertTrue("${p.name}: moving-block gap ${p.ahead(x.s, y.s)} in ${p.blocks[b].tag}", p.ahead(x.s, y.s) >= p.trainLength + 3.0)
                    return@forEach
                }
                assertTrue("${p.name}: ${ts.size} trains in ${p.blocks[b].tag} ${p.blocks[b].name} " + plc.plant.trains.map { "${it.id}@${"%.1f".format(it.s)}/${"%.2f".format(it.v)}" } + " L=${"%.0f".format(p.length)} blocks=" + p.blocks.map { "${it.tag}:${it.start.toInt()}-${it.end.toInt()}" } + " launch=${plc.launchState}", ts.size <= 1)
            }
        }
    }

    /** A 50 m block brake tag part-way along the free-running course. */
    private fun midCourseBrake(p: RideProfile, frac: Double): LayoutTag {
        val a = p.blocks[1].end + frac * (p.blocks[p.transferBlock].brakeStart!! - p.blocks[1].end)
        return LayoutTag("BLOCK_BRAKE", a / p.length, (a + 50) / p.length, "Tagged MCBR")
    }

    @Test fun tagsAddCourseBrake() {
        var applied = 0
        for (spec in CoasterLibrary.all.filter { it.tunnelLaunch == 0.0 }) {
            val base = RideProfile.create(spec)
            val r = TagLayout.apply(base, listOf(midCourseBrake(base, 0.4), LayoutTag("LIFT", 0.02, 0.02, "Chain")))
            val b = r.profile.blocks.firstOrNull { it.name == "Tagged MCBR" } ?: continue
            applied++
            assertEquals(spec.name, "B3", b.tag)
            // Levelled through the brake.
            assertEquals(spec.name, r.profile.height(b.brakeStart!!), r.profile.height(b.stopPos!!), 0.01)
            if (applied <= 4) runCycles(r.profile, 3, 6, NOOP)
        }
        assertTrue("only $applied rides took a mid-course tag", applied >= 4)
    }

    @Test fun trimAndSensorTags() {
        val base = RideProfile.create(CoasterLibrary.byId("magnum"))
        val c0 = base.blocks[1].end; val c1 = base.blocks[base.transferBlock].brakeStart!!
        val a = c0 + 0.15 * (c1 - c0)
        val px = c0 + 0.6 * (c1 - c0)
        val r = TagLayout.apply(base, listOf(
            LayoutTag("TRIM_BRAKE", a / base.length, (a + 30) / base.length, "Trim 1"),
            LayoutTag("SENSOR", px / base.length, px / base.length, "MID"),
        ))
        assertTrue(r.report.toString(), r.rejected.isEmpty())
        val trim = r.profile.trims.single()
        var seen = false
        var maxInTrimEnd = 0.0
        val plc = runCycles(r.profile, 2, 4, NOOP)
        // Drive one more lap watching the trim and sensor.
        repeat(6000) {
            plc.scan(dt); plc.plant.step(dt)
            if (plc.plant.proxSensor(0)) seen = true
            plc.plant.trains.filter { r.profile.inRange(it.s, trim.end - 3, trim.end) }.forEach { maxInTrimEnd = maxOf(maxInTrimEnd, it.v) }
        }
        // The trim must actually bind (natural speed there is well above its cap).
        assertTrue("trim never binds", trim.maxSpeed < kotlin.math.sqrt(2 * 9.81 * (base.maxHeight - base.height(trim.end))) - 3)
        assertTrue("sensor never saw a train", seen)
        assertTrue("trim exit %.1f > %.1f".format(maxInTrimEnd, trim.maxSpeed), maxInTrimEnd <= trim.maxSpeed + 0.5)
    }

    @Test fun tagsOutsideCourseKeepGenerated() {
        val base = RideProfile.create(CoasterLibrary.byId("millennium"))
        val r = TagLayout.apply(base, listOf(LayoutTag("BLOCK_BRAKE", 10 / base.length, 40 / base.length)))
        assertEquals(base.blocks.map { it.name }, r.profile.blocks.map { it.name })
        assertTrue(r.report.toString(), r.report.single().contains("skipped"))
    }

    @Test fun millenniumForceBlocks() {
        val p = RideProfile.create(CoasterLibrary.byId("millennium"))
        assertEquals(listOf("Load Station", "Lift Hill", "Main Course / Final Brake", "Unload Station"), p.blocks.map { it.name })
        runCycles(CoasterLibrary.byId("millennium"), 3, 6, NOOP)
    }

    private fun runCycles(type: ProfileType, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc =
        runCycles(CoasterLibrary.default(type), trains, dispatches, configure)

    private fun runCycles(spec: CoasterSpec, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc =
        runCycles(RideProfile.create(spec), trains, dispatches, configure)

    private fun runCycles(profile: RideProfile, trains: Int, dispatches: Int, configure: (RidePlc) -> Unit = {}): RidePlc {
        val plc = RidePlc(Plant(profile, trains))
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
                throw AssertionError("${profile.name}: alarms: " + bad.map { it.text } + " trains=" +
                    plc.plant.trains.map { "${it.id}@${"%.1f".format(it.s)}/${"%.2f".format(it.v)}" })
            }
        }
        println("${profile.name} x$trains: $done dispatches, max speed ${"%.1f".format(maxV)} m/s, t=$guard")
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
        step(plc, 90.0) // a swing launch takes ~30 s before the final launch
        assertTrue("${spec.name} no rollback", plc.alarms.isActive("ROLLBACK"))
        assertEquals(RideState.STOPPED, plc.state)
        plc.plant.faults.launchUnderspeed = false
        press(plc) { plc.inputs.faultReset = it }
        step(plc, 120.0)
        val t = plc.plant.trains[0]
        assertTrue("${spec.name} train at ${t.s}", kotlin.math.abs(t.s - 70.0) < 1.0)
    }

    @Test fun movingBlockStationMovesTogether() {
        // TT2 with a train at load, unload and wait: on dispatch all three move off within a few seconds of each other.
        val plc = runCycles(CoasterLibrary.byId("tt2"), 3, 3, NOOP)
        val p = plc.profile
        val unload = p.blocks.first { it.kind == BlockKind.UNLOAD }.index
        var waited = 0.0
        while (!(plc.trainInStation && plc.occ[unload] != -1 && plc.occ[unload - 1] != -1) && waited < 600) {
            step(plc, 0.5); waited += 0.5
        }
        step(plc, 10.0) // unload dwell
        press(plc) { plc.inputs.loadReady = it }; press(plc) { plc.inputs.unloadReady = it }
        waited = 0.0
        while (!plc.dispatchReady && waited < 120) { step(plc, 0.5); waited += 0.5 }
        assertTrue("dispatch not ready: " + plc.permissives.filter { !it.ok }, plc.dispatchReady)
        val ids = listOf(plc.occ[0], plc.occ[unload], plc.occ[unload - 1])
        assertTrue("station not full: $ids", ids.none { it == -1 })
        plc.inputs.dispatchL = true; plc.inputs.dispatchR = true
        step(plc, 0.2)
        plc.inputs.dispatchL = false; plc.inputs.dispatchR = false
        val started = DoubleArray(3) { -1.0 }
        var t = 0.0
        while (t < 30 && started.any { it < 0 }) {
            step(plc, dt); t += dt
            ids.forEachIndexed { k, id -> if (started[k] < 0 && plc.trainSpeed(id) > 0.3) started[k] = t }
        }
        assertTrue("moves: load/unload/wait started at ${started.toList()}", started.all { it in 0.0..6.0 })
        assertTrue(plc.alarms.active.map { it.text }.toString(), plc.alarms.active.none { it.severity.ordinal <= Severity.CSTOP.ordinal })
    }

    @Test fun swingLaunchUsesSpikeAndCrests() {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId("tt2")), 1))
        val i = plc.inputs
        i.mode = KeyMode.AUTO
        press(plc) { i.powerOn = it }; press(plc) { i.rideStart = it }; step(plc, 7.0)
        press(plc) { i.loadReady = it }; press(plc) { i.unloadReady = it }
        i.dispatchL = true; i.dispatchR = true; step(plc, 0.2); i.dispatchL = false; i.dispatchR = false
        var maxSpike = 0.0
        var crested = false
        var sawSpikeLine = false
        repeat(4000) {
            step(plc, dt)
            if (plc.plant.switchSpikeLs) sawSpikeLine = true
            val t = plc.plant.trains[0]
            maxSpike = maxOf(maxSpike, t.spike)
            if (plc.occ[2] == t.id) crested = true
        }
        assertTrue("spike climb $maxSpike", maxSpike > 50)
        assertTrue("never crested the top hat", crested)
        assertTrue(plc.alarms.active.map { it.code }.toString(), !plc.alarms.isActive("ROLLBACK"))
        assertTrue("switch never reached the spike line", sawSpikeLine)
        assertTrue("switch did not return to the station line", plc.plant.switchStationLs)
    }

    @Test fun jammedSwitchInhibitsSwingLaunch() {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId("tt2")), 1))
        plc.plant.faults.switchStuck = true
        val i = plc.inputs
        i.mode = KeyMode.AUTO
        press(plc) { i.powerOn = it }; press(plc) { i.rideStart = it }; step(plc, 7.0)
        press(plc) { i.loadReady = it }; press(plc) { i.unloadReady = it }
        i.dispatchL = true; i.dispatchR = true; step(plc, 0.2); i.dispatchL = false; i.dispatchR = false
        step(plc, 60.0)
        assertTrue("no switch alarm", plc.alarms.isActive("SW_POS"))
        assertEquals(LaunchState.IDLE, plc.launchState)
        assertEquals(0.0, plc.plant.trains[0].v, 0.01)
    }

    /** Dispatching with no load time, trains should not queue on the course brakes (only at the station). */
    @Test fun fastDispatchDoesNotStackCourseBrakes() {
        for (id in listOf("thunder", "magnum")) {
            val p = RideProfile.create(CoasterLibrary.byId(id))
            val plc = RidePlc(Plant(p, 3))
            val i = plc.inputs
            i.mode = KeyMode.AUTO; i.singleButtonDispatch = true
            press(plc) { i.powerOn = it }; press(plc) { i.rideStart = it }; step(plc, 7.0)
            var courseWait = 0.0
            val mid = (1 until p.blocks.size).filter { p.blocks[it].kind == com.abemu.coaster.sim.BlockKind.BRAKE_RUN && it != p.transferBlock }
            repeat(1200 * 5) {
                if (plc.dispatchReady) { press(plc) { i.dispatchL = it } }
                else if (plc.trainInStation && !plc.releaseLatch[0] && !plc.loadReady) {
                    press(plc) { i.loadReady = it }; press(plc) { i.unloadReady = it }
                }
                step(plc, 0.2)
                for (b in mid) if (plc.plant.trainAtStop(b)?.let { kotlin.math.abs(it.v) < 0.05 } == true) courseWait += 0.2
            }
            val perDispatch = courseWait / plc.dispatchCount
            assertTrue("$id: ${"%.1f".format(perDispatch)} s per dispatch held on course brakes", perDispatch < 5.0)
        }
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

    private fun runningLift(): RidePlc {
        val plc = RidePlc(Plant(RideProfile.create(ProfileType.LIFT), 3))
        plc.inputs.mode = KeyMode.AUTO
        press(plc) { plc.inputs.powerOn = it }
        press(plc) { plc.inputs.rideStart = it }
        step(plc, 7.0)
        assertEquals(RideState.RUNNING, plc.state)
        assertTrue(plc.safety.outputsOn)
        return plc
    }

    @Test fun forcedEstopInputCaughtBySafetyTask() {
        val plc = runningLift()
        plc.force("ESTOP_PB_NC", true) // mask the console E-Stop from the standard task
        plc.inputs.estopPressed = true
        step(plc, 0.5)
        assertTrue("safety must trip on its own inputs", !plc.safety.outputsOn)
        assertTrue(!plc.plant.safetyPower)
        assertEquals(RideState.RUNNING, plc.state)
        step(plc, 1.0)
        assertEquals(RideState.ESTOP, plc.state)
        assertEquals("SAFE_STD", plc.alarms.firstOut?.code)
        // Clear it all and reset.
        plc.inputs.estopPressed = false
        plc.removeAllForces()
        step(plc, 0.5)
        press(plc) { plc.inputs.faultReset = it }
        assertEquals(RideState.STOPPED, plc.state)
        assertTrue(plc.safety.outputsOn)
    }

    @Test fun weldedChannelLatchesDiscrepancy() {
        val plc = runningLift()
        plc.plant.faults.estopWeldedB = 1 // load stand
        plc.inputs.loadStandEstop = true
        step(plc, 1.0)
        assertTrue(plc.alarms.isActive("SAFE_DISC"))
        plc.inputs.loadStandEstop = false
        step(plc, 0.5)
        press(plc) { plc.inputs.faultReset = it }
        assertEquals("discrepancy is latched until both channels open", RideState.ESTOP, plc.state)
        // Repair the contact, cycle the E-Stop, then reset.
        plc.plant.faults.estopWeldedB = -1
        press(plc) { plc.inputs.loadStandEstop = it }
        press(plc) { plc.inputs.faultReset = it }
        assertEquals(RideState.STOPPED, plc.state)
        assertTrue(plc.safety.outputsOn)
    }

    @Test fun forcedBrakeOutputCannotBeatSafetyPower() {
        val plc = runningLift()
        plc.inputs.estopPressed = true
        step(plc, 0.5)
        val b = plc.profile.blocks.first { it.stopPos != null && it.index != 0 }
        plc.force("${b.tag}_BRK_SOL", true)
        step(plc, 1.0)
        assertTrue(plc.plant.brakes[b.index].cmdOpen)
        assertTrue("brake must stay closed with safety power off", plc.plant.brakes[b.index].closedProx)
        plc.force("${b.tag}_BRK_SOL", null)
        assertTrue(!plc.forces.any)
    }

    @Test fun ladderAndIoMapBuildForEveryRide() {
        CoasterLibrary.all.forEach { spec ->
            val plc = RidePlc(Plant(RideProfile.create(spec), 3))
            plc.inputs.mode = KeyMode.AUTO
            press(plc) { plc.inputs.powerOn = it }
            val rungs = com.abemu.coaster.sim.Ladder.build(plc)
            assertTrue(rungs.isNotEmpty())
            rungs.forEach { r -> r.inputs.forEach { it.passes() }; r.coil.value() }
            val mods = com.abemu.coaster.sim.IoMap.build(plc)
            assertEquals(spec.name, mods.size, mods.map { it.slot }.toSet().size)
            mods.forEach { m -> m.points.forEach { it.value(plc) } }
            val tags = mods.flatMap { m -> m.points.map { it.tag } }
            assertEquals("${spec.name} duplicate I/O tags", tags.size, tags.toSet().size)
        }
    }

    @Test fun diagnosticsCoverAlarmCodes() {
        val codes = listOf("ESTOP_PB", "ESTOP_LOAD", "ESTOP_UNLOAD", "SAFETY", "AIR", "LIFT_VFD", "LIFT_OL", "LSM", "LSM_SPD",
            "ROLLBACK", "COLLIDE", "GATE", "ROW3", "BRK2", "STALL1", "UNEXP2", "OVR3", "SAFE_DISC", "SAFE_IO", "SAFE_STD")
        codes.forEach { assertTrue("no diagnosis for $it", com.abemu.coaster.sim.Diagnostics.forCode(it) != null) }
        assertEquals(null, com.abemu.coaster.sim.Diagnostics.forCode("BRKX"))
    }

    companion object { val NOOP: (RidePlc) -> Unit = {} }
}
