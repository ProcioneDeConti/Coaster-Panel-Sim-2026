"""Render the anti-rollback clack exactly as ui/Sound.kt synthesises it, for listening on a PC.

Port of the RideAudio clack: per-click variation (pitch, level, decay, timing, steel modes), the independent ping
variation, the dry clack and the ping-only reverb. Keep in step with Sound.kt when the clack changes.

Usage: python -I tools/audio/render_clack.py [clicks] [rate_hz] [out.wav]   (defaults: 10 clicks, 5 Hz,
tools/audio/work/clack_test.wav)
"""
import math
import os
import random
import sys

import numpy as np
import soundfile as sf

SR = 44100
DT = 1 / SR
clicks = int(sys.argv[1]) if len(sys.argv) > 1 else 10
rate_hz = float(sys.argv[2]) if len(sys.argv) > 2 else 5.0
out_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(__file__), "work", "clack_test.wav")

MODE_F = [160, 218, 280, 355, 425, 503, 593, 705, 823, 988]
MODE_A = [.55, .5, .45, .4, .32, .26, .2, .14, .1, .07]
MODE_D = [.034, .03, .026, .022, .018, .015, .012, .01, .008, .006]
BANDS = [95.0, 238.0, 575.0, 1050.0, 6500.0, 8900.0]

rng = random.Random()


def vary(spread):
    return 1.0 + (rng.random() * 2 - 1) * spread


n_total = int((clicks / rate_hz + 0.5) * SR)
dry = np.zeros(n_total)
ping = np.zeros(n_total)
lo = [0.0] * 6
band = [0.0] * 6
lp = 0.0
ph, nxt = 1.0, 1.0  # start with a click
t_click = -1.0
cF, cD = [0.0] * 10, [0.0] * 10
cPitch = cLevel = cDecay = pLevel = pPitch = pRing = pBal = 1.0
made = 0
for i in range(n_total):
    wn = rng.uniform(-1, 1)
    if made < clicks:
        ph += rate_hz * DT
        if ph >= nxt:
            ph -= nxt
            t_click = 0.0
            made += 1
            nxt = vary(0.06)
            cPitch, cLevel, cDecay = vary(0.05), vary(0.15), vary(0.15)
            pLevel, pPitch, pRing, pBal = vary(0.3), vary(0.02), vary(0.2), vary(0.25)
            for k in range(10):
                cF[k] = MODE_F[k] * cPitch * vary(0.02)
                cD[k] = MODE_D[k] * cDecay
    if 0 <= t_click < 0.12:
        d = t_click
        burst = wn * math.exp(-d / (0.007 * cDecay))
        for k in range(6):
            fc = BANDS[k] * cPitch * (pPitch if k >= 4 else 1.0)
            damp = 0.7 if k == 0 else (0.085 / pRing if k >= 4 else 0.25)
            g = 2 * math.sin(math.pi * fc / SR)
            lo[k] += g * band[k]
            hi = burst - lo[k] - damp * band[k]
            band[k] += g * hi
        lp += (wn * math.exp(-d / (0.012 * cDecay)) - lp) * 0.007
        ring = sum(MODE_A[k] * math.sin(2 * math.pi * cF[k] * d) * math.exp(-d / cD[k]) for k in range(10))
        dry[i] = 0.12 * cLevel * (1.8 * band[0] + 0.6 * band[1] + 0.9 * band[2] + 0.9 * band[3] +
                                  0.15 * burst + 9.0 * lp + 0.05 * ring)
        ping[i] = 0.12 * cLevel * pLevel * (0.2 * band[4] + 0.1 * pBal * band[5])
        t_click += DT

# Station reverb on the ping only (same structure and settings as Sound.kt).
early = np.zeros(n_total)
for ms, gain in [(11, .22), (19, .15), (29, .1)]:
    o = int(ms * SR / 1000)
    early[o:] += gain * ping[:-o]
tail = np.zeros(n_total)
for length in [797, 863, 929, 991]:
    buf, pos, lpv = np.zeros(length), 0, 0.0
    for i in range(n_total):
        o = buf[pos]
        lpv = o * .65 + lpv * .35
        buf[pos] = ping[i] * .25 + lpv * .72
        pos = (pos + 1) % length
        tail[i] += o
for length in [389, 241]:
    buf, pos, y = np.zeros(length), 0, np.zeros(n_total)
    for i in range(n_total):
        b = buf[pos]
        y[i] = -tail[i] + b
        buf[pos] = tail[i] + b * .5
        pos = (pos + 1) % length
    tail = y

out = dry + ping + early + 0.15 * tail
os.makedirs(os.path.dirname(out_path), exist_ok=True)
sf.write(out_path, out, SR)
print(f"wrote {out_path}: {clicks} clicks at {rate_hz} Hz, peak {abs(out).max():.3f}")
