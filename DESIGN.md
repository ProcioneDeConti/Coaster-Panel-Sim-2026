# Coaster Control Emulator — Design

An Android tablet app (landscape) that emulates an Allen-Bradley–style roller coaster control
system: a PanelView Plus 1000–style touchscreen HMI beside a physical operator console, both
driven by a simulated PLC running a fixed 20 ms scan.

## Architecture

```
 UI (Jetpack Compose)                          Simulation (pure Kotlin, unit-tested)
 ┌──────────────────────┬───────────────────┐   ┌───────────────────────────────────┐
 │ HMI (PanelView)      │ Operator console  │   │ RidePlc  – 20 ms scan: inputs →   │
 │ F1 Overview (plan /  │ keyswitch, E-Stop,│──▶│   logic → outputs; alarms, block  │
 │    profile)          │ pushbuttons, pilot│   │   tracking, dispatch, launch,     │
 │ F2 Station  F3 Alarms│ lights, load and  │◀──│   transfer                        │
 │ F4 Manual/Maint (map)│ unload stands     │   │ Plant    – trains, gravity, brakes│
 │ F5 I/O  F6 Instructor│                   │   │   lift, LSM, tires, gates, air    │
 │ F7 Trends  DEV editor│                   │   │ CoasterLibrary – ride generator   │
 └──────────────────────┴───────────────────┘   │ RealLayouts – OSM plan geometry   │
                                                 │ TrendRecorder, Diagnostics        │
                                                 └───────────────────────────────────┘
```

* The UI never moves trains. It sets **input bits**. The PLC computes **outputs** (brake valves,
  lift VFD, LSM, tires, gates, restraints), and the plant acts on them.
* The PLC sees trains only through simulated **proximity sensors**, **brake feedback**,
  **restraint lock switches** and **speed sensors**, so injected faults behave as on a real ride.

## Ride library

16 coasters: 9 lift-hill and 7 LSM launch. Tracks are generated from a `CoasterSpec`: lift
height or launch speed, hills, MCBR, train size and block layout. The generator keeps every hill
within the available energy and sizes brake runs from arrival speed. Block layouts vary per ride:

* Optional mid-course brake run, or none (the whole course is one block).
* Optional separate **unload station** (UNLOAD block: dwell, then release to load).
* Optional **tunnel block** with a mid-course LSM launch.

Real Cedar Point rides (Millennium Force, Magnum XL-200, Maverick, Gemini, Top Thrill 2) use
top-down layouts from OpenStreetMap (`roller_coaster=track` / `roller_coaster=station`; see
`tools/osm/`). Their simulated course is stretched to the real track length.

## Control logic (summary)

* **Ride states:** Power Off → Stopped → Starting (6 s two-tone track clear warning) → Running →
  Controlled Stop → Stopped. E-Stop from any state, latched until released and reset.
* **Modes (keyswitch):** OFF / MAINT / MANUAL / AUTO. The track clear warning also sounds when
  entering or leaving MANUAL, and after a fault reset that clears a stop-level fault.
* **Block zoning:** one train per block. A block's exit brake (or lift crest) holds until the next
  block is clear. Occupancy is tracked from sensor events. Overrun or an unexpected train causes an
  E-Stop; a stall or valley causes a controlled stop. Tests assert one train per block every scan.
* **Dispatch:** Auto + Running, no faults, train at the stop, restraints locked, gates closed,
  load and unload operators ready, next block clear, then a two-hand press within 0.5 s (a
  single-button option exists for mouse use).
* **Alarms:** latched with first-out tracking; tap an alarm for causes and corrective actions.

## Access levels

| Level | PIN | Unlocks |
|---|---|---|
| Operator | — | Normal operation |
| Maintenance | 2580 | Brake bypass, transfer, block resync, compressor |
| Instructor | 1234 | Instructor page (rides, faults, sound), track editor |

## Dev: track editor

Instructor → OPEN TRACK EDITOR. Tap the plan view to mark stations, lifts, launches, brakes,
tunnels, transfer, block boundaries and sensors. Tags are stored per coaster and exported as JSON
(app external files dir, `tags/<id>.json`).

Tags feed the generator (`sim/TagLayout.kt`) when the ride is built (APPLY TO RIDE, power off):
* Block brake / tunnel tags in the free-running course replace the generated MCBR. The tagged span
  is levelled at a height the train can reach, and later hills are trimmed so a train leaving the
  stop off the drive tires still makes the final brake. Tags that can't work are rejected.
* Block boundaries move a block's end between its stop point and the next brake.
* Transfer tags move the transfer table to that brake run; station/lift/launch tags rename blocks.
* A generated tunnel launch is kept, and brake tags may only go before it.
* Trim brakes cap speed (passive fins), but never below what still clears the track to the next
  brake. Sensor tags become extra field inputs (`<NAME>_PX` on the I/O page).
The editor lists what happened to each tag, marks rejected ones with a red cross, and shows the
built elevation over the generated one. TAGS: USED/IGNORED switches back to the pure generator.

## Sound

Synthesised in real time and mixed: track clear warning, fault horn, dispatch chime, launch
warning, lift motor, anti-rollback clack, brake pneumatics, LSM whine, gate/restraint pneumatics.
Each sound can be switched on or off individually.
