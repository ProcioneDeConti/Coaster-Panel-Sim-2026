package com.abemu.coaster.sim

/** Ladder instructions for the read-only logic viewer. Values are read live from the controller. */
sealed interface LadderEl

/** XIC (examine if closed) or XIO (examine if open, [nc] = true). */
class Contact(val tag: String, val nc: Boolean = false, val value: () -> Boolean) : LadderEl {
    val mnemonic get() = if (nc) "XIO" else "XIC"
    /** Instruction is true (passes power) when its condition is met. */
    val passes get() = value() != nc
}

/** Parallel branch: each leg is a series of instructions. */
class Branch(val legs: List<List<LadderEl>>) : LadderEl

enum class CoilType { OTE, OTL, OTU }

class Coil(val tag: String, val type: CoilType = CoilType.OTE, val value: () -> Boolean)

class Rung(val routine: String, val comment: String, val inputs: List<LadderEl>, val coil: Coil)

fun LadderEl.passes(): Boolean = when (this) {
    is Contact -> passes
    is Branch -> legs.any { leg -> leg.all { it.passes() } }
}

/**
 * Rungs mirroring the controller's logic in RSLogix form. The emulator's PLC is written in Kotlin,
 * so these are a faithful rendering of what it does rather than the code it executes.
 */
object Ladder {
    fun build(plc: RidePlc): List<Rung> {
        val p = plc.plant
        val prof = plc.profile
        val bl = prof.blocks
        val i = plc.inputs
        fun xic(tag: String, v: () -> Boolean) = Contact(tag, false, v)
        fun xio(tag: String, v: () -> Boolean) = Contact(tag, true, v)
        val auto = xic("KEY_AUTO") { i.mode == KeyMode.AUTO }
        val running = { plc.state == RideState.RUNNING }
        val hasE = { plc.alarms.anyActive(Severity.ESTOP) }
        val hasC = { plc.alarms.anyActive(Severity.CSTOP) }

        return buildList {
            // ---------------- MainRoutine / Safety mirror
            add(Rung("Safety", "E-Stop chain as seen by the standard task (auxiliary contacts). The safety task acts on the dual-channel safety inputs independently.",
                listOf(
                    xic("ESTOP_PB_NC") { plc.estopPbOk }, xic("LOAD_STAND_ESTOP_NC") { plc.loadEstopOk },
                    xic("UNLOAD_STAND_ESTOP_NC") { plc.unloadEstopOk }, xic("REMOTE_ESTOP_OK") { plc.remoteEstopOk },
                ),
                Coil("ESTOP_CHAIN_OK") { plc.estopPbOk && plc.loadEstopOk && plc.unloadEstopOk && plc.remoteEstopOk }))
            add(Rung("Safety", "Safety task status. When the safety outputs are OFF the standard task must also be stopped, or SAFE_STD faults.",
                listOf(xic("SafetyTask.OutputsOn") { plc.safety.outputsOn }, xio("SafetyTask.Discrepancy") { plc.safety.anyDiscrepancy },
                    xio("SafetyTask.IOFault") { plc.safety.ioFault }),
                Coil("SAFETY_HEALTHY") { plc.safety.outputsOn && !plc.safety.anyDiscrepancy && !plc.safety.ioFault }))

            // ---------------- Ride state
            add(Rung("RideState", "Ride run latch: start in AUTO with no faults, seal in while running.",
                listOf(
                    Branch(listOf(listOf(xic("RIDE_START_PB") { i.rideStart }), listOf(xic("RIDE_RUN", running)))),
                    auto, xic("CS_PB_NC") { !i.controlledStop }, xio("FAULT_ESTOP", hasE), xio("FAULT_CSTOP", hasC),
                ),
                Coil("RIDE_RUN", value = running)))
            add(Rung("RideState", "Horn sounds for any unsilenced stop-level alarm.",
                listOf(xic("ALARM_UNSILENCED") { plc.alarms.hornDemand }, xic("RIDE_POWER") { plc.powered }),
                Coil("HORN") { plc.hornActive }))

            // ---------------- Station
            add(Rung("Station", "Dispatch permissives – every condition must be made.",
                plc.permissives.indices.map { k ->
                    xic(permTag(plc.permissives[k].label)) { plc.permissives.getOrNull(k)?.ok == true }
                },
                Coil("DISPATCH_READY") { plc.dispatchReady }))
            add(Rung("Station", "Two-hand dispatch (both buttons within 0.5 s) latches the station block release.",
                listOf(
                    xic("DISPATCH_L_PB") { i.dispatchL || (i.singleButtonDispatch && i.dispatchR) },
                    xic("DISPATCH_R_PB") { i.dispatchR || (i.singleButtonDispatch && i.dispatchL) },
                    xic("DISPATCH_READY") { plc.dispatchReady },
                ),
                Coil("${bl[0].tag}_RELEASE", CoilType.OTL) { plc.releaseLatch[0] }))

            // ---------------- Blocks
            for (b in bl) {
                val k = b.index
                val nx = plc.next(k)
                if (b.kind == BlockKind.BRAKE_RUN || b.kind == BlockKind.UNLOAD) {
                    add(Rung("Blocks", "${b.tag} ${b.name}: auto release when the next block is clear.",
                        listOf(
                            xic("RIDE_RUN", running), xic("${b.tag}_OCCUPIED") { plc.occ[k] != -1 },
                            xio("${bl[nx].tag}_OCCUPIED") { plc.occ[nx] != -1 },
                            xio("${bl[nx].tag}_PX_ENTRY") { plc.entry(nx) },
                        ),
                        Coil("${b.tag}_RELEASE", CoilType.OTL) { plc.releaseLatch[k] }))
                }
                if (b.stopPos != null && b.kind != BlockKind.LAUNCH) {
                    add(Rung("Blocks", "${b.tag} brake solenoid: open on release latch, manual release, or maintenance. Brakes are spring-applied.",
                        listOf(
                            Branch(listOf(
                                listOf(xic("${b.tag}_RELEASE") { plc.releaseLatch[k] }),
                                listOf(xic("KEY_MANUAL") { i.mode == KeyMode.MANUAL }, xic("${b.tag}_MAN_REL_PB") { i.manualRelease[k] }),
                                listOf(xic("KEY_MAINT") { i.mode == KeyMode.MAINT }, xic("${b.tag}_MAINT_OPEN") { plc.maintBrakeOpen[k] }),
                            )),
                            xio("FAULT_ESTOP") { plc.eStopped },
                        ),
                        Coil("${b.tag}_BRK_SOL") { p.brakes[k].cmdOpen }))
                }
            }

            // ---------------- Lift / launch
            if (prof.type == ProfileType.LIFT) {
                add(Rung("Lift", "Lift chain runs in AUTO unless a train is held at the crest; jog in MANUAL/MAINT.",
                    listOf(
                        Branch(listOf(
                            listOf(xic("RIDE_RUN", running)),
                            listOf(xic("KEY_MANUAL") { i.mode == KeyMode.MANUAL }, xic("LIFT_JOG_PB") { i.liftJog }),
                        )),
                        xio("CREST_HOLD") { plc.liftCrestHold }, xio("FAULT_ESTOP") { plc.eStopped },
                    ),
                    Coil("LIFT_VFD_RUN") { p.liftRunCmd }))
                add(Rung("Lift", "Crest hold: train at the crest sensor and the next block is occupied.",
                    listOf(xic("LIFT_CREST_PX") { plc.crestPx }, xio("${bl[plc.next(1)].tag}_CLEAR") { plc.nextClear(1) }),
                    Coil("CREST_HOLD") { plc.liftCrestHold }))
            } else {
                add(Rung("Launch", "LSM fire while the launch sequencer is in FIRING.",
                    listOf(xic("LAUNCH_SEQ.FIRING") { plc.launchState == LaunchState.FIRING }, xic("LSM_DRIVE_READY") { plc.lsmReady },
                        xio("FAULT_ESTOP") { plc.eStopped }),
                    Coil("LSM_FIRE") { p.lsmFireCmd }))
                add(Rung("Launch", "Rollback detected when the train fails to crest within the verify time.",
                    listOf(xic("LAUNCH_SEQ.VERIFY") { plc.launchState == LaunchState.VERIFY || plc.launchState == LaunchState.ROLLBACK },
                        xio("LSM_CREST_PX") { p.bodyAt(prof.crestSensor) }),
                    Coil("ROLLBACK", CoilType.OTL) { plc.launchState == LaunchState.ROLLBACK }))
                if (plc.hasSwitch) {
                    add(Rung("Launch", "Switch track slides to the spike line once a train is at the launch position; returns to the station line after the launch when the table is clear.",
                        listOf(xic("LAUNCH_REQ.SPIKE") { plc.switchCmd }, xio("FAULT_ESTOP") { plc.eStopped }),
                        Coil("SWITCH_TO_SPIKE") { p.switchToSpikeCmd }))
                    add(Rung("Launch", "Launch charge permitted only with the switch track locked to the spike line.",
                        listOf(xic("SWITCH_SPIKE_LS") { plc.switchSpikeLs }, xic("LSM_DRIVE_READY") { plc.lsmReady }),
                        Coil("SWING_PERMIT") { plc.switchSpikeLs && plc.lsmReady }))
                }
            }

            // ---------------- Station outputs
            add(Rung("Station", "Station gate and restraint solenoids follow their HMI/console commands.",
                listOf(xic("GATE_OPEN_CMD") { p.gatesOpenCmd }),
                Coil("GATE_OPEN_SOL") { p.gatesOpenCmd }))
            add(Rung("Station", "Fault lamp on while any alarm is active.",
                listOf(xic("ALARM_ANY") { plc.alarms.active.isNotEmpty() }),
                Coil("FAULT_LT") { plc.alarms.active.isNotEmpty() }))
        }
    }

    /** "Restraints locked – all rows" -> "PERM_RESTRAINTS_LOCKED_ALL_ROWS" */
    private fun permTag(label: String) = "PERM_" + label.uppercase()
        .replace("≥", "GE").replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(28)
}
