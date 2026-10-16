package com.abemu.coaster

import com.abemu.coaster.sim.AutoOperator
import com.abemu.coaster.sim.CoasterLibrary
import com.abemu.coaster.sim.Plant
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.RideProfile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desk mode with random faults on the moving-block rides: failed block sensors, lift / LSM faults mid-move and
 * maintenance resyncs must never let a train run into another, and the crew must keep the ride going.
 */
class MovingBlockFaultTest {
    @Test fun randomFaultsNeverCauseContact() {
        for ((id, seed) in listOf("millennium" to 1, "millennium" to 4, "millennium" to 6, "millennium" to 8, "tt2" to 2)) {
            val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId(id)), 3))
            val op = AutoOperator(seed).also { it.randomFaults = true }
            plc.restraintWalk = true
            var t = 0.0
            while (t < 4 * 3600.0) {
                op.update(0.02, plc); plc.scan(0.02); plc.plant.step(0.02); t += 0.02
                assertTrue("$id seed $seed: contact at t=${t.toInt()} after ${op.repairs} repairs",
                    plc.alarms.active.none { it.code == "COLLIDE" || it.code == "MB_GAP" })
            }
            assertTrue("$id seed $seed: only ${plc.dispatchCount} dispatches", plc.dispatchCount >= 80)
        }
    }
}
