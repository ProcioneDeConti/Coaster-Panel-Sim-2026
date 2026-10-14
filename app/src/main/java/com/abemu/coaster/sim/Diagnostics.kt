package com.abemu.coaster.sim

/** Troubleshooting text shown for an alarm on the HMI. */
data class Diagnosis(val causes: List<String>, val actions: List<String>)

object Diagnostics {
    /** Look up guidance by alarm code; block-numbered codes (e.g. BRK2, STALL1) match by prefix. */
    fun forCode(code: String): Diagnosis? = table[code] ?: table.entries.firstOrNull { (k, _) ->
        k.endsWith("#") && code.startsWith(k.dropLast(1)) && code.drop(k.length - 1).all { it.isDigit() }
    }?.value

    private val table: Map<String, Diagnosis> = mapOf(
        "ESTOP_PB" to Diagnosis(
            listOf("Console E-Stop pushbutton pressed."),
            listOf("Confirm the reason for the stop and that the ride area is safe.", "Twist-release the console E-Stop.", "Press FAULT RESET, then restart per procedure (track clear warning will sound)."),
        ),
        "ESTOP_LOAD" to Diagnosis(
            listOf("Load stand E-Stop pressed by the load operator."),
            listOf("Check with the load operator for the reason.", "Release the load stand E-Stop.", "Press FAULT RESET."),
        ),
        "ESTOP_UNLOAD" to Diagnosis(
            listOf("Unload stand E-Stop pressed by the unload operator."),
            listOf("Check with the unload operator for the reason.", "Release the unload stand E-Stop.", "Press FAULT RESET."),
        ),
        "SAFETY" to Diagnosis(
            listOf("Remote E-Stop in the safety chain opened.", "Safety relay MSR1 input wiring fault or failed contact."),
            listOf("Check all remote E-Stops and access gates on the circuit.", "Check MSR1 diagnostic LEDs and input wiring.", "Reset only after the cause is found."),
        ),
        "SAFE_DISC" to Diagnosis(
            listOf("The two channels of a dual-channel E-Stop disagreed for more than 0.5 s.", "Welded or failed contact on one channel, or a wiring fault on one channel."),
            listOf("Check channel A/B states for the station on the LOGIC › SAFETY screen.", "Repair the contact block or wiring.", "Press and release that E-Stop so both channels open together (clears the latch), then FAULT RESET."),
        ),
        "SAFE_IO" to Diagnosis(
            listOf("The safety input module lost its connection to the GuardLogix controller.", "Module fault, backplane problem, or the module was removed."),
            listOf("Check the module status LED on the I/O › RACK view.", "Reseat or replace the module.", "Safety outputs stay off until the connection is restored and the fault is reset."),
        ),
        "SAFE_STD" to Diagnosis(
            listOf("The safety task turned its outputs off but the standard task did not stop the ride.", "Usually a forced standard input masking an E-Stop, or a standard logic fault."),
            listOf("Check for I/O forces (title bar) and remove them.", "Compare the E-Stop inputs on the I/O screen with the safety channels.", "Find the cause before resetting: the standard task was running blind."),
        ),
        "AIR" to Diagnosis(
            listOf("Air leak (hose, fitting, brake actuator).", "Compressor off, tripped or unloaded.", "Dryer/filter blocked."),
            listOf("Check the compressor is running (Maint page) and the receiver pressure.", "Listen/inspect for leaks at brake actuators and gates.", "Brakes are spring-applied: trains will stop safely, but do not run below 75 psi."),
        ),
        "LIFT_VFD" to Diagnosis(
            listOf("Lift VFD tripped (overvoltage, overcurrent, drive overtemp).", "Supply disturbance."),
            listOf("Read the fault code on the drive keypad.", "Check incoming supply and braking resistor.", "Reset the drive, then FAULT RESET. If a train is on the lift, anti-rollbacks are holding it."),
        ),
        "LIFT_OL" to Diagnosis(
            listOf("Lift motor overload relay tripped: sustained high current.", "Chain binding, worn bearings, or overweight load."),
            listOf("Check the Trends screen for lift motor current.", "Allow the motor to cool; inspect the chain and drive train.", "Reset the overload relay, then FAULT RESET."),
        ),
        "LSM" to Diagnosis(
            listOf("LSM drive or power supply fault.", "Capacitor bank not charged.", "Stator over-temperature."),
            listOf("Check LSM drive cabinet status.", "Launch is inhibited until cleared; trains wait in the launch/tunnel block.", "Clear the fault at the drive, then FAULT RESET."),
        ),
        "LSM_SPD" to Diagnosis(
            listOf("Launch exit speed below minimum.", "Weak LSM output, heavy train, cold/windy conditions."),
            listOf("Watch the next launches on the Trends screen.", "If it repeats, stop the ride and have the LSM checked. Low speed can cause a rollback."),
        ),
        "ROLLBACK" to Diagnosis(
            listOf("Train did not crest the top hat after launch and rolled back onto the launch track."),
            listOf("Confirm the train is stopped by the rollback brakes and riders are OK.", "Find the cause of the low launch speed before re-launching.", "FAULT RESET; the LSM will return the train to the launch position."),
        ),
        "COLLIDE" to Diagnosis(
            listOf("Two trains came closer than the minimum spacing. Block protection failed (brake stuck open, sensor failure)."),
            listOf("Do NOT restart. Inspect both trains and the track.", "Check brake and proximity sensor faults in the alarm history.", "Investigate before any reset."),
        ),
        "GATE" to Diagnosis(
            listOf("Station air gate closed switch not made: gate obstructed, stuck, or switch misaligned."),
            listOf("Check the gates for obstruction.", "Check the closed limit switch on the I/O screen.", "Cycle the gates open/closed."),
        ),
        "ROW#" to Diagnosis(
            listOf("A restraint row did not confirm locked: rider not seated correctly, restraint not pushed down, or lock switch fault."),
            listOf("Check the row on the Station screen.", "Have the operator check the restraint and rider.", "If the restraint is locked but shows unlocked, check the row lock switch."),
        ),
        "BRK#" to Diagnosis(
            listOf("Brake commanded position does not match feedback: valve stuck, actuator jammed, low air, or proximity switch fault."),
            listOf("Check the brake's CLOSED/OPEN proximity inputs on the I/O screen.", "Check air pressure.", "Inspect the brake actuator and valve before resetting."),
        ),
        "STALL#" to Diagnosis(
            listOf("Train stopped outside a brake/stop position (valley), or tracking lost it (sensor failed off)."),
            listOf("Locate the train visually (Overview map).", "If valleyed, follow the evacuation procedure.", "If it is not there, check block entry sensors and use VERIFY / RESYNC in maintenance."),
        ),
        "UNEXP#" to Diagnosis(
            listOf("Block entry sensor saw a train that tracking did not expect: sensor stuck on, or tracking out of step."),
            listOf("Check the entry proximity sensor on the I/O screen.", "Confirm train positions on the Overview map.", "Repair the sensor; resync tracking in maintenance before restarting."),
        ),
        "OVR#" to Diagnosis(
            listOf("A train entered a block that was already occupied: block brake failed to hold, or a sensor failure."),
            listOf("Do not restart. Inspect the block brakes and sensors involved.", "Check alarm history for the first-out fault."),
        ),
    )
}
