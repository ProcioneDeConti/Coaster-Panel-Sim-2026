package com.abemu.coaster

import com.abemu.coaster.sim.AutoOperator
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoOperatorTest {
    private fun runFor(type: ProfileType, secs: Double, faults: Boolean, seed: Int): Pair<RidePlc, AutoOperator> {
        val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.default(type)), 3))
        val op = AutoOperator(seed).also { it.randomFaults = faults }
        plc.restraintWalk = true
        var t = 0.0
        val phases = mutableMapOf<AutoOperator.Phase, Double>()
        while (t < secs) {
            op.update(0.02, plc); plc.scan(0.02); plc.plant.step(0.02); t += 0.02
            phases[op.phase] = (phases[op.phase] ?: 0.0) + 0.02
        }
        println("$type faults=$faults dispatches=${plc.dispatchCount} repairs=${op.repairs} phase=${op.phase} state=${plc.state} last=${op.lastFault} " +
            phases.mapValues { it.value.toInt() })
        return plc to op
    }

    @Test fun runsLiftRideByItself() {
        val (plc, op) = runFor(ProfileType.LIFT, 1800.0, false, 1)
        assertTrue("${plc.dispatchCount} ${op.status}", plc.dispatchCount >= 20 && op.repairs == 0)
    }

    @Test fun runsLaunchRideByItself() {
        val (plc, _) = runFor(ProfileType.LAUNCH, 1800.0, false, 2)
        assertTrue("${plc.dispatchCount}", plc.dispatchCount >= 20)
    }

    @Test fun maintenanceRecoversRandomFaults() {
        for (type in listOf(ProfileType.LIFT, ProfileType.LAUNCH)) for (seed in 1..4) {
            val (plc, op) = runFor(type, 3 * 3600.0, true, seed)
            assertTrue("$type $seed ${plc.dispatchCount} ${op.repairs} ${op.status}", op.repairs >= 3 && plc.dispatchCount >= 100)
        }
    }
}
