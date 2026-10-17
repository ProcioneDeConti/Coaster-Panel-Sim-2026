"""Per-second summary of a Coasteracc CSV: baro altitude (relative), GPS speed, accel magnitude range (g)."""
import csv, math, sys
from collections import defaultdict
rows = defaultdict(lambda: {"alt": [], "spd": [], "a": []})
t0 = None
with open(sys.argv[1], newline="") as f:
    for r in csv.DictReader(f):
        t = int(r["timestamp_ms"]); t0 = t0 or t
        b = rows[(t - t0) // 1000]
        if r["altitude_m"]: b["alt"].append(float(r["altitude_m"]))
        if r["gps_speed_mps"]: b["spd"].append(float(r["gps_speed_mps"]))
        if r["sensor_type"] == "ACCEL":
            b["a"].append(math.sqrt(sum(float(r[k]) ** 2 for k in ("accel_x", "accel_y", "accel_z"))) / 9.81)
alts = [x for b in rows.values() for x in b["alt"]]
base = min(alts) if alts else 0
for s in sorted(rows):
    b = rows[s]
    alt = "%6.0f" % ((sum(b["alt"]) / len(b["alt"]) - base) / 0.3048) if b["alt"] else "     -"
    spd = "%5.1f" % (max(b["spd"]) * 2.23694) if b["spd"] else "    -"
    a = "%4.2f-%4.2f" % (min(b["a"]), max(b["a"])) if b["a"] else "    -    "
    print(f"{s:4d}s alt {alt} ft  gps {spd} mph  |a| {a} g")
