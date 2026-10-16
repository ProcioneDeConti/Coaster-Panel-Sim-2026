# Scenery for the plan view, exec'd by extract_layouts.py (shares its root / nodes / xy / ways / inside).
# Output: one text file per ride in app/src/main/resources/osm_scenery/, in plan-box coordinates (100 x 56).
#   T x y                 tree
#   W x y x y ...         water polygon        L ...  land (island) polygon
#   E ...  Lake Erie polygon    K ...  land inside the lake (drawn before W / L)
import json, os

def tagmap(e): return {t.get('k'): t.get('v') for t in e.findall('tag')}
wayn, waytags = {}, {}
for w in root.findall('way'):
    wayn[w.get('id')] = [nd.get('ref') for nd in w.findall('nd')]
    waytags[w.get('id')] = tagmap(w)
def closed(nds): return len(nds) > 3 and nds[0] == nds[-1] and all(n in nodes for n in nds)
def full(nds): return [xy(n) for n in nds if n in nodes]

trees = [xy(n.get('id')) for n in root.findall('node') if tagmap(n).get('natural') == 'tree']

def rings(segs, pt=None):
    """Stitch way node lists into closed rings; rings left open by missing members are dropped."""
    pt = pt or (lambda n: xy(n) if n in nodes else None)
    while segs:
        ring = segs.pop(0)
        while ring[0] != ring[-1]:
            nxt = next((g for g in segs if ring[-1] in (g[0], g[-1])), None)
            if nxt is None: break
            segs.remove(nxt); ring += (nxt if nxt[0] == ring[-1] else nxt[::-1])[1:]
        if ring[0] == ring[-1]:
            ps = [pt(n) for n in ring[:-1]]
            if all(ps): yield ps

def is_water(t): return t.get('natural') == 'water' or t.get('water') is not None
waters, lands = [], []
lake, lake_land = [], []  # Lake Erie and the land inside it, drawn under the park's own water
for wid, nds in wayn.items():
    t = waytags[wid]
    if closed(nds):
        if is_water(t): waters.append(full(nds[:-1]))
        if t.get('place') in ('island', 'islet'): lands.append(full(nds[:-1]))
for rel in root.findall('relation'):
    t = tagmap(rel)
    if t.get('type') != 'multipolygon' or not is_water(t) or t.get('name') == 'Lake Erie': continue
    for role, dst in (('outer', waters), ('inner', lands)):
        dst += rings([list(wayn[m.get('ref')]) for m in rel.findall('member')
                      if m.get('type') == 'way' and m.get('role') == role and m.get('ref') in wayn])

# Lake Erie: the whole outer shoreline (Overpass: rel(4039900); way(r:"outer"); out geom;), stitched into one ring.
erie_file = os.path.join(os.path.dirname(sys.argv[1]), 'erie.json')
if os.path.exists(erie_file):
    geo = {}
    segs = []
    for el in json.load(open(erie_file, encoding='utf-8'))['elements']:
        for n, g in zip(el['nodes'], el['geometry']): geo[n] = (g['lat'], g['lon'])
        segs.append(list(el['nodes']))
    def ept(n):
        la, lo = geo[n]
        return (lo * 111320 * math.cos(math.radians(lat0)), -la * 110540)
    erie = max(rings(segs, ept), key=len, default=None)
    if erie: lake.append(erie)
    # Inner rings: land inside the lake polygon (Cedar Point's peninsula is one; the lake includes Sandusky Bay).
    # Overpass: rel(4039900); way(r:"inner")(41.38,-82.85,41.56,-82.50); out geom;
    inner_file = erie_file.replace('erie.json', 'erie_inner.json')
    segs = []
    for el in json.load(open(inner_file, encoding='utf-8'))['elements']:
        for n, g in zip(el['nodes'], el['geometry']): geo[n] = (g['lat'], g['lon'])
        segs.append(list(el['nodes']))
    n0 = len(segs)
    found = list(rings(segs, ept))
    lake_land += found
    print(f'Lake Erie: outer ring {len(erie or [])} pts, {len(found)} islands from {n0} inner ways')
else:
    print('no erie.json: Lake Erie skipped')

def area(ps): return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ps, ps[1:] + ps[:1]))) / 2

R = (-30.0, -20.0, 130.0, 76.0)  # clip rectangle around the 100 x 56 plan box

def clip_poly(ps):
    """Sutherland-Hodgman against R: correct fill for concave shapes (any bridges lie on R, off screen)."""
    for edge in range(4):
        if not ps: return ps
        def ins(p):
            return (p[0] >= R[0], p[1] >= R[1], p[0] <= R[2], p[1] <= R[3])[edge]
        def cut(a, b):
            if edge in (0, 2):
                x = R[edge]; t = (x - a[0]) / (b[0] - a[0]); return (x, a[1] + (b[1] - a[1]) * t)
            y = R[edge]; t = (y - a[1]) / (b[1] - a[1]); return (a[0] + (b[0] - a[0]) * t, y)
        out = []
        for a, b in zip(ps, ps[1:] + ps[:1]):
            if ins(b):
                if not ins(a): out.append(cut(a, b))
                out.append(b)
            elif ins(a): out.append(cut(a, b))
        ps = out
    return ps

def simplify(ps, tol):
    out = [ps[0]]
    for p in ps[1:]:
        if math.dist(p, out[-1]) > tol: out.append(p)
    return out

def plan_scenery(proj, track, max_trees=88):
    near = lambda p, d: any(math.dist(p, q) < d for q in track)
    ts = [p for p in map(proj, trees) if -25 < p[0] < 125 and -12 < p[1] < 68 and not near(p, 2.2)]
    ts.sort(key=lambda p: (p[0] * 7919 + p[1] * 104729) % 1)  # stable pseudo-random thinning
    kept = []
    for p in ts:
        if len(kept) >= max_trees: break
        if all(math.dist(p, q) > 2.5 for q in kept): kept.append(p)
    def polys(src, tol, min_area=0.0):
        out = []
        for poly in src:
            pp = clip_poly([proj(q) for q in poly])
            if len(pp) >= 3:
                pp = simplify(pp, tol)
                if len(pp) >= 3 and area(pp) >= min_area: out.append(pp)
        return out
    return dict(T=[kept], E=polys(lake, 0.5), K=polys(lake_land, 0.5), 
                W=polys(waters, 0.5), L=polys(lands, 0.5))

def write_scenery(scenery):
    d = os.path.join(os.path.dirname(outkt).split(os.sep + 'java' + os.sep)[0].split('/java/')[0], 'resources', 'osm_scenery')
    os.makedirs(d, exist_ok=True)
    f2 = lambda v: f'{v:.2f}'.rstrip('0').rstrip('.')
    for rid, sc in scenery:
        out = ['# GENERATED by tools/osm/extract_layouts.py. Map data (c) OpenStreetMap contributors, ODbL.']
        out += [f'T {f2(x)} {f2(y)}' for x, y in sc['T'][0]]
        for k in 'EKWL':
            out += [k + ' ' + ' '.join(f'{f2(x)} {f2(y)}' for x, y in ps) for ps in sc[k]]
        open(os.path.join(d, rid + '.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
        print(f'{rid}: ' + ', '.join(f'{k}={len(sc[k]) if k != "T" else len(sc[k][0])}' for k in 'TEKWL')
              + f', {os.path.getsize(os.path.join(d, rid + ".txt")) // 1024} KB')
