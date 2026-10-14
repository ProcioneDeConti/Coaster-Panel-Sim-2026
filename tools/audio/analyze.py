"""Spectral summary of a reference sound clip, used to tune the synthesised ride sounds in ui/Sound.kt.

Usage: python -I tools/audio/analyze.py <file> <start s> <end s>
Prints, for each 100 ms frame: loudness and the strongest spectral peaks; then a summary of where the energy sits.
"""
import sys

import numpy as np
import soundfile as sf
from scipy.signal import find_peaks

path, t0, t1 = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
x, sr = sf.read(path, always_2d=True)
x = x.mean(axis=1)[int(t0 * sr):int(t1 * sr)]
win = int(0.1 * sr)
print(f"{path}: {t0}-{t1} s, sr={sr}")
total = np.zeros(win // 2 + 1)
freqs = np.fft.rfftfreq(win, 1 / sr)
for k in range(0, len(x) - win, win // 2):
    seg = x[k:k + win] * np.hanning(win)
    mag = np.abs(np.fft.rfft(seg))
    total += mag
    db = 20 * np.log10(mag + 1e-9)
    rms = 20 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-9)
    pk, _ = find_peaks(db, prominence=10, distance=int(20 / (sr / win)))
    top = sorted(pk, key=lambda i: -db[i])[:7]
    print(f"t={t0 + k / sr:5.2f}s  rms={rms:6.1f} dB  peaks: " +
          ", ".join(f"{freqs[i]:.0f}Hz({db[i] - db.max():+.0f})" for i in sorted(top)))
db = 20 * np.log10(total + 1e-9)
db -= db.max()
bands = [(0, 100), (100, 300), (300, 800), (800, 2000), (2000, 4000), (4000, 8000), (8000, 16000)]
print("energy by band (dB rel. strongest bin):")
for lo, hi in bands:
    m = (freqs >= lo) & (freqs < hi)
    print(f"  {lo:5d}-{hi:5d} Hz: {10 * np.log10(np.mean(10 ** (db[m] / 10))):6.1f}")
pk, _ = find_peaks(db, prominence=8, distance=5)
print("overall peaks:", ", ".join(f"{freqs[i]:.0f}Hz({db[i]:+.0f})" for i in sorted(pk, key=lambda i: -db[i])[:12]))
