"""Pre-bake the station auto-spiels with ElevenLabs.

Reads spiels/spiels.txt and writes MP3s to app/src/main/assets/spiels/:
  <slug>.mp3            spiels without [ride] (shared by every ride)
  <slug>__<ride>.mp3    spiels with [ride], one per ride given with --ride

Only spiels whose text changed since the last run (tracked in manifest.json) are generated, so re-running
costs credits only for new or edited spiels. The API key is read from local.properties (elevenlabs.apiKey)
or the ELEVENLABS_API_KEY environment variable.

Usage: python -I tools/spiels/generate.py --ride tt2 "Top Thrill 2" [--ride id "Name" ...] [--dry-run] [--force]
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPIELS = os.path.join(ROOT, "spiels", "spiels.txt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "spiels")
VOICE = "XpZeqQTuSFEd29ztEE53"  # GIFFEN - Professional North American Voice
MODEL = "eleven_multilingual_v2"


def parse(path):
    """[(label, text)] from the spiels file (same rules as the app)."""
    spiels, label, lines = [], None, []
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if line.startswith("#"):
            continue
        m = re.fullmatch(r"==\s*(.+?)\s*==", line)
        if m:
            if label:
                spiels.append((label, " ".join(lines).strip()))
            label, lines = m.group(1).split("|")[0].strip(), []  # "ID | Button title": the ID names the files
        elif label and line:
            lines.append(line)
    if label:
        spiels.append((label, " ".join(lines).strip()))
    return spiels


def slug(label):
    return re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_")


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


def synth(key, text):
    req = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{VOICE}?output_format=mp3_44100_128",
        data=json.dumps({"text": text, "model_id": MODEL}).encode(),
        headers={"xi-api-key": key, "Content-Type": "application/json", "Accept": "audio/mpeg"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"ElevenLabs error {e.code}: {e.read().decode(errors='replace')[:300]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ride", nargs=2, action="append", metavar=("ID", "NAME"), default=[],
                    help="ride to render [ride] spiels for, e.g. --ride tt2 \"Top Thrill 2\"")
    ap.add_argument("--dry-run", action="store_true", help="list what would be generated and the character cost")
    ap.add_argument("--force", action="store_true", help="regenerate even if unchanged")
    a = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    man_path = os.path.join(OUT, "manifest.json")
    manifest = json.load(open(man_path, encoding="utf-8")) if os.path.exists(man_path) else {}

    jobs = []
    for label, text in parse(SPIELS):
        if "[ride]" in text:
            for rid, name in a.ride:
                jobs.append((f"{slug(label)}__{rid}.mp3", text.replace("[ride]", name)))
        else:
            jobs.append((f"{slug(label)}.mp3", text))

    todo = []
    for fname, text in jobs:
        h = hashlib.sha256(f"{VOICE}|{MODEL}|{text}".encode()).hexdigest()
        if a.force or manifest.get(fname) != h or not os.path.exists(os.path.join(OUT, fname)):
            todo.append((fname, text, h))
    chars = sum(len(t) for _, t, _ in todo)
    print(f"{len(todo)} of {len(jobs)} spiels to generate, {chars} characters")
    if a.dry_run:
        for fname, text, _ in todo:
            print(f"  {fname}: {text[:70]}{'...' if len(text) > 70 else ''}")
        return
    key = api_key() if todo else None
    for fname, text, h in todo:
        audio = synth(key, text)
        open(os.path.join(OUT, fname), "wb").write(audio)
        manifest[fname] = h
        json.dump(manifest, open(man_path, "w", encoding="utf-8"), indent=1, sort_keys=True)
        print(f"  wrote {fname} ({len(audio) // 1024} KB)")


if __name__ == "__main__":
    main()
