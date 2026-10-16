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
        for ((id, seed, trains) in listOf(Triple("millennium", 1, 3), Triple("millennium", 4, 3), Triple("millennium", 6, 3),
            Triple("millennium", 8, 3), Triple("tt2", 2, 3), Triple("maverick", 7, 6))) {
            val plc = RidePlc(Plant(RideProfile.create(CoasterLibrary.byId(id)), trains))
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

    /** Maverick's dual-load station: trains leave in pairs, the rear one loaded at the rear position. */
    @Test fun maverickDispatchesInPairs() {
        val prof = RideProfile.create(CoasterLibrary.byId("maverick"))
        assertTrue("6 trains allowed", prof.maxTrains == 6)
        val plc = RidePlc(Plant(prof, 6))
        val op = AutoOperator(7).also { it.randomFaults = false }
        plc.restraintWalk = true
        var t = 0.0
        var pairs = 0
        var head: Any? = null
        while (t < 2 * 3600.0) {
            op.update(0.02, plc); plc.scan(0.02); plc.plant.step(0.02); t += 0.02
            val h = plc.alarms.history
            if (h.isNotEmpty() && h.first() !== head) {
                pairs += h.takeWhile { it !== head }.count { it.text.contains("rear of pair") }
                head = h.first()
            }
            assertTrue("contact at t=${t.toInt()}", plc.alarms.active.none { it.code == "COLLIDE" || it.code == "MB_GAP" })
        }
        // ~90 trains an hour, half of them the rear of a pair.
        assertTrue("${plc.dispatchCount} dispatches", plc.dispatchCount >= 160)
        assertTrue("$pairs pairs of ${plc.dispatchCount}", pairs * 2 in plc.dispatchCount - 4..plc.dispatchCount + 4)
    }
}
