# Coaster Control Emulator — Design

An Android tablet app (landscape) emulating an Allen-Bradley–style roller coaster control
system: a PanelView-style touchscreen HMI beside a physical operator console, both driven by a
simulated PLC running a fixed-rate scan.

## Architecture

```
 UI (Jetpack Compose)                         Simulation (pure Kotlin)
 ┌──────────────────────┬──────────────────┐   ┌──────────────────────────────┐
 │ HMI (PanelView)      │ Operator Console │   │ RidePlc   – 20 ms scan:      │
 │  Overview / Station  │  Keyswitch, E-Stop│──▶│   read inputs → logic →      │
 │  Alarms / Maint / I/O│  pushbuttons,     │   │   write outputs              │
 │  Sim (instructor)    │  pilot lights     │◀──│ TrackPhysics – trains, grav.,│
 └──────────────────────┴──────────────────┘   │   brakes, lift, LSM, tires   │
            reads tags / sets input bits        │ RideProfile – track geometry │
                                                └──────────────────────────────┘
```

* The UI never moves trains directly. It sets **input bits** (buttons) and the PLC computes
  **outputs** (brake valves, lift VFD, LSM, tires, lamps), which the physics model acts on.
* The PLC only "sees" the trains through simulated **proximity sensors**, **brake position
  feedback**, **restraint lock switches** and **speed sensors** — so injected sensor faults
  behave like they would on a real ride.

## Ride profiles

| Profile | Blocks |
|---|---|
| Lift hill | Station → Lift → Mid-Course Brake Run (MCBR) → Final Brake Run / Transfer |
| Launch    | Station → Launch (LSM) + top hat → MCBR → Final Brake Run / Transfer |

The track is an elevation profile `h(s)`. Trains move under gravity, rolling resistance and
aero drag, plus whatever drive is active: the lift chain, LSM thrust, drive tires, or brakes
with stop-point control. With 1–3 trains, the train count is chosen in maintenance.

## Control logic (summary)

* **Ride states:** Power Off → Stopped → Running (Auto) → Controlled Stop → Stopped; E-Stop
  from any state, latched until the E-Stop is released and the fault is reset.
* **Modes (keyswitch):** OFF / MAINT / MANUAL / AUTO.
  * AUTO: full block logic plus operator dispatch.
  * MANUAL: hold-to-run release of individual blocks, still interlocked (next block must be clear).
  * MAINT: interlocks bypassed for brakes, restraints and gates; transfer track and train
    storage are available.
* **Block zoning:** one train per block. A block's exit brake opens only when the next block is
  clear. Occupancy is tracked by sensor events. An unexpected sensor hit gives a block overrun
  E-Stop; a train not arriving in time gives a stall/valley alarm.
* **Dispatch permissives:** Auto + Running, no active faults, train at the station stop, all
  restraints locked, gates closed, load and unload operators ready, next block clear, then
  two-hand dispatch (both buttons pressed within 0.5 s).
* **Launch:** charge (3 s) → fire → crest sensor must be reached within a time window,
  otherwise a rollback alarm. The LSM repositions the train after the fault is reset.

## Faults (instructor injection)

E-Stop circuit, brake fails to close or open, low air pressure, lift VFD fault, lift motor
overload, LSM fault, launch underspeed (causes a rollback), stuck or failed proximity sensors,
restraint not locked, gate not closed, train stall.

## HMI screens

Overview (elevation track diagram, live blocks, brakes and trains) · Station (restraints,
gates, permissive checklist) · Alarms (active and history, acknowledge) · Maint (manual
controls, transfer, train count, profile) · I/O (AB-style tag addresses with live bits) ·
Sim (fault injection).
