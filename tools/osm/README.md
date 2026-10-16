# OSM layout extraction

Regenerates `app/src/main/java/com/abemu/coaster/sim/RealLayouts.kt` from OpenStreetMap.

Downloads go in `tools/osm/data/` (git-ignored).

1. Download the Cedar Point area (OSM API, ~10 MB):
   `curl -o tools/osm/data/cp.osm "https://api.openstreetmap.org/api/0.6/map?bbox=-82.700,41.470,-82.665,41.495"`
2. Download Lake Erie for the plan-view scenery (Overpass; needs a User-Agent). The lake includes Sandusky Bay,
   so Cedar Point is one of its *inner* rings:
   `curl -A abemu-osm-tools -o tools/osm/data/erie.json --data-urlencode 'data=[out:json];rel(4039900);way(r:"outer");out geom;' https://overpass-api.de/api/interpreter`
   `curl -A abemu-osm-tools -o tools/osm/data/erie_inner.json --data-urlencode 'data=[out:json];rel(4039900);way(r:"inner")(41.38,-82.85,41.56,-82.50);out geom;' https://overpass-api.de/api/interpreter`
3. Run: `python -I tools/osm/extract_layouts.py tools/osm/data/cp.osm app/src/main/java/com/abemu/coaster/sim/RealLayouts.kt`

This also writes the plan-view scenery (trees, water, islands) to `app/src/main/resources/osm_scenery/<ride>.txt`
(see `scenery.py`).

Uses `roller_coaster=track` ways (sidings excluded), the ride's `roller_coaster=station`
building, and `roller_coaster:track=lift_hill` where tagged. Untagged lifts fall back to the
longest straight after the station. Map data © OpenStreetMap contributors, ODbL.
