package com.abemu.coaster.sim

import kotlin.math.max

enum class DrillKind(val label: String) { FAULT("FAULT RECOVERY"), OPENING("DAILY OPENING"), EVACUATION("EVACUATION") }

/**
 * One checklist item. Signoff steps are completed by the trainee pressing SIGN OFF. [afterPrev] steps
 * are state checks (e.g. "ride running") that only count once the previous step is done; the others
 * are actions, and doing one before an earlier step costs an out-of-order penalty.
 */
class Step(
    val text: String,
    val signoff: Boolean = false,
    val afterPrev: Boolean = false,
    val done: (Drill, Int) -> Boolean = { _, _ -> false },
)

/** A field work order the trainee can send a technician to do. */
class Repair(val id: String, val label: String)

class Scenario(
    val id: String,
    val title: String,
    val kind: DrillKind,
    val type: ProfileType,
    val briefing: String,
    val parSec: Double,
    /** Dispatches the warm-up makes before it starts looking for [trigger]. -1 = no warm-up (ride left powered off). */
    val warmDispatches: Int = 2,
    /** Warm-up runs the ride until this is true, then the drill starts and [inject] runs. */
    val trigger: (RidePlc) -> Boolean = { true },
    val inject: (Drill) -> Unit = {},
    /** Correct work orders for this ride and what each fixes. */
    val fixes: (RidePlc) -> Map<String, (RidePlc) -> Unit> = { emptyMap() },
    val steps: List<Step>,
    val noAutoRestart: Boolean = false,
    val trains: Int = 3,
)

class Penalty(val t: Double, val points: Int, val text: String)

data class DrillResult(val title: String, val score: Int, val grade: String, val seconds: Double)

/** A drill in progress: tracks the checklist, penalties and score. Updated once per PLC scan. */
class Drill(val scenario: Scenario, val plc: RidePlc) {
    var t = 0.0; private set
    val steps get() = scenario.steps
    private val doneAt = arrayOfNulls<Double>(steps.size)
    private val dispAt = arrayOfNulls<Int>(steps.size)
    val penalties = mutableListOf<Penalty>()
    val workOrders = Scenarios.workOrders(plc.profile)
    private val fixes = scenario.fixes(plc)
    val repaired = mutableSetOf<String>()
    /** Alarm codes seen active since the drill started. */
    val seen = mutableSetOf<String>()
    private val signed = mutableSetOf<Int>()
    /** Trains whose restraints were opened at the station (evacuated). */
    val unloaded = mutableSetOf<Int>()
    var launchesOk = 0; private set
    var restraintsCycled = false; private set
    var gatesCycled = false; private set
    /** Scenario-specific marker, e.g. the id of the train stalled on the lift. */
    var mark = -1
    var failed: String? = null; private set
    var finished = false; private set

    private var initialForces = emptySet<String>()
    private val penalizedForces = mutableSetOf<String>()
    private var maintPenalized = false
    private var prevReset = false
    private var prevStart = false
    private var prevLaunch = plc.launchState
    private var lastDispatch = plc.dispatchCount
    private var lastResetPenalty = -99.0
    private var sawRestraintOpen = false
    private var sawGateOpen = false
    /** Scans left to hold the console controlled-stop input (used by injects). */
    var csPulse = 0

    fun begin() {
        scenario.inject(this)
        initialForces = plc.forces.all.keys.toSet()
        if (csPulse > 0) plc.inputs.controlledStop = true
        plc.alarms.info("DRILL", "Training drill started: ${scenario.title}")
    }

    fun done(k: Int) = doneAt[k] != null
    fun doneTime(k: Int) = doneAt[k]
    val nextStep get() = steps.indices.firstOrNull { doneAt[it] == null }
    val allRepaired get() = fixes.keys.all { it in repaired }
    /** No stop-level alarms active. */
    val clear get() = plc.alarms.active.none { it.severity.ordinal <= Severity.CSTOP.ordinal }
    val acked get() = seen.isNotEmpty() && !plc.alarms.anyUnacked
    fun dispatchedSince(k: Int) = dispAt[k]?.let { plc.dispatchCount > it } == true
    fun canSignOff(k: Int) = steps[k].signoff && !done(k) && !finished

    fun penalize(points: Int, text: String) {
        penalties += Penalty(t, points, text)
        plc.alarms.info("DRILL", "Penalty −$points: $text")
    }

    fun signOff(k: Int) { if (canSignOff(k)) signed += k }

    fun repair(r: Repair) {
        if (finished) return
        val fix = fixes[r.id]
        if (fix == null || r.id in repaired) {
            penalize(10, "Unnecessary work order: ${r.label}")
            return
        }
        fix(plc)
        repaired += r.id
        plc.alarms.info("WO", "Work order complete: ${r.label}")
    }

    fun abort() {
        if (finished) return
        failed = "Drill aborted"
        finished = true
    }

    private fun fail(why: String) {
        failed = why
        finished = true
        plc.alarms.info("DRILL", "Drill failed: $why")
    }

    fun update(dt: Double) {
        if (finished) return
        t += dt
        val i = plc.inputs
        if (csPulse > 0 && --csPulse == 0) i.controlledStop = false
        plc.alarms.active.forEach { seen += it.code }

        // ---- Unsafe or sloppy actions
        if (i.faultReset && !prevReset && t - lastResetPenalty > 5 &&
            plc.alarms.active.any { it.condition && it.severity.ordinal <= Severity.CSTOP.ordinal }
        ) {
            lastResetPenalty = t
            penalize(3, "Fault reset pressed with the fault still present")
        }
        prevReset = i.faultReset
        for (tag in plc.forces.all.keys) if (tag !in initialForces && penalizedForces.add(tag)) {
            penalize(20, "Unsafe override: force installed on $tag")
        }
        if (i.mode == KeyMode.MAINT && !maintPenalized) {
            maintPenalized = true
            penalize(10, "Maintenance mode used – interlocks bypassed")
        }
        if (scenario.noAutoRestart && i.rideStart && !prevStart && i.mode == KeyMode.AUTO) {
            penalize(20, "Ride start attempted during the evacuation")
        }
        prevStart = i.rideStart
        if (plc.dispatchCount > lastDispatch && initialForces.any { plc.forces[it] != null }) {
            penalize(30, "Dispatched with a forced input still installed")
        }
        lastDispatch = plc.dispatchCount
        if (plc.alarms.isActive("COLLIDE")) { fail("Train collision"); return }

        // ---- Trackers used by checklist steps
        if (prevLaunch == LaunchState.VERIFY && plc.launchState == LaunchState.IDLE) launchesOk++
        prevLaunch = plc.launchState
        val p = plc.plant
        p.stationTrain()?.let { st -> if (p.restraintUnlockCmd) unloaded += st.id }
        if (p.restraintUnlockCmd && p.stationTrain() != null) sawRestraintOpen = true
        if (sawRestraintOpen && !p.restraintUnlockCmd && plc.allRowsLocked) restraintsCycled = true
        if (p.gatePos > 0.95) sawGateOpen = true
        if (sawGateOpen && p.gateClosedSensor) gatesCycled = true

        // ---- Checklist
        for (k in steps.indices) {
            if (doneAt[k] != null) continue
            val s = steps[k]
            if (s.afterPrev && k > 0 && doneAt[k - 1] == null) continue
            val ok = if (s.signoff) k in signed else s.done(this, k)
            if (!ok) continue
            doneAt[k] = t
            dispAt[k] = plc.dispatchCount
            val skipped = (0 until k).firstOrNull { doneAt[it] == null }
            if (skipped != null) penalize(5, "Out of order: “${s.text}” before “${steps[skipped].text}”")
        }
        if (doneAt.all { it != null }) {
            finished = true
            plc.alarms.info("DRILL", "Drill complete: ${scenario.title} – score $score ($grade)")
        }
    }

    val timePenalty get() = (max(0.0, t - scenario.parSec) / 10).toInt().coerceAtMost(30)
    val score get() = if (failed != null) 0 else (100 - penalties.sumOf { it.points } - timePenalty).coerceAtLeast(0)
    val grade get() = when {
        failed != null -> "FAIL"
        score >= 90 -> "A"
        score >= 80 -> "B"
        score >= 70 -> "C"
        else -> "RETRAIN"
    }

    fun result() = DrillResult(scenario.title, score, grade, t)
}

/** Drives the console headlessly to put a ride into a known state before a drill. */
object Autopilot {
    private const val DT = 0.02

    /** Run for [secs]; returns true early if [stop] becomes true. */
    fun run(plc: RidePlc, secs: Double, stop: () -> Boolean = { false }): Boolean {
        var t = 0.0
        while (t < secs) {
            plc.scan(DT); plc.plant.step(DT); t += DT
            if (stop()) return true
        }
        return false
    }

    private fun press(plc: RidePlc, stop: () -> Boolean, set: (Boolean) -> Unit): Boolean {
        set(true)
        val a = run(plc, 0.1, stop)
        set(false)
        return a || run(plc, 0.1, stop)
    }

    /** Power on, start in AUTO and dispatch trains until [trigger] holds (after [minDispatches]). */
    fun warmUp(plc: RidePlc, minDispatches: Int, trigger: (RidePlc) -> Boolean, maxSecs: Double = 900.0): Boolean {
        val i = plc.inputs
        var dispatched = 0
        val stop = { dispatched >= minDispatches && trigger(plc) }
        i.mode = KeyMode.AUTO
        press(plc, { false }) { i.powerOn = it }
        press(plc, { false }) { i.rideStart = it }
        run(plc, 7.0)
        var t = 0.0
        while (t < maxSecs) {
            if (stop()) return true
            if (plc.state == RideState.RUNNING && plc.trainInStation && !plc.releaseLatch[0]) {
                // Load cycle; abandoned part-way if the trigger fires.
                if (press(plc, stop) { i.restraintOpen = it }) return true
                if (press(plc, stop) { i.gatesOpen = it }) return true
                if (run(plc, 2.0, stop)) return true
                if (press(plc, stop) { i.gatesClose = it }) return true
                if (press(plc, stop) { i.restraintClose = it }) return true
                if (run(plc, 7.0, stop)) return true
                if (press(plc, stop) { i.loadReady = it }) return true
                if (press(plc, stop) { i.unloadReady = it }) return true
                if (run(plc, 60.0) { stop() || plc.dispatchReady } && stop()) return true
                if (plc.dispatchReady) {
                    i.dispatchL = true; i.dispatchR = true
                    run(plc, 0.2)
                    i.dispatchL = false; i.dispatchR = false
                    dispatched++
                }
                t += 75
            } else {
                if (run(plc, 0.5, stop)) return true
                t += 0.5
            }
        }
        return false
    }
}

object Scenarios {
    fun workOrders(p: RideProfile): List<Repair> = buildList {
        p.blocks.forEach { add(Repair("PX${it.index}", "Replace ${it.tag} entry proximity sensor")) }
        if (p.type == ProfileType.LIFT) {
            add(Repair("OL", "Reset lift motor overload OL1 and inspect the chain"))
            add(Repair("VFD", "Reset the lift VFD"))
        } else {
            add(Repair("LSM", "Service the LSM drive – restore launch output"))
        }
        add(Repair("AIR", "Locate and repair the air leak"))
        add(Repair("GATE", "Free the stuck station air gate"))
        add(Repair("CB_LOAD", "Replace load stand E-Stop contact block"))
        add(Repair("MSR", "Replace safety relay MSR1"))
    }

    // ---- Shared steps
    private fun ack() = Step("Acknowledge the alarm and read the first-out") { d, _ -> d.acked }
    private fun repair(text: String) = Step(text) { d, _ -> d.allRepaired }
    private fun reset() = Step("Fault reset – all stop faults cleared", afterPrev = true) { d, _ ->
        d.clear && d.plc.state != RideState.ESTOP
    }
    private fun restart() = Step("Restart the ride in AUTO", afterPrev = true) { d, _ -> d.plc.state == RideState.RUNNING }
    private fun dispatch() = Step("Dispatch a train", afterPrev = true) { d, k -> d.dispatchedSince(k - 1) }

    private fun liftLen(p: RideProfile) = p.ahead(p.liftStart, p.liftEnd)
    private fun onLift(p: RideProfile, s: Double, from: Double, to: Double) =
        p.inRange(s, p.liftStart + from * liftLen(p), p.liftStart + to * liftLen(p))

    val all: List<Scenario> = listOf(
        Scenario(
            "sensor", "Failed block sensor", DrillKind.FAULT, ProfileType.LIFT,
            "A block entry proximity sensor has failed ON. The PLC sees a train where there is none and has " +
                "E-Stopped the ride. Find the sensor from the alarms and I/O screen, raise the right work order, then recover.",
            parSec = 150.0, trains = 2,
            trigger = { plc ->
                val n = plc.profile.blocks.size
                plc.occ[n - 2] == -1 && plc.occ[n - 1] == -1 && !plc.plant.entrySensor(n - 1)
            },
            inject = { d -> d.plc.plant.faults.sensorStuckOn += d.plc.profile.blocks.size - 1 },
            fixes = { plc -> val b = plc.profile.blocks.size - 1; mapOf("PX$b" to { x -> x.plant.faults.sensorStuckOn -= b }) },
            steps = listOf(ack(), repair("Replace the failed sensor (work order)"), reset(), restart(), dispatch()),
        ),
        Scenario(
            "valley", "Train stalled on the lift", DrillKind.FAULT, ProfileType.LIFT,
            "The lift motor overload has tripped with a loaded train part-way up. Anti-rollbacks are holding it. " +
                "Get the lift running again and bring the train over the crest.",
            parSec = 150.0,
            trigger = { plc -> plc.state == RideState.RUNNING && plc.plant.trains.any { onLift(plc.profile, it.s, 0.3, 0.7) } },
            inject = { d ->
                d.mark = d.plc.plant.trains.first { onLift(d.plc.profile, it.s, 0.25, 0.75) }.id
                d.plc.plant.faults.liftOverload = true
            },
            fixes = { mapOf("OL" to { x -> x.plant.faults.liftOverload = false }) },
            steps = listOf(
                ack(), repair("Reset the overload (work order)"), reset(), restart(),
                Step("Stalled train crests the lift", afterPrev = true) { d, _ ->
                    val tr = d.plc.plant.trains.firstOrNull { it.id == d.mark }
                    tr == null || d.plc.profile.blockAt(tr.s).index != 1
                },
                dispatch(),
            ),
        ),
        Scenario(
            "rollback", "Launch rollback", DrillKind.FAULT, ProfileType.LAUNCH,
            "Launch output is weak. The next launch will not make it over the top hat. Recover the train, get the " +
                "LSM serviced, and prove a good launch before you carry on.",
            parSec = 180.0,
            trigger = { plc -> plc.state == RideState.RUNNING && plc.occ[1] != -1 && plc.launchState == LaunchState.IDLE },
            inject = { d -> d.plc.plant.faults.launchUnderspeed = true },
            fixes = { mapOf("LSM" to { x -> x.plant.faults.launchUnderspeed = false }) },
            steps = listOf(
                Step("Rollback detected – acknowledge the alarm") { d, _ -> "ROLLBACK" in d.seen && d.acked },
                repair("Service the LSM drive (work order)"), reset(), restart(),
                Step("Successful launch over the top hat", afterPrev = true) { d, _ -> d.launchesOk > 0 && !d.plc.alarms.isActive("ROLLBACK") },
            ),
        ),
        Scenario(
            "estop", "E-Stop mid-course", DrillKind.FAULT, ProfileType.LIFT,
            "A guest dropped a phone onto the track by the unload platform and the unload operator hit their E-Stop " +
                "with a train out on the course. Confirm the area is clear BEFORE releasing the E-Stop, then recover.",
            parSec = 90.0,
            trigger = { plc -> plc.state == RideState.RUNNING && plc.plant.trains.any { it.v > 10 } },
            inject = { d -> d.plc.inputs.unloadStandEstop = true },
            steps = listOf(
                ack(),
                Step("Confirm with the unload operator that the track is clear", signoff = true),
                Step("Release the unload stand E-Stop") { d, _ -> !d.plc.inputs.unloadStandEstop },
                reset(), restart(), dispatch(),
            ),
        ),
        Scenario(
            "air", "Low air pressure", DrillKind.FAULT, ProfileType.LIFT,
            "An air line has split. Brakes are spring-applied so the ride will stop safely, but nothing can open " +
                "below 75 psi. Get the leak repaired and wait for pressure before restarting.",
            parSec = 150.0,
            inject = { d -> d.plc.plant.faults.airLeak = true },
            fixes = { mapOf("AIR" to { x -> x.plant.faults.airLeak = false }) },
            steps = listOf(
                ack(), repair("Repair the air leak (work order)"),
                Step("Air pressure back above 90 psi", afterPrev = true) { d, _ -> d.plc.plant.airPsi >= 90 },
                reset(), restart(), dispatch(),
            ),
        ),
        Scenario(
            "welded", "Welded E-Stop contact", DrillKind.FAULT, ProfileType.LIFT,
            "The load operator pressed their E-Stop for a guest issue. The guest is fine, but the safety system is " +
                "reporting something else. Check LOGIC › SAFETY TASK.",
            parSec = 180.0,
            inject = { d ->
                d.plc.plant.faults.estopWeldedB = 1
                d.plc.inputs.loadStandEstop = true
            },
            fixes = { mapOf("CB_LOAD" to { x -> x.plant.faults.estopWeldedB = -1 }) },
            steps = listOf(
                ack(),
                Step("Guest issue resolved with the load operator", signoff = true),
                repair("Replace the failed contact block (work order)"),
                Step("Cycle the load stand E-Stop so both channels open and clear the discrepancy", afterPrev = true) { d, _ ->
                    !d.plc.safety.discrepancy[1] && !d.plc.inputs.loadStandEstop
                },
                reset(), restart(), dispatch(),
            ),
        ),
        Scenario(
            "force", "Forgotten force", DrillKind.FAULT, ProfileType.LIFT,
            "Maintenance worked on the station gates overnight. Before you dispatch, check that everything the PLC " +
                "sees is real. (Maintenance PIN 2580.)",
            parSec = 150.0,
            trigger = { plc -> plc.state == RideState.RUNNING && plc.trainInStation && !plc.releaseLatch[0] },
            inject = { d ->
                d.plc.plant.faults.gateStuck = true
                d.plc.force("GATES_CLOSED_LS", true)
            },
            fixes = { mapOf("GATE" to { x -> x.plant.faults.gateStuck = false }) },
            steps = listOf(
                Step("Find and remove the I/O force") { d, _ -> d.plc.forces["GATES_CLOSED_LS"] == null },
                repair("Free the stuck gate (work order)"),
                Step("Gates confirmed closed by the limit switch", afterPrev = true) { d, _ -> d.plc.plant.gateClosedSensor },
                Step("Dispatch a train", afterPrev = true) { d, k -> d.dispatchedSince(k - 1) },
            ),
        ),
        Scenario(
            "opening", "Daily opening checklist", DrillKind.OPENING, ProfileType.LIFT,
            "Open the ride for the day. Power up, prove every E-Stop, cycle the station equipment and run empty test " +
                "trains before releasing the ride to guests.",
            parSec = 300.0, warmDispatches = -1,
            steps = listOf(
                Step("Track walk complete – no obstructions", signoff = true),
                Step("Ride power ON") { d, _ -> d.plc.powered },
                Step("E-Stop test: console, load stand and unload stand") { d, _ ->
                    listOf("ESTOP_PB", "ESTOP_LOAD", "ESTOP_UNLOAD").all { it in d.seen }
                },
                Step("Fault reset – no alarms active", afterPrev = true) { d, _ ->
                    d.plc.alarms.active.isEmpty() && d.plc.state != RideState.ESTOP
                },
                Step("Restraints cycled open and locked") { d, _ -> d.restraintsCycled },
                Step("Station gates cycled open and closed") { d, _ -> d.gatesCycled },
                Step("Two empty test trains dispatched") { d, _ -> d.plc.dispatchCount >= 2 },
                Step("Release the ride to guests", signoff = true),
            ),
        ),
        Scenario(
            "evac", "Lightning evacuation", DrillKind.EVACUATION, ProfileType.LIFT,
            "Lightning within 8 miles. The ride is being brought to a controlled stop. Using MANUAL block control, " +
                "bring every train back to the station and unload it. Do NOT restart in AUTO.",
            parSec = 420.0, warmDispatches = 3, noAutoRestart = true,
            trigger = { plc -> plc.state == RideState.RUNNING && plc.occ.count { it != -1 } >= 3 && plc.occ[0] == -1 },
            inject = { d -> d.csPulse = 2 },
            steps = listOf(
                Step("Controlled stop complete", afterPrev = true) { d, _ -> d.plc.state == RideState.STOPPED },
                Step("Key switch to MANUAL") { d, _ -> d.plc.inputs.mode == KeyMode.MANUAL },
                Step("Every train brought to the station and unloaded") { d, _ ->
                    d.plc.plant.trains.all { it.id in d.unloaded }
                },
                Step("All guests clear – ride secured", signoff = true),
            ),
        ),
    )
}
