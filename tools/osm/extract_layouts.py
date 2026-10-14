import sys, math, xml.etree.ElementTree as ET
osm, outkt = sys.argv[1], sys.argv[2]
root = ET.parse(osm).getroot()
nodes = {n.get('id'): (float(n.get('lat')), float(n.get('lon'))) for n in root.findall('node')}
lat0 = 41.48
def xy(n):
    la, lo = nodes[n]
    return (lo * 111320 * math.cos(math.radians(lat0)), -la * 110540)

ways, stations = {}, {}
for w in root.findall('way'):
    tags = {t.get('k'): t.get('v') for t in w.findall('tag')}
    nds = [nd.get('ref') for nd in w.findall('nd')]
    if tags.get('roller_coaster') == 'track' and tags.get('service') != 'siding':
        ways[w.get('id')] = (nds, tags)
    if tags.get('roller_coaster') == 'station' and tags.get('name') and tags.get('roller_coaster:station_position') != 'unload':
        stations[tags['name']] = [xy(n) for n in nds if n in nodes]

parent = {}
def find(x):
    while parent.setdefault(x, x) != x:
        parent[x] = parent[parent[x]]; x = parent[x]
    return x
for wid, (nds, _) in ways.items():
    for n in nds: parent[find('w' + wid)] = find('n' + n)
comps = {}
for wid in ways: comps.setdefault(find('w' + wid), []).append(wid)

def inside(p, poly):
    x, y = p; c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c

def chain(wids):
    segs = [list(ways[w][0]) for w in wids]
    loop = segs.pop(0)
    while segs:
        for i, sg in enumerate(segs):
            if sg[0] == loop[-1]: loop += sg[1:]; segs.pop(i); break
            if sg[-1] == loop[-1]: loop += sg[::-1][1:]; segs.pop(i); break
            if sg[-1] == loop[0]: loop = sg[:-1] + loop; segs.pop(i); break
            if sg[0] == loop[0]: loop = sg[::-1][:-1] + loop; segs.pop(i); break
        else: raise SystemExit('could not chain')
    if loop[0] == loop[-1]: return loop[:-1]
    # Ends close together: a circuit with an unmapped gap (usually through the station). Close it.
    if math.dist(xy(loop[0]), xy(loop[-1])) < 150: return loop
    return loop + loop[-2:0:-1]  # genuinely open track: out and back

def forward(loop, wids):
    pos = {n: i for i, n in enumerate(loop)}; v = 0
    for w in wids:
        nds, tags = ways[w]
        if tags.get('oneway') == 'yes' and len(nds) > 1 and nds[0] in pos and nds[1] in pos:
            v += 1 if (pos[nds[1]] - pos[nds[0]]) % len(loop) == 1 else -1
    return v >= 0

def densify(pts, step=4.0):
    out = []
    for a, b in zip(pts, pts[1:] + pts[:1]):
        d = math.dist(a, b); k = max(1, int(d // step))
        out += [(a[0] + (b[0] - a[0]) * t / k, a[1] + (b[1] - a[1]) * t / k) for t in range(k)]
    return out

rides = [('millennium', 'Millennium Force'), ('magnum', 'Magnum XL-200'), ('maverick', 'Maverick'), ('gemini', 'Gemini'),
         ('tt2', 'Top Thrill 2')]
out = []
for rid, name in rides:
    poly = stations[name]
    # track circuit with the most nodes inside (or nearest to) the station building
    def score(wids):
        ps = [xy(n) for w in wids for n in ways[w][0]]
        return (sum(inside(p, poly) for p in ps), -min(math.dist(p, q) for p in ps for q in poly))
    wids = max((w for w in comps.values() if len(w) > 1 or len(ways[w[0]][0]) > 10), key=score)
    loop = chain(wids)
    if not forward(loop, wids): loop = loop[::-1]
    # start the station run on the first pass of an out-and-back track
    pts = densify([xy(n) for n in loop])
    n = len(pts)
    ins = [inside(p, poly) for p in pts]
    if not any(ins):  # fall back to nearest point
        k = min(range(n), key=lambda i: min(math.dist(pts[i], q) for q in poly)); ins[k] = True
    # station run = longest consecutive inside run (cyclic)
    best = (0, 0)
    for i in range(n):
        if ins[i] and not ins[i - 1]:
            j = i
            while ins[j % n] and j - i < n: j += 1
            if j - i > best[1] - best[0]: best = (i, j)
    st0, st1 = best
    if st1 - st0 < 3: st0, st1 = st0 - 8, st0 + 8  # tiny overlap: assume ~60 m platform
    pts = pts[st0 % n:] + pts[:st0 % n]
    st_end = st1 - st0
    # lift: tagged lift_hill way end, else the straight run after the station
    lift_end = None
    lift_start = st_end
    tagged = False
    for w in wids:
        nds, tags = ways[w]
        if tags.get('roller_coaster:track') == 'lift_hill':
            ends = [xy(nds[0]), xy(nds[-1])]
            window = range(st_end, min(n, st_end + 120))  # within ~480 m after the station
            cand = [min(window, key=lambda i: math.dist(pts[i], e)) for e in ends]
            lift_start, lift_end = min(cand), max(cand)
            tagged = True
    if lift_end is None:
        # longest straight (all points within 3 m of a line) starting within ~120 m of the station exit
        def straight_from(i0):
            a = pts[i0]; b = pts[min(n - 1, i0 + 3)]
            dx, dy = b[0] - a[0], b[1] - a[1]; L0 = math.hypot(dx, dy) or 1
            j = i0
            while j < n - 1:
                px, py = pts[j + 1][0] - a[0], pts[j + 1][1] - a[1]
                if abs(px * dy - py * dx) / L0 > 3.0: break
                j += 1
            return j
        best_len = -1
        for i0 in range(st_end, min(n - 4, st_end + 30)):
            j = straight_from(i0)
            if j - i0 > best_len: best_len = j - i0; lift_start = i0; lift_end = j
    cum = [0.0]
    for a, b in zip(pts, pts[1:] + pts[:1]): cum.append(cum[-1] + math.dist(a, b))
    total = cum[-1]
    best = None
    for deg in range(0, 360, 3):
        r = math.radians(deg)
        rp = [(x * math.cos(r) - y * math.sin(r), x * math.sin(r) + y * math.cos(r)) for x, y in pts]
        xs = [p[0] for p in rp]; ys = [p[1] for p in rp]
        sc = min(92 / (max(xs) - min(xs)), 50 / (max(ys) - min(ys)))
        # prefer the station along the bottom of the screen
        sy = sum(rp[i][1] for i in range(st_end)) / max(1, st_end)
        key = (round(sc, 1), sy)
        if best is None or key > best[0]: best = (key, sc, rp, min(xs), min(ys), max(xs), max(ys))
    _, sc, rp, x0, y0, x1, y1 = best
    ox = (100 - (x1 - x0) * sc) / 2; oy = (56 - (y1 - y0) * sc) / 2
    step = max(1, n // 160)
    sel = list(range(0, n, step))
    for k in (st_end, lift_start, lift_end):
        if k not in sel: sel.append(k)
    sel.sort()
    pl = [(round(cum[i] / total, 5), round(ox + (rp[i][0] - x0) * sc, 2), round(oy + (rp[i][1] - y0) * sc, 2)) for i in sel]
    print(f'{rid}: {round(total)} m, station {round(cum[st_end])} m, lift {round(cum[lift_start])}-{round(cum[lift_end])} m tagged={tagged}, {len(pl)} pts')
    out.append((rid, name, total, cum[st_end] / total, cum[lift_start] / total, cum[lift_end] / total, tagged, pl))

exec(open(sys.argv[0].replace('extract_layouts.py', 'writer.py')).read())
