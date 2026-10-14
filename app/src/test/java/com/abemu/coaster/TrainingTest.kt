package com.abemu.coaster

import com.abemu.coaster.sim.Autopilot
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.Drill
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Scenario
import com.abemu.coaster.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every drill with a scripted correct recovery and checks it completes with a good grade. */
class TrainingTest {
    private val dt = 0.02

    private fun start(id: String): Drill {
        val sc: Scenario = Scenarios.all.first { it.id == id }
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.default(sc.type)), sc.trains))
        if (sc.warmDispatches >= 0) assertTrue("$id warm-up never triggered", Autopilot.warmUp(plc, sc.warmDispatches, sc.trigger))
        return Drill(sc, plc).also { it.begin() }
    }

    private fun Drill.run(secs: Double, until: () -> Boolean = { false }): Boolean {
        var t = 0.0
        while (t < secs) {
            plc.scan(dt); plc.plant.step(dt); update(dt); t += dt
            if (until() || finished) return true
        }
        return false
    }

    private fun Drill.press(set: (Boolean) -> Unit) { set(true); run(0.1); set(false); run(0.1) }
    private fun Drill.ack() = press { plc.inputs.silence = it }
    private fun Drill.reset() = press { plc.inputs.faultReset = it }
    private fun Drill.restart() { press { plc.inputs.rideStart = it }; run(7.0) }
    private fun Drill.wo(id: String) = repair(workOrders.first { it.id == id })

    private fun Drill.dispatchOne() {
        val i = plc.inputs
        assertTrue("no train reached the station: " + dbg(), run(300.0) { plc.trainInStation && !plc.releaseLatch[0] })
        press { i.restraintOpen = it }; press { i.gatesOpen = it }; run(2.0)
        press { i.gatesClose = it }; press { i.restraintClose = it }; run(7.0)
        press { i.loadReady = it }; press { i.unloadReady = it }
        assertTrue("dispatch not ready: " + plc.permissives.filter { !it.ok } + dbg(), run(60.0) { plc.dispatchReady })
        i.dispatchL = true; i.dispatchR = true; run(0.2); i.dispatchL = false; i.dispatchR = false
        run(1.0)
    }

    private fun Drill.dbg() = " state=${plc.state} alarms=" + plc.alarms.active.map { it.code + "/" + it.condition } +
        " occ=" + plc.occ.toList() + " trains=" + plc.plant.trains.map { "${it.id}@${"%.0f".format(it.s)}/${"%.1f".format(it.v)}" } +
        " psi=${"%.0f".format(plc.plant.airPsi)} hist=" + plc.alarms.history.take(8).map { it.code + ":" + it.event }

    private fun Drill.check(minScore: Int = 80) {
        println("${scenario.title}: score $score ($grade) in ${"%.0f".format(t)} s; penalties " + penalties.map { it.text })
        assertTrue("${scenario.title} not finished; next = ${nextStep?.let { steps[it].text }}", finished)
        assertEquals(null, failed)
        assertTrue("${scenario.title} score $score", score >= minScore)
    }

    @Test fun failedSensor() = with(start("sensor")) {
        run(1.0); ack()
        wo("PX${plc.profile.blocks.size - 1}")
        run(0.5); reset(); restart(); dispatchOne(); check()
    }

    @Test fun liftStall() = with(start("valley")) {
        run(3.0); ack(); wo("OL")
        assertTrue(run(180.0) { plc.state == RideState.STOPPED })
        reset(); restart()
        run(120.0) { done(4) }
        dispatchOne(); check()
    }

    @Test fun launchRollback() = with(start("rollback")) {
        assertTrue("no rollback", run(90.0) { plc.alarms.isActive("ROLLBACK") })
        ack(); wo("LSM")
        run(90.0) { plc.state == RideState.STOPPED }
        reset(); run(30.0); restart()
        assertTrue("no good launch", run(240.0) { finished })
        check(70)
    }

    @Test fun estopMidCourse() = with(start("estop")) {
        run(1.0); ack(); signOff(1)
        plc.inputs.unloadStandEstop = false
        run(0.5); reset(); restart(); dispatchOne(); check()
    }

    @Test fun lowAir() = with(start("air")) {
        run(8.0); ack(); wo("AIR")
        run(60.0) { plc.plant.airPsi >= 92 }
        reset(); restart(); dispatchOne(); check()
    }

    @Test fun weldedContact() = with(start("welded")) {
        run(1.5)
        assertTrue(plc.alarms.isActive("SAFE_DISC"))
        ack(); signOff(1); wo("CB_LOAD"); run(0.5)
        plc.inputs.loadStandEstop = false
        run(0.5); reset(); restart(); dispatchOne(); check()
    }

    @Test fun forgottenForce() = with(start("force")) {
        run(1.0)
        plc.force("GATES_CLOSED_LS", null)
        wo("GATE"); run(3.0)
        dispatchOne(); check()
    }

    @Test fun forgottenForceDispatchedIsPenalised() = with(start("force")) {
        dispatchOne()
        assertTrue(penalties.any { it.points == 30 })
    }

    @Test fun dailyOpening() = with(start("opening")) {
        val i = plc.inputs
        signOff(0)
        i.mode = KeyMode.AUTO; i.loadFootSwitch = true; i.unloadFootSwitch = true
        press { i.powerOn = it }
        for (set in listOf<(Boolean) -> Unit>({ i.estopPressed = it }, { i.loadStandEstop = it }, { i.unloadStandEstop = it })) {
            set(true); run(0.5); set(false); run(0.5); reset()
        }
        press { i.restraintOpen = it }; run(1.0); press { i.restraintClose = it }; run(7.0)
        press { i.gatesOpen = it }; run(2.0); press { i.gatesClose = it }; run(2.0)
        restart(); dispatchOne(); dispatchOne()
        signOff(7); run(0.1)
        check()
    }

    @Test fun lightningEvacuation() = with(start("evac")) {
        val i = plc.inputs
        assertTrue(run(120.0) { plc.state == RideState.STOPPED })
        i.mode = KeyMode.MANUAL
        run(0.5)
        val p = plc.plant
        var guard = 0.0
        while (!done(2) && guard < 1200) {
            val st = p.stationTrain()
            when {
                st != null && st.id !in unloaded && !p.restraintUnlockCmd -> press { i.restraintOpen = it }
                st != null && st.id in unloaded && p.restraintUnlockCmd -> { press { i.restraintClose = it }; run(7.0) }
            }
            val stUnloaded = st == null || st.id in unloaded
            for (b in plc.profile.blocks.indices) i.manualRelease[b] = plc.occ[b] != -1 && plc.nextClear(b) && (b != 0 || stUnloaded)
            i.liftJog = plc.occ[1] != -1
            run(0.5); guard += 0.5
        }
        assertTrue("evac stuck:" + dbg() + " unloaded=$unloaded", done(2))
        i.manualRelease.fill(false); i.liftJog = false
        signOff(3); run(0.1)
        check()
    }

    @Test fun wrongWorkOrderCostsPoints() = with(start("air")) {
        run(1.0)
        wo("MSR")
        assertEquals(10, penalties.sumOf { it.points })
    }
}
