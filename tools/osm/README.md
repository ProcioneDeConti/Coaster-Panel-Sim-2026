# OSM layout extraction

Regenerates `app/src/main/java/com/abemu/coaster/sim/RealLayouts.kt` from OpenStreetMap.

1. Download the Cedar Point area (OSM API, ~10 MB):
   `curl -o cp.osm "https://api.openstreetmap.org/api/0.6/map?bbox=-82.700,41.470,-82.665,41.495"`
2. Run: `python -I tools/osm/extract_layouts.py cp.osm app/src/main/java/com/abemu/coaster/sim/RealLayouts.kt`

Uses `roller_coaster=track` ways (sidings excluded), the ride's `roller_coaster=station`
building, and `roller_coaster:track=lift_hill` where tagged. Untagged lifts fall back to the
longest straight after the station. Map data © OpenStreetMap contributors, ODbL.
