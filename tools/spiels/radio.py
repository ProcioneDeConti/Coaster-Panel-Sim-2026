"""Pre-bake the ride-ops radio chatter with ElevenLabs.

Reads spiels/radio.txt and writes app/src/main/assets/radio/<clip>.wav: 16 kHz mono speech with a two-way radio
effect (band-pass, light clipping, hiss, squelch at key-up and release).

Only clips whose text or voice changed since the last run (tracked in manifest.json) are generated. The API key is
read from local.properties (elevenlabs.apiKey) or the ELEVENLABS_API_KEY environment variable.

Usage: python -I tools/spiels/radio.py [--dry-run] [--force] [--fx-only]
  --fx-only   re-apply the radio effect to the cached raw speech (no API calls, no credits)
"""
import argparse
import hashlib
import json
import math
import os
import random
import re
import struct
import sys
import urllib.error
import urllib.request
import wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCRIPT = os.path.join(ROOT, "spiels", "radio.txt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "radio")
# Raw (dry) speech is cached outside the APK so the effect can be re-tuned without spending credits.
RAW = os.path.join(ROOT, "tools", "spiels", "radio_raw")
# One voice per role on the channel; the speaker field of each script header picks it.
VOICES = {
    "SUP": "g6xIsTj2HwM6VR4iXFCw",             # Jessica Anne Bogart: ride ops supervisor
    "MAINT": "uFIXVu9mmnDZ7dTKCBTX",           # Justin Time: maintenance base
    "MAINT_UNIT": "T5cu6IU92Krx4mh43osx",      # Bill Oxley: maintenance tech in the field
    "AREA_LEAD": "cjVigY5qzO86Huf0OWal",       # Eric
    "SECURITY": "CeNX9CMwmxDxUF5Q2Inm",        # library voice picked by the user (replaced Adam, the TikTok narrator)
    "GUEST_SERVICES": "XrExE9yKIg1WjnnlVkGX",  # Matilda
    "FIRST_AID": "EXAVITQu4vr4xnSDxMaL",       # Sarah
    "FOOD_SERVICE": "iP95p4xoKVk53GoZ742B",    # Chris
    "ENTERTAINMENT": "cgSgspJ2msm6clMCkdW9",   # Jessica
    "RIDE_OPS": "bIHbv24MWmeRgasZH58o",        # Will: operators on other rides
    "GAMES": "TX3LPaxmHKxFdv7VOQHJ",           # Liam
    "CUSTODIAL": "CwhRBWXzGAHq8TQ4Fs17",       # Roger
    "OP": "SAz9YHcvj6GT2YYXdXww",              # River: you, the coaster operator
    # 2026-11 additions: Voice Library voices picked by the user, avoiding stock / TikTok-narration voices.
    "GATE": "Mac2FKpSgaGIsaNRXt8A",            # Emma: front gate / admissions
    "PARKING": "oOxgWK7hYRcDqjiZMSoX",         # Brian (Midwestern): parking & trams
    "PARK_OPS": "6fLd3vCll0LutxnciLI8",        # Tiffany: park operations / weather desk
    "OTHER_OP": "3LPKEa2TICTsITcoxL7H",        # Arthur: operators on other rides
}
MODEL = "eleven_multilingual_v2"
RATE = 16000


def parse(path):
    """[(clip, speaker, text)] from the radio script."""
    out, head, lines = [], None, []
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if line.startswith("#"):
            continue
        m = re.fullmatch(r"==\s*(\S+)\s*\|\s*(\w+)\s*(?:\|\s*([^=]+?))?\s*==", line)
        if m:
            if head:
                out.append((*head, " ".join(lines).strip()))
            head, lines = (m.group(1), m.group(2).upper()), []
        elif head and line:
            lines.append(line)
    if head:
        out.append((*head, " ".join(lines).strip()))
    return out


def api_key():
    key = os.environ.get("ELEVENLABS_API_KEY")
    if key:
        return key
    props = os.path.join(ROOT, "local.properties")
    if os.path.exists(props):
        for line in open(props, encoding="utf-8"):
            if line.startswith("elevenlabs.apiKey="):
                return line.split("=", 1)[1].strip()
    sys.exit("No ElevenLabs API key: set elevenlabs.apiKey in local.properties or ELEVENLABS_API_KEY")


def synth(key, voice, text):
    """Raw 16-bit 16 kHz mono PCM."""
    req = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=pcm_16000",
        data=json.dumps({"text": text, "model_id": MODEL}).encode(),
        headers={"xi-api-key": key, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"ElevenLabs error {e.code}: {e.read().decode(errors='replace')[:300]}")


def biquad(x, kind, f0, q=0.707):
    w = 2 * math.pi * f0 / RATE
    a = math.sin(w) / (2 * q)
    c = math.cos(w)
    if kind == "lp":
        b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
    else:
        b0, b1, b2 = (1 + c) / 2, -(1 + c), (1 + c) / 2
    a0, a1, a2 = 1 + a, -2 * c, 1 - a
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
    for s in x:
        o = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, s, y1, o
        y.append(o)
    return y


def radio_fx(pcm, seed):
    rnd = random.Random(seed)
    x = [s / 32768 for s in struct.unpack(f"<{len(pcm) // 2}h", pcm)]
    peak = max(1e-6, max(abs(s) for s in x))
    x = [s / peak * 0.8 for s in x]
    # Two-way radio band, kept fairly wide so it only hints at the radio.
    for kind, f in (("hp", 280), ("hp", 280), ("lp", 3600), ("lp", 3600)):
        x = biquad(x, kind, f)
    # Gentle saturation and a faint hiss while the carrier is up.
    x = [math.tanh(s * 1.6) * 0.82 + rnd.gauss(0, 0.004) for s in x]

    def noise(ms, level, fade):
        n = int(RATE * ms / 1000)
        out = []
        for i in range(n):
            env = (1 - i / n) ** 2 if fade else 1.0
            out.append(rnd.gauss(0, level) * env)
        return biquad(biquad(out, "hp", 600), "lp", 5000)

    key_up = [0.0] * int(RATE * 0.03) + noise(35, 0.07, False)
    tail = [0.0] * int(RATE * 0.12) + noise(140, 0.12, True)  # squelch tail when the talker un-keys
    y = key_up + x + tail
    return b"".join(struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in y)


def write_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="list what would be generated and the character cost")
    ap.add_argument("--force", action="store_true", help="regenerate even if unchanged")
    ap.add_argument("--fx-only", action="store_true", help="re-apply the radio effect to cached raw speech")
    a = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    os.makedirs(RAW, exist_ok=True)
    man_path = os.path.join(OUT, "manifest.json")
    manifest = json.load(open(man_path, encoding="utf-8")) if os.path.exists(man_path) else {}

    clips = parse(SCRIPT)
    bad = [c for c, sp, _ in clips if sp not in VOICES]
    if bad:
        sys.exit(f"Unknown speaker for {bad}; use one of {list(VOICES)}")

    if a.fx_only:
        for clip, _, _ in clips:
            raw = os.path.join(RAW, clip + ".pcm")
            if os.path.exists(raw):
                write_wav(os.path.join(OUT, clip + ".wav"), radio_fx(open(raw, "rb").read(), clip))
                print(f"  fx {clip}.wav")
        return

    todo = []
    for clip, sp, text in clips:
        h = hashlib.sha256(f"{VOICES[sp]}|{MODEL}|{text}".encode()).hexdigest()
        if a.force or manifest.get(clip) != h or not os.path.exists(os.path.join(OUT, clip + ".wav")):
            todo.append((clip, sp, text, h))
    chars = sum(len(t) for _, _, t, _ in todo)
    print(f"{len(todo)} of {len(clips)} radio clips to generate, {chars} characters")
    if a.dry_run:
        for clip, sp, text, _ in todo:
            print(f"  {clip} [{sp}]: {text[:70]}{'...' if len(text) > 70 else ''}")
        return
    key = api_key() if todo else None
    for clip, sp, text, h in todo:
        pcm = synth(key, VOICES[sp], text)
        open(os.path.join(RAW, clip + ".pcm"), "wb").write(pcm)
        write_wav(os.path.join(OUT, clip + ".wav"), radio_fx(pcm, clip))
        manifest[clip] = h
        json.dump(manifest, open(man_path, "w", encoding="utf-8"), indent=1, sort_keys=True)
        print(f"  wrote {clip}.wav ({sp})")


if __name__ == "__main__":
    main()
