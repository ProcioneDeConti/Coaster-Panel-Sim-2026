package com.abemu.coaster.sim

enum class ModuleKind { CPU, INPUT, OUTPUT, ANALOG, SAFETY_IN, SAFETY_OUT }

/** One I/O point. [raw] is the field value (outputs: the value written to the field, forces included). */
class IoPoint(val tag: String, val forcible: Boolean, private val raw: () -> Boolean) {
    fun value(plc: RidePlc) = plc.forces[tag] ?: raw()
}

class IoModule(val slot: Int, val catalog: String, val desc: String, val kind: ModuleKind, val points: List<IoPoint>) {
    fun address(n: Int) = when (kind) {
        ModuleKind.INPUT -> "Local:$slot:I.Data.$n"
        ModuleKind.OUTPUT -> "Local:$slot:O.Data.$n"
        ModuleKind.SAFETY_IN -> "Local:$slot:I.Pt%02dData".format(n)
        ModuleKind.SAFETY_OUT -> "Local:$slot:O.Pt%02dData".format(n)
        else -> "Local:$slot:I.Ch${n}Data"
    }
}

/** The ControlLogix chassis: module layout and point mapping, built from the ride profile. */
object IoMap {
    fun build(plc: RidePlc): List<IoModule> {
        val p = plc.plant
        val i = plc.inputs
        val prof = plc.profile
        val bl = prof.blocks
        val braked = bl.filter { it.stopPos != null }
        val mods = mutableListOf(IoModule(0, "1756-L83ES", "GuardLogix 5580 controller", ModuleKind.CPU, emptyList()))
        var slot = 1

        fun pt(tag: String, forcible: Boolean = false, raw: () -> Boolean) = IoPoint(tag, forcible, raw)
        fun addCards(catalog: String, desc: String, kind: ModuleKind, points: List<IoPoint>, per: Int = 16) {
            points.chunked(per).forEach { mods += IoModule(slot++, catalog, desc, kind, it) }
        }

        addCards("1756-IB16", "Console inputs", ModuleKind.INPUT, listOf(
            pt("ESTOP_PB_NC", true) { !i.estopPressed },
            pt("KEY_AUTO") { i.mode == KeyMode.AUTO }, pt("KEY_MANUAL") { i.mode == KeyMode.MANUAL },
            pt("KEY_MAINT") { i.mode == KeyMode.MAINT }, pt("PWR_ON_PB") { i.powerOn }, pt("PWR_OFF_PB_NC") { !i.powerOff },
            pt("RIDE_START_PB") { i.rideStart }, pt("CS_PB_NC") { !i.controlledStop }, pt("FAULT_RST_PB") { i.faultReset },
            pt("SILENCE_PB") { i.silence }, pt("DISPATCH_L_PB") { i.dispatchL }, pt("DISPATCH_R_PB") { i.dispatchR },
            pt("RESTR_OPEN_PB") { i.restraintOpen }, pt("RESTR_LOCK_PB") { i.restraintClose },
            pt("GATE_OPEN_PB") { i.gatesOpen }, pt("GATE_CLOSE_PB") { i.gatesClose },
        ))
        addCards("1756-IB16", "Field inputs", ModuleKind.INPUT, buildList {
            bl.forEach { b -> add(pt("${b.tag}_PX_ENTRY", true) { p.entrySensor(b.index) }) }
            braked.forEach { b ->
                add(pt("${b.tag}_BRK_CLOSED_PX", true) { p.brakes[b.index].closedProx })
                add(pt("${b.tag}_BRK_OPEN_PX", true) { p.brakes[b.index].openProx })
                add(pt("${b.tag}_PX_STOP") { p.trainAtStop(b.index) != null })
            }
            add(pt("GATES_CLOSED_LS", true) { p.gateClosedSensor })
            add(pt("LOAD_STAND_ESTOP_NC", true) { !i.loadStandEstop })
            add(pt("UNLOAD_STAND_ESTOP_NC", true) { !i.unloadStandEstop })
            add(pt("REMOTE_ESTOP_OK", true) { !p.faults.safetyCircuit })
            add(pt("AIR_PRESS_SW", true) { p.airPsi >= 75 })
            if (prof.type == ProfileType.LIFT) {
                add(pt("LIFT_CREST_PX", true) { p.bodyAt(prof.liftCrestSensor) })
                add(pt("LIFT_VFD_READY", true) { !p.faults.liftVfd })
                add(pt("LIFT_OL1_OK", true) { !p.faults.liftOverload })
            } else {
                add(pt("LSM_EXIT_PX") { p.bodyAt(prof.launchEnd) })
                add(pt("LSM_CREST_PX") { p.bodyAt(prof.crestSensor) })
                add(pt("LSM_DRIVE_READY", true) { !p.faults.lsmFault })
                if (prof.spikeHeight > 0) {
                    add(pt("SWITCH_STATION_LS", true) { p.switchStationLs })
                    add(pt("SWITCH_SPIKE_LS", true) { p.switchSpikeLs })
                }
            }
            for (r in 0 until prof.rows) add(pt("ROW${r + 1}_LOCKED_LS", true) { p.rowLockSensor(r) })
            prof.sensors.forEachIndexed { n, s -> add(pt("${s.name.uppercase().replace(' ', '_')}_PX") { p.proxSensor(n) }) }
        })
        addCards("1756-OB16E", "Outputs", ModuleKind.OUTPUT, buildList {
            braked.forEach { b -> add(pt("${b.tag}_BRK_SOL", true) { p.brakes[b.index].cmdOpen }) }
            bl.forEach { b -> add(pt("${b.tag}_DRIVE_TIRES", true) { p.tiresCmd[b.index] }) }
            if (prof.type == ProfileType.LIFT) add(pt("LIFT_VFD_RUN", true) { p.liftRunCmd })
            else { add(pt("LSM_FIRE", true) { p.lsmFireCmd }); add(pt("LSM_POSITION", true) { p.lsmPositionCmd }) }
            if (prof.spikeHeight > 0) add(pt("SWITCH_TO_SPIKE", true) { p.switchToSpikeCmd })
            add(pt("GATE_OPEN_SOL", true) { p.gatesOpenCmd })
            add(pt("RESTR_UNLOCK_SOL", true) { p.restraintUnlockCmd })
            add(pt("COMPRESSOR_RUN") { p.compressorEnabled && p.airPsi < 110 })
            add(pt("HORN") { plc.hornActive })
            add(pt("DISPATCH_LT") { plc.dispatchReady })
            add(pt("FAULT_LT") { plc.alarms.active.isNotEmpty() })
        })
        mods += IoModule(slot++, "1756-IF8", "Analog inputs", ModuleKind.ANALOG, emptyList())
        val s = plc.safety
        addCards("1756-IB16S", "Safety inputs", ModuleKind.SAFETY_IN, buildList {
            s.stations.forEachIndexed { k, name ->
                add(pt("${name}_ESTOP_CH_A") { s.chA[k] })
                add(pt("${name}_ESTOP_CH_B") { s.chB[k] })
            }
        })
        addCards("1756-OBV8S", "Safety outputs", ModuleKind.SAFETY_OUT, listOf(
            pt("SO_BRAKE_VALVE_PWR") { s.outputsOn },
            pt(if (prof.type == ProfileType.LIFT) "SO_LIFT_VFD_STO" else "SO_LSM_STO") { s.outputsOn },
            pt("SO_MSR1_ENABLE") { s.outputsOn },
        ), per = 8)
        return mods
    }

    /** Smallest standard ControlLogix chassis that fits [slots] modules. */
    fun chassisFor(slots: Int) = listOf(4, 7, 10, 13, 17).first { it >= slots || it == 17 }.let { "1756-A$it" to it }
}
