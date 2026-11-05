#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Walk a demo beat by beat on the emulator and write an HTML inventory: both displays per beat and
every visible string, so wording and polish can be reviewed before recording.

Usage: inventory-demo-screens.py --story met|charge|all --out DIR [--driver-id ID --passenger-id ID]
"""
from __future__ import annotations

import argparse
import html
import pathlib
import re
import subprocess
import time

PKG = "art.dart.a2ui.carui"
CARUI = "art.dart.a2ui.carui.action."
CASE, CASE_ID = "art.dart.a2ui.carapp.action.CASE", "art.dart.a2ui.carapp.extra.CASE_ID"
X = "art.dart.a2ui.carui.extra."
DRIVER_ID, PASSENGER_ID = "4619827259835644672", "4619827551948147201"


def adb(*args: str, check: bool = False) -> str:
    done = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=60)
    if check and done.returncode:
        raise SystemExit(f"adb {' '.join(args)} failed: {done.stderr.strip()}")
    return done.stdout


def broadcast(action: str, *extras: str) -> None:
    adb("shell", "am", "broadcast", "--user", "current", "-a", action, "-p", PKG, *extras)


def car(state: str) -> None:
    adb("shell", "cmd", "car_service", "emulate-driving-state", state)


def speak(phrase: str) -> None:
    broadcast(CARUI + "SPEAK", "--es", X + "PHRASE", f"'{phrase}'")


def battery(pct: int) -> None:
    broadcast(CARUI + "VEHICLE", "--ei", X + "BATTERY_PCT", str(pct))


def tap_text(label: str) -> bool:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    dump = adb("shell", "cat", "/sdcard/ui.xml")
    m = re.search(rf'text="{re.escape(label)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def visible_text() -> list[str]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    dump = adb("shell", "cat", "/sdcard/ui.xml")
    seen = []
    for t in re.findall(r'(?:text|content-desc)="([^"]+)"', dump):
        t = html.unescape(t).strip()
        if t and t not in seen and not t.startswith("com.android."):
            seen.append(t)
    return seen


def shot(out: pathlib.Path, name: str, driver: str, passenger: str) -> str:
    files = []
    for tag, pid in (("d", driver), ("p", passenger)):
        adb("shell", "screencap", "-p", "-d", pid, "/sdcard/inv.png")
        local = out / f"{name}-{tag}.png"
        adb("pull", "/sdcard/inv.png", str(local))
        files.append(local)
    combo = out / f"{name}.jpg"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(files[0]), "-i", str(files[1]),
                    "-filter_complex", "[0]scale=-2:600[a];[1]scale=-2:600[b];[a][b]hstack",
                    "-q:v", "4", str(combo)], check=True)
    for f in files:
        f.unlink()
    return combo.name


MET = [
    ("Parked · brief", lambda: (car("park"), broadcast(CASE, "--es", CASE_ID, "met_visit"))),
    ("Muse brief (live or cached)", lambda: broadcast(
        CARUI + "AGENT", "--es", X + "PROVIDER", "muse", "--es", X + "ASK",
        "'Take us to The Met this afternoon'")),
    ("Reserve · confirm", lambda: speak("reserve entry for two at three")),
    ("Ticket", lambda: (tap_text("Confirm") or tap_text("OK"))),
    ("Drive · change strip", lambda: car("drive")),
    ("Drive · audio guide + passenger film", lambda: broadcast(
        CARUI + "PASSENGER", "--es", X + "MEDIA", "artwork")),
    ("Arrive · park", lambda: car("park")),
    ("Parked · artwork", lambda: tap_text("Film · Slideshow")),
]
CHARGE = [
    ("Drive away", lambda: (broadcast(CASE, "--es", CASE_ID, "ev_charge_coffee"), car("drive"))),
    ("Low battery · chargers", lambda: battery(18)),
    ("Voice · first charger", lambda: speak("take me to the first one")),
    ("Park · charging", lambda: car("park")),
    ("Order coffee · confirm", lambda: speak("order my usual coffee")),
    ("Pickup card", lambda: (tap_text("Confirm") or tap_text("OK"))),
    ("Drive off · strip", lambda: car("drive")),
    ("Parked · Big Buck Bunny", lambda: (car("park"), time.sleep(2), tap_text("Big Buck Bunny"))),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--story", choices=("met", "charge", "all"), default="all")
    ap.add_argument("--out", type=pathlib.Path, required=True)
    ap.add_argument("--driver-id", default=DRIVER_ID)
    ap.add_argument("--passenger-id", default=PASSENGER_ID)
    ap.add_argument("--settle", type=float, default=3.0)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", "--user", "current", PKG)
    adb("shell", "am", "start", "--user", "current", "-n", f"{PKG}/.A2uiCarUiActivity", check=True)
    adb("shell", "am", "start", "--user", "current", "--display", "2", "-n",
        f"{PKG}/.A2uiPassengerActivity")
    time.sleep(4)
    steps = (MET if args.story in ("met", "all") else []) + (
        CHARGE if args.story in ("charge", "all") else [])
    rows = []
    for i, (label, act) in enumerate(steps, 1):
        act()
        time.sleep(args.settle)
        image = shot(args.out, f"{i:02d}", args.driver_id, args.passenger_id)
        rows.append((i, label, image, visible_text()))
        print(f"{i:02d} {label}")
    page = ["<!doctype html><meta charset=utf-8><title>Demo screen inventory</title>",
            "<style>body{font:14px system-ui;background:#111;color:#eee;margin:24px}"
            "section{margin:0 0 32px}img{max-width:100%;border:1px solid #333}"
            "li{color:#bbb}</style><h1>Demo screen inventory</h1>"]
    for i, label, image, texts in rows:
        page.append(f"<section><h2>{i:02d} · {html.escape(label)}</h2><img src='{image}'>"
                    "<ul>" + "".join(f"<li>{html.escape(t)}</li>" for t in texts) + "</ul></section>")
    (args.out / "index.html").write_text("\n".join(page), encoding="utf-8")
    print(f"wrote {args.out / 'index.html'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
