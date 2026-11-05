#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Run and record one timed NYC Host B scenario on one AAOS display, or two side by side.

A scenario whose manifest entry says "recording": {"layout": "dual"} records the driver and the
passenger physical displays with two screenrecord streams and composes them on a 1920x1080 canvas
(driver larger, left; passenger right; each labelled). Everything else records one display, as
before. Either may open on a title card and hold its end card.

Post-production, all built from the same recording so it always matches the UI:
  preamble    problem cards rendered around frames of the raw driver recording, prepended;
  outro       a closing card (a one-line note, the journey with real thumbnails, the architecture)
              shown after the timeline over its held last frame;
  disclaimer  one small line in the canvas margin for the whole video.
durationSeconds is the device timeline; the output is preamble + timeline + outro (60–180 s).

A dual recording is composed in a cockpit (scripts/demo_overlays.py; --no-cockpit restores the
side-by-side canvas): one still frame for the whole video with both screens in its windows. It
opens on the Muse2CarUI graffiti on the screens, pushing in, which crossfades into what the
screens show next while the preamble plays in the driver window; captions and the story rail
are pre-rendered overlays, and the SRT is written next to the video.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import math
import pathlib
import re
import shlex
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
AUDIO_DIR = ROOT / "a2ui-automotive/demo/audio/elevenlabs"
CARDS = ROOT / "a2ui-automotive/demo/cards"
PACKAGE = "art.dart.a2ui.carui"
ACTIVITY = "art.dart.a2ui.carui.A2uiCarUiActivity"
PASSENGER_ACTIVITY = "art.dart.a2ui.carui.A2uiPassengerActivity"
ACTION_SET = "art.dart.a2ui.demo.action.SET"
EXTRA_ACTIVE = "art.dart.a2ui.demo.extra.ACTIVE"
ACTION_CASE = "art.dart.a2ui.carapp.action.CASE"
EXTRA_CASE = "art.dart.a2ui.carapp.extra.CASE_ID"
ACTION_SPEAK = "art.dart.a2ui.carui.action.SPEAK"
EXTRA_PHRASE = "art.dart.a2ui.carui.extra.PHRASE"
ACTION_AGENT = "art.dart.a2ui.carui.action.AGENT"
EXTRA_PROVIDER = "art.dart.a2ui.carui.extra.PROVIDER"
EXTRA_ASK = "art.dart.a2ui.carui.extra.ASK"
ACTION_PASSENGER = "art.dart.a2ui.carui.action.PASSENGER"
EXTRA_MEDIA = "art.dart.a2ui.carui.extra.MEDIA"
ACTION_END_CARD = "art.dart.a2ui.carui.action.END_CARD"
ACTION_VEHICLE = "art.dart.a2ui.carui.action.VEHICLE"
EXTRA_BATTERY_PCT = "art.dart.a2ui.carui.extra.BATTERY_PCT"
VHAL_SERVICE = "android.hardware.automotive.vehicle.IVehicle/default"
# VehicleProperty ids (hardware/interfaces/automotive/vehicle, VehicleProperty.aidl): FLOAT, Wh.
EV_BATTERY_LEVEL = 0x11600309
INFO_EV_BATTERY_CAPACITY = 0x11600106
DEVICE_VIDEO = "/sdcard/a2ui-nyc-demo.mp4"
DEVICE_PASSENGER_VIDEO = "/sdcard/a2ui-nyc-demo-passenger.mp4"
SCREENRECORD_MAX_S = 180
MIN_S, MAX_S = 60, 180
FPS = 30
# Layout, in one place (overlay work elsewhere overrides these): the side-by-side canvas, the
# driver (16:9, left) and passenger (2:3, right) panels at the same height, their labels just
# above, and the disclaimer's bottom-left anchor in the margin below both panels.
CANVAS = (1920, 1080)
DRIVER_BOX = (24, 186, 1344, 756)  # 16:9 driver and 2:3 passenger, same height
PASSENGER_BOX = (1392, 186, 504, 756)
LABEL_GAP = 12
BACKGROUND = "0x0b0f14"
LABELS = {"driver": CARDS / "label-driver.png", "passenger": CARDS / "label-passenger.png"}
DISCLAIMER_POS = (24, 18)  # x from the left edge, margin above the bottom edge
TITLE_FADE_S = 0.4  # a chapter card's default fade out (the opening card)
CHAPTER_SECONDS = (2.0, 3.5)  # every chapter card holds this long, fades included
PREAMBLE_FADE_S = 0.25  # each preamble card dips to the background; the story fades in after
OUTRO_FADE_S = 0.5
STILL_LEAD_MS = 3000  # capture the idle passenger frame this long before its stream starts
CARD_RENDERER = ROOT / "scripts/render-demo-cards.py"
OVERLAY_RENDERER = ROOT / "scripts/demo_overlays.py"
# The cockpit intro: graffiti on both screens for INTRO_HOLD_S, pushing in by INTRO_ZOOM, then an
# eased INTRO_FADE_S crossfade into the screens' next content as the preamble begins under it.
INTRO_HOLD_S, INTRO_FADE_S, INTRO_ZOOM = 2.0, 1.1, 0.025


def _writer_normalization():
    """The function prepare-elevenlabs-demo-audio.py writes transcriptNormalized with."""
    spec = importlib.util.spec_from_file_location(
        "prepare_elevenlabs_demo_audio",
        pathlib.Path(__file__).resolve().with_name("prepare-elevenlabs-demo-audio.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.normalized_transcript


normalized_transcript = _writer_normalization()


class DemoFailure(RuntimeError):
    pass


def run(command: list[str], *, capture: bool = True, check: bool = True,
        timeout: int = 60) -> subprocess.CompletedProcess[str]:
    # No child reads the terminal: an ffmpeg waiting on stdin stalls a backgrounded recording.
    result = subprocess.run(command, text=True, capture_output=capture, timeout=timeout,
                            stdin=subprocess.DEVNULL)
    if check and result.returncode:
        summary = (result.stderr or result.stdout or "no diagnostic").strip().splitlines()[-1]
        raise DemoFailure(f"command failed ({' '.join(command[:3])}): {summary}")
    return result


def adb(*args: str, capture: bool = True, check: bool = True,
        timeout: int = 60) -> subprocess.CompletedProcess[str]:
    return run(["adb", *args], capture=capture, check=check, timeout=timeout)


def adb_shell(*args: str, capture: bool = True, check: bool = True,
              timeout: int = 60) -> subprocess.CompletedProcess[str]:
    """Run one safely quoted command through adb's remote shell."""
    command = " ".join(shlex.quote(arg) for arg in args)
    return adb("shell", command, capture=capture, check=check, timeout=timeout)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def broadcast(action: str, *extras: str) -> None:
    adb_shell("am", "broadcast", "--user", "current", "-a", action, *extras)


def set_car_state(state: str) -> None:
    if state not in {"park", "drive"}:
        raise DemoFailure(f"unsupported car state {state}")
    result = adb("shell", "cmd", "car_service", "emulate-driving-state", state,
                 check=False, timeout=30)
    combined = (result.stdout + result.stderr).lower()
    if result.returncode or "error" in combined or "exception" in combined:
        raise DemoFailure(f"car_service could not enter {state}; refusing to substitute DEMO UXR")


def launch(passenger_launch_display: str | None = None) -> None:
    # Reset retained Activity state so every recording begins on the same base surface. Selecting
    # the scenario happens only after screenrecord starts; otherwise Android may use the first
    # interaction 10–15 seconds later as PTS zero and silently truncate the narration timeline.
    adb_shell("am", "force-stop", "--user", "current", PACKAGE)
    adb("shell", "am", "start", "--user", "current", "-n", f"{PACKAGE}/{ACTIVITY}")
    if passenger_launch_display:  # force-stop took the passenger surface down too
        adb("shell", "am", "start", "--user", "current", "--display", passenger_launch_display,
            "-n", f"{PACKAGE}/{PASSENGER_ACTIVITY}")
    time.sleep(3)
    # Clear an old synthetic override while the base surface is visible.
    broadcast(ACTION_SET, "--ei", EXTRA_ACTIVE, "-1")
    time.sleep(1)


def transcript_for(audio_dir: pathlib.Path, clip_id: str, expected: str,
                   allow_scripted: bool) -> tuple[str, pathlib.Path | None]:
    path = audio_dir / "transcripts" / f"{clip_id}.json"
    if not path.exists():
        if allow_scripted:
            return expected, None
        raise DemoFailure(f"missing cached STT fixture {path}; run prepare-elevenlabs-demo-audio.py")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("transcriptNormalized") != normalized_transcript(expected):
        raise DemoFailure(f"cached STT fixture {clip_id} no longer matches the scenario")
    if data.get("dispatchText") != expected:
        raise DemoFailure(f"cached STT fixture {clip_id} is not bound to the displayed phrase")
    return str(data["dispatchText"]), path


def tap_text(text: str) -> None:
    remote = "/sdcard/a2ui-window.xml"
    for attempt in range(5):
        adb("shell", "uiautomator", "dump", remote, timeout=30)
        xml = adb("exec-out", "cat", remote).stdout
        adb("shell", "rm", "-f", remote, check=False)
        try:
            root = ET.fromstring(xml)
        except ET.ParseError as error:
            raise DemoFailure(f"could not parse accessibility tree: {error}") from None
        candidates = []
        right_edge = bottom_edge = 0
        for node in root.iter("node"):
            match = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]",
                                 node.attrib.get("bounds", ""))
            if not match:
                continue
            left, top, right, bottom = map(int, match.groups())
            right_edge, bottom_edge = max(right_edge, right), max(bottom_edge, bottom)
            if (node.attrib.get("text") == text or node.attrib.get("content-desc") == text) \
                    and node.attrib.get("enabled", "true") == "true":
                candidates.append(((right - left) * (bottom - top), (left + right) // 2,
                                   (top + bottom) // 2))
        if candidates:
            _, x, y = min(candidates)
            adb("shell", "input", "tap", str(x), str(y))
            return
        if attempt < 4 and right_edge and bottom_edge:
            # Semantic target, geometry-derived scroll: no fixed tap coordinate and no assumption
            # that the row is initially inside the 1408p viewport.
            adb("shell", "input", "swipe", str(right_edge * 3 // 4),
                str(bottom_edge * 4 // 5), str(right_edge * 3 // 4),
                str(bottom_edge * 2 // 5), "450")
            time.sleep(1)
    raise DemoFailure(f"no enabled visible node has exact text {text!r} after four scrolls")


def verify_preconditions(required_avd: str) -> str:
    lines = [line for line in adb("devices").stdout.splitlines()[1:] if "\tdevice" in line]
    if len(lines) != 1:
        raise DemoFailure(f"expected exactly one device, found {len(lines)}")
    avd_output = adb("emu", "avd", "name", check=False).stdout.splitlines()
    avd_name = next((line.strip() for line in avd_output
                     if line.strip() and line.strip() != "OK"), "unknown")
    if required_avd and avd_name != required_avd:
        raise DemoFailure(f"expected AVD {required_avd}, connected device is {avd_name}")
    packages = adb("shell", "pm", "list", "packages", "--user", "current").stdout
    if f"package:{PACKAGE}" not in packages:
        raise DemoFailure(f"{PACKAGE} is not installed for the current AAOS user")
    flags = adb("shell", "dumpsys", "package", PACKAGE).stdout
    if "DEBUGGABLE" not in flags:
        raise DemoFailure("Host B is not debuggable; its test-only controls are unavailable")
    return avd_name


def ok_output(result: subprocess.CompletedProcess[str]) -> bool:
    text = (result.stdout + result.stderr).lower()
    return not result.returncode and not any(
        word in text for word in ("error", "exception", "unknown", "invalid", "usage"))


def property_float(text: str) -> float | None:
    """The first float in `cmd car_service get-property-value` output, which prints the HAL
    value (`floatValues: [150000.0]`, or `mValue=150000.0` in older formats)."""
    match = re.search(r"(?:floatValues|mValue|value)\s*[=:]\s*\[?\s*"
                      r"(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)", text)
    return float(match.group(1)) if match else None


class Adb:
    """The device side of run_timeline(); tests substitute a recording fake."""

    def broadcast(self, action: str, *extras: str) -> None:
        broadcast(action, *extras)

    def tap_text(self, text: str) -> None:
        tap_text(text)

    def set_car_state(self, state: str) -> None:
        set_car_state(state)

    def key_back(self) -> None:
        adb("shell", "input", "keyevent", "4")

    def start_recording(self, display_id: str, remote: str, limit: int) -> subprocess.Popen:
        return subprocess.Popen(["adb", "shell", "screenrecord", "--display-id", display_id,
                                 "--time-limit", str(limit), remote],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)

    def require_alive(self, proc: subprocess.Popen, what: str) -> None:
        if proc.poll() is not None:
            diagnostic = proc.stderr.read().strip() if proc.stderr else ""
            raise DemoFailure(f"{what} screenrecord did not start: {diagnostic}")

    def alive(self, proc: subprocess.Popen) -> bool:
        return proc.poll() is None

    def stop_recordings(self, procs: list[subprocess.Popen]) -> None:
        adb("shell", "pkill", "-INT", "screenrecord", check=False)  # SIGINT writes the moov atom
        for proc in procs:
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.send_signal(signal.SIGINT)
                proc.wait(timeout=10)

    def screencap(self, display_id: str, path: pathlib.Path) -> bool:
        result = subprocess.run(["adb", "exec-out", "screencap", "-d", display_id, "-p"],
                                capture_output=True, timeout=30)
        if result.returncode or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            return False
        # exec-out also carries the device's stderr (e.g. a FORTIFY warning) after IEND, which
        # makes ffmpeg's looped PNG decode spin forever; keep only the image.
        end = result.stdout.find(b"IEND")
        path.write_bytes(result.stdout[:end + 8] if end >= 0 else result.stdout)
        return True

    def shell(self, *args: str) -> subprocess.CompletedProcess[str]:
        return adb_shell(*args, check=False, timeout=30)


class Clock:
    def now(self) -> float:
        return time.monotonic()

    def sleep(self, seconds: float) -> None:
        time.sleep(seconds)


@dataclass(frozen=True)
class Step:
    at_ms: int
    kind: str
    action: dict


def layout_of(scenario: dict) -> str:
    layout = scenario.get("recording", {}).get("layout", "single")
    if layout not in {"single", "dual"}:
        raise DemoFailure(f"{scenario['id']} has unknown recording layout {layout!r}")
    return layout


def schedule(scenario: dict, dual: bool) -> list[Step]:
    """The scenario's actions in time order, plus the passenger stream's two internal steps.

    A passenger display is idle until its first passenger action, and screenrecord takes its PTS
    zero from the first frame that changes; so that stream starts just before that action, and
    an idle frame captured a few seconds earlier stands in for it until then.
    """
    actions = sorted(scenario["actions"], key=lambda action: int(action["atMs"]))
    steps: list[Step] = []
    first = None
    for action in actions:
        if dual and first is None and action["type"] == "passenger":
            first = action  # the stream starts immediately before this broadcast, not earlier
            steps.append(Step(int(action["atMs"]), "passengerRecord", {}))
        steps.append(Step(int(action["atMs"]), action["type"], action))
    if dual:
        if first is None:
            raise DemoFailure(f"{scenario['id']} records two displays but never addresses the "
                              "passenger display")
        still = Step(max(0, int(first["atMs"]) - STILL_LEAD_MS), "passengerStill", {})
        steps.insert(next(i for i, step in enumerate(steps) if step.at_ms >= still.at_ms), still)
    return steps


def screenrecord_limit(seconds: float) -> int:
    return max(1, min(SCREENRECORD_MAX_S, math.ceil(seconds) + 5))


def vehicle_signal(device, action: dict) -> dict:
    """Inject a battery level by every route the action names; succeed if one route does.

    vhal-set    dumpsys <VHAL> --set: the emulator's fake VHAL stores the value, so reads and the
                continuous refresh both report it (EV_BATTERY_LEVEL is CONTINUOUS; an injected
                event alone is overwritten by the next refresh).
    vhal-inject cmd car_service inject-vhal-event: one event to subscribers; reads are unchanged.
    broadcast   Host B's debug VEHICLE broadcast, for a host that does not read the property.
    """
    if action.get("signal") != "battery":
        raise DemoFailure(f"unsupported vehicle signal {action.get('signal')!r}")
    percent = float(action["percent"])

    def read(prop: int) -> float | None:
        result = device.shell("cmd", "car_service", "get-property-value", hex(prop), "0")
        return property_float(result.stdout) if ok_output(result) else None

    capacity, source = read(INFO_EV_BATTERY_CAPACITY), "INFO_EV_BATTERY_CAPACITY"
    if not capacity or capacity <= 0:
        capacity, source = float(action.get("capacityWhFallback", 150000)), "fallback"
    level = round(capacity * percent / 100, 1)
    via = action.get("via", ["vhal-set", "vhal-inject", "broadcast"])
    previous = read(EV_BATTERY_LEVEL) if "vhal-set" in via else None
    routes = []
    for route in via:
        if route == "vhal-set":
            result = device.shell("dumpsys", VHAL_SERVICE, "--set", hex(EV_BATTERY_LEVEL),
                                  "-f", f"{level:g}")
        elif route == "vhal-inject":
            result = device.shell("cmd", "car_service", "inject-vhal-event",
                                  hex(EV_BATTERY_LEVEL), f"{level:g}")
        elif route == "broadcast":
            device.broadcast(ACTION_VEHICLE, "--ei", EXTRA_BATTERY_PCT, str(round(percent)))
            result = subprocess.CompletedProcess([], 0, "", "")
        else:
            raise DemoFailure(f"unknown vehicle signal route {route!r}")
        routes.append({"via": route, "ok": ok_output(result),
                       "output": (result.stdout + result.stderr).strip()[-200:]})
    if not any(route["ok"] for route in routes):
        raise DemoFailure(f"no route injected the battery level: {routes}")
    return {"signal": "battery", "percent": percent, "levelWh": level, "capacityWh": capacity,
            "capacitySource": source, "previousWh": previous, "routes": routes}


def restore_vehicle(device, signals: list[dict]) -> None:
    """Put back the stored battery level the first vhal-set replaced, and hand Host B back to
    the vehicle's reading after a broadcast override (best effort)."""
    stored = [s for s in signals if s.get("previousWh") is not None
              and any(r["via"] == "vhal-set" and r["ok"] for r in s["routes"])]
    if stored:
        device.shell("dumpsys", VHAL_SERVICE, "--set", hex(EV_BATTERY_LEVEL),
                     "-f", f"{stored[0]['previousWh']:g}")
    if any(r["via"] == "broadcast" for s in signals for r in s["routes"]):
        device.broadcast(ACTION_VEHICLE, "--ei", EXTRA_BATTERY_PCT, "-1")


def run_timeline(scenario: dict, device, clock, *, driver_display: str,
                 passenger_display: str | None, audio_dir: pathlib.Path, allow_scripted: bool,
                 still_path: pathlib.Path) -> dict:
    """Start the recorder(s), perform every step on time, stop, and return the evidence."""
    dual = passenger_display is not None
    steps = schedule(scenario, dual)
    clips = {clip["id"]: clip for clip in scenario["clips"]}
    duration = int(scenario["durationSeconds"])
    evidence: dict = {"transcripts": [], "vehicleSignals": [], "steps": [],
                      "passengerStartSeconds": None, "passengerStill": None}
    recorders = [("driver", device.start_recording(driver_display, DEVICE_VIDEO,
                                                   screenrecord_limit(duration)), 0.0,
                  screenrecord_limit(duration))]
    started = clock.now()
    early: list[str] = []
    try:
        # The first deliberate content mutation anchors PTS zero near the manifest's zero.
        device.broadcast(ACTION_CASE, "--es", EXTRA_CASE, scenario["corpusCase"])
        clock.sleep(2)
        device.require_alive(recorders[0][1], "driver")
        for step in steps:
            clock.sleep(max(0, started + step.at_ms / 1000 - clock.now()))
            elapsed = clock.now() - started
            action = step.action
            if step.kind == "passengerStill":
                if device.screencap(passenger_display, still_path):
                    evidence["passengerStill"] = still_path
            elif step.kind == "passengerRecord":
                limit = screenrecord_limit(duration - elapsed)
                proc = device.start_recording(passenger_display, DEVICE_PASSENGER_VIDEO, limit)
                evidence["passengerStartSeconds"] = clock.now() - started
                recorders.append(("passenger", proc, clock.now() - started, limit))
            elif step.kind == "speak":
                clip = clips[action["clipId"]]
                phrase, transcript_path = transcript_for(
                    audio_dir, action["clipId"], clip["expectedTranscript"], allow_scripted)
                device.broadcast(ACTION_SPEAK, "--es", EXTRA_PHRASE, phrase)
                evidence["transcripts"].append({
                    "clipId": action["clipId"],
                    "source": "elevenlabs-stt-cache" if transcript_path else "scripted-preflight",
                    "sha256": sha256(transcript_path) if transcript_path else None,
                })
            elif step.kind == "tapText":
                device.tap_text(action["text"])
            elif step.kind == "carState":
                device.set_car_state(action["state"])
            elif step.kind == "selectCase":
                device.broadcast(ACTION_CASE, "--es", EXTRA_CASE, action["caseId"])
                clock.sleep(2)
            elif step.kind == "keyEvent" and action.get("key") == "BACK":
                device.key_back()
            elif step.kind == "agent":
                device.broadcast(ACTION_AGENT, "--es", EXTRA_PROVIDER, action["provider"],
                                 "--es", EXTRA_ASK, action["ask"])
            elif step.kind == "passenger":
                if not re.fullmatch(r"[a-z_]+", str(action.get("media", ""))):
                    raise DemoFailure(f"invalid passenger media {action.get('media')!r}")
                device.broadcast(ACTION_PASSENGER, "--es", EXTRA_MEDIA, action["media"])
            elif step.kind == "endCard":
                device.broadcast(ACTION_END_CARD)
            elif step.kind == "vehicleSignal":
                evidence["vehicleSignals"].append(vehicle_signal(device, action))
            elif step.kind == "wallClock":
                # The status bar's time follows the story (needs root adbd and auto_time off).
                when = dt.datetime.strptime(action["local"], "%Y-%m-%dT%H:%M")
                device.shell("date", when.strftime("%m%d%H%M%Y.00"))
            else:
                raise DemoFailure(f"unknown action type {step.kind}")
            evidence["steps"].append({"atMs": step.at_ms, "type": step.kind,
                                      "startedMs": round(elapsed * 1000)})
        clock.sleep(max(0, started + duration - clock.now()))
    finally:
        # A recorder that is gone before its own time limit died; one at its limit did not.
        now = clock.now() - started
        early = [name for name, proc, began, limit in recorders
                 if not device.alive(proc) and now - began < limit - 1]
        device.stop_recordings([proc for _, proc, _, _ in recorders])
        restore_vehicle(device, evidence["vehicleSignals"])
        device.set_car_state("park")
        if dual:
            device.broadcast(ACTION_PASSENGER, "--es", EXTRA_MEDIA, "stop")
    if early:
        raise DemoFailure(f"{', '.join(early)} screenrecord exited before the scenario ended")
    return evidence


@dataclass(frozen=True)
class ChapterCard:
    """A full card over the driver's picture at a time on the device timeline: the opening title,
    or an intermission that hides a case switch and a clock jump between chapters."""
    image: pathlib.Path
    at: float = 0.0  # seconds on the device timeline
    seconds: float = 2.5
    fade_in: float = 0.0  # 0 cuts in: the opening card lies over the recording's first frame
    fade_out: float = TITLE_FADE_S


@dataclass
class RenderPlan:
    duration: int
    layout: str = "single"
    driver_raw: pathlib.Path = pathlib.Path()
    driver_size: tuple[int, int] | None = None  # needed for a single-display title card
    passenger_raw: pathlib.Path | None = None
    passenger_start: float = 0.0
    passenger_still: pathlib.Path | None = None
    chapters: list = field(default_factory=list)  # [ChapterCard], in time order
    hold_from: float | None = None
    audio_dir: pathlib.Path | None = None  # None renders silently
    clips: list = field(default_factory=list)  # atMs on the output timeline (placed_clips)
    cues: list = field(default_factory=list)
    labels: dict = field(default_factory=lambda: dict(LABELS))
    preamble: list = field(default_factory=list)  # [(card PNG, seconds)], before the timeline
    outro: tuple | None = None  # (card PNG, seconds), after the timeline over its last frame
    disclaimer: pathlib.Path | None = None  # one line in the margin for the whole video
    cockpit: dict | None = None  # demo_overlays.py overlays.json: layout, overlays, intro
    lead: float = 0.0  # seconds of cockpit intro before the preamble starts

    def total(self) -> float:
        return (self.lead + sum(seconds for _, seconds in self.preamble) + self.duration
                + (self.outro[1] if self.outro else 0))


def legacy(plan: RenderPlan) -> bool:
    return (plan.layout == "single" and not plan.chapters and plan.hold_from is None
            and not plan.preamble and plan.outro is None and plan.disclaimer is None)


def card_fit(width: int, height: int) -> str:
    """A full-canvas card or title image, fitted to the canvas without distortion."""
    return (f"scale={width}:{height}:force_original_aspect_ratio=decrease,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color={BACKGROUND}")


def chapter_stream(index: int, card: ChapterCard, width: int, height: int, out: str) -> str:
    """A chapter card as an RGBA stream starting at its time. Its fades ease: a fade multiplies
    what it is given, so each one runs twice, a quadratic ease-in (p²) into the card and an
    ease-out ((1-p)²) from it into what follows.

    The fade-out dips through dark, like the intro's passenger card: text over text ghosts in a
    crossfade, so for its first half the card stays opaque while its content eases to the
    background colour, and for its second half that dark lifts off the live screen."""
    fades = []
    if card.fade_in > 0:
        fades += [f"fade=t=in:st=0:d={card.fade_in:.3f}:alpha=1"] * 2
    half = card.fade_out / 2
    dip = card.seconds - card.fade_out
    fades += [f"fade=t=out:st={dip:.3f}:d={half:.3f}:color={BACKGROUND}"] * 2
    fades += [f"fade=t=out:st={dip + half:.3f}:d={half:.3f}:alpha=1"] * 2
    return (f"[{index}:v]{card_fit(width, height)},format=rgba,{','.join(fades)},"
            f"trim=duration={card.seconds:.3f},setpts=PTS-STARTPTS+{card.at:.3f}/TB{out}")


def fit(source: str, box: tuple[int, int, int, int], out: str) -> str:
    _, _, w, h = box
    return (f"{source}scale={w}:{h}:force_original_aspect_ratio=decrease:flags=lanczos,"
            f"pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color={BACKGROUND},setsar=1,format=yuv420p{out}")


def stream(index: int, seconds: float, hold_end: float | None) -> str:
    """CFR, frozen from hold_end, padded by cloning the last frame, cut to exactly seconds.

    screenrecord is variable-frame-rate and emits nothing while the screen is still, so its
    timeline can end at the last change; tpad clones that frame out to the manifest duration.
    """
    held = f",trim=end={hold_end:.3f}" if hold_end is not None else ""
    return (f"[{index}:v]fps={FPS}{held},tpad=stop_mode=clone:stop_duration={seconds:.3f},"
            f"trim=duration={seconds:.3f},setpts=PTS-STARTPTS")


def render_command(plan: RenderPlan, output: pathlib.Path) -> list[str]:
    """The single ffmpeg invocation that composes video, mixes timed audio and trims exactly.

    The timeline section runs for the device timeline plus the outro, whose card fades in over
    the timeline's held last frame; the preamble cards are concatenated in front of it; the
    disclaimer is overlaid on the result. Clip and cue times are already on that timeline.
    """
    target = plan.duration
    span = target + (plan.outro[1] if plan.outro else 0)
    total = plan.total()
    length = f"{total:g}" if float(total).is_integer() else f"{total:.3f}"
    command = ["ffmpeg", "-y", "-v", "error", "-i", str(plan.driver_raw)]
    count = 1
    filters: list[str] = []
    if legacy(plan):
        # Clone the last frame to the manifest duration: screenrecord is variable-frame-rate and
        # may end its timeline at the last UI change.
        filters.append(f"[0:v]fps=30,tpad=stop_mode=clone:stop_duration={target},"
                       f"trim=duration={target}[v]")  # CFR + trim: -t alone overshot by ~0.5s
    elif plan.layout == "single":
        width, height = plan.driver_size or (0, 0)
        if not width or not height:
            raise DemoFailure("a single-display chapter card needs the recording's size")
        filters.append(stream(0, span, plan.hold_from) + "[base]")
        last = "[base]"
    else:
        start = round(plan.passenger_start * FPS) / FPS
        hold = plan.hold_from
        filters.append(f"color=c={BACKGROUND}:s={CANVAS[0]}x{CANVAS[1]}:r={FPS}:d={span}[bg]")
        filters.append(fit(stream(0, span, hold) + ",", DRIVER_BOX, "[drv]"))
        rec_end = None if hold is None else hold - start
        record = plan.passenger_raw is not None and start < target and (
            rec_end is None or rec_end > 0)
        idle = span if not record else start
        parts = []
        if idle > 0:
            if plan.passenger_still is not None:
                command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{idle:.3f}",
                            "-i", str(plan.passenger_still)]
                filters.append(fit(f"[{count}:v]trim=duration={idle:.3f},", PASSENGER_BOX,
                                   "[pre]"))
                count += 1
            else:
                filters.append(f"color=c={BACKGROUND}:s={PASSENGER_BOX[2]}x{PASSENGER_BOX[3]}:"
                               f"r={FPS}:d={idle:.3f},setsar=1,format=yuv420p[pre]")
            parts.append("[pre]")
        if record:
            command += ["-i", str(plan.passenger_raw)]
            filters.append(fit(stream(count, span - start, rec_end) + ",", PASSENGER_BOX,
                               "[rec]"))
            count += 1
            parts.append("[rec]")
        if len(parts) == 2:
            filters.append("[pre][rec]concat=n=2:v=1:a=0[pas]")
        else:
            filters.append(f"{parts[0]}null[pas]")
        command += ["-i", str(plan.labels["driver"]), "-i", str(plan.labels["passenger"])]
        label_driver, label_passenger = count, count + 1
        count += 2
        dx, dy = DRIVER_BOX[:2]
        px, py = PASSENGER_BOX[:2]
        filters.append(f"[bg][drv]overlay={dx}:{dy}[c1];[c1][pas]overlay={px}:{py}[c2];"
                       f"[c2][{label_driver}:v]overlay=x={dx}:y={dy - LABEL_GAP}-h[c3];"
                       f"[c3][{label_passenger}:v]overlay=x={px}:y={py - LABEL_GAP}-h[base]")
        width, height = CANVAS
        last = "[base]"
    if not legacy(plan):
        for n, card in enumerate(plan.chapters):
            command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{card.seconds:.3f}",
                        "-i", str(card.image)]
            filters.append(chapter_stream(count, card, width, height, f"[title{n}]"))
            filters.append(f"{last}[title{n}]overlay=0:0:eof_action=pass:repeatlast=0"
                           f"[titled{n}]")
            last = f"[titled{n}]"
            count += 1
        if plan.outro is not None:
            card, seconds = plan.outro
            command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{seconds:.3f}",
                        "-i", str(card)]
            filters.append(f"[{count}:v]{card_fit(width, height)},format=rgba,fade=t=in:st=0:"
                           f"d={OUTRO_FADE_S}:alpha=1,setpts=PTS-STARTPTS+{target}/TB[outro]")
            filters.append(f"{last}[outro]overlay=0:0:eof_action=pass[closed]")
            last = "[closed]"
            count += 1
        if plan.preamble:
            filters.append(f"{last}setsar=1,format=yuv420p,fade=t=in:st=0:d={PREAMBLE_FADE_S}:"
                           f"color={BACKGROUND}[story]")
            parts = []
            for n, (card, seconds) in enumerate(plan.preamble):
                command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{seconds:.3f}",
                            "-i", str(card)]
                filters.append(
                    f"[{count}:v]{card_fit(width, height)},setsar=1,format=yuv420p,"
                    f"fade=t=in:st=0:d={PREAMBLE_FADE_S}:color={BACKGROUND},"
                    f"fade=t=out:st={seconds - PREAMBLE_FADE_S:.3f}:d={PREAMBLE_FADE_S}:"
                    f"color={BACKGROUND},trim=duration={seconds:.3f},setpts=PTS-STARTPTS[pre{n}]")
                parts.append(f"[pre{n}]")
                count += 1
            filters.append("".join(parts) + f"[story]concat=n={len(parts) + 1}:v=1:a=0[cut]")
            last = "[cut]"
        if plan.disclaimer is not None:
            command += ["-i", str(plan.disclaimer)]
            x, margin = DISCLAIMER_POS
            filters.append(f"{last}[{count}:v]overlay=x={x}:y=H-h-{margin}[noted]")
            last = "[noted]"
            count += 1
        filters.append(f"{last}format=yuv420p[v]")
    return finish(plan, output, command, filters, count, length)


def finish(plan: RenderPlan, output: pathlib.Path, command: list[str], filters: list[str],
           count: int, length: str) -> list[str]:
    """Mix the timed speech and cues (inputs from `count` on), then encode exactly `length`."""
    maps = ["-map", "[v]"]
    if plan.audio_dir is not None:
        speech = [plan.audio_dir / f"{clip['id']}.mp3" for clip in plan.clips]
        effects = [plan.audio_dir / "sfx" / f"{cue['effectId']}.mp3" for cue in plan.cues]
        missing = [path for path in speech + effects if not path.exists()]
        if missing:
            raise DemoFailure(f"missing {len(missing)} TTS clip(s); prepare the ElevenLabs "
                              "cache first")
        for path in speech + effects:
            command.extend(["-i", str(path)])
        labels = []
        for offset, clip in enumerate(plan.clips, start=count):
            filters.append(f"[{offset}:a]adelay=delays={int(clip['atMs'])}:all=1[a{offset}]")
            labels.append(f"[a{offset}]")
        for offset, cue in enumerate(plan.cues, start=count + len(plan.clips)):
            filters.append(f"[{offset}:a]volume={float(cue['gainDb']):g}dB,"
                           f"adelay=delays={int(cue['atMs'])}:all=1[a{offset}]")
            labels.append(f"[a{offset}]")
        # A closing card's silent hold outlasts the last clip: pad the mix to the video's length.
        pad = "" if legacy(plan) else f"apad=whole_dur={length},"
        filters.append("".join(labels) + f"amix=inputs={len(labels)}:duration=longest:"
                       f"normalize=0,{pad}atrim=duration={length}[a]")
        maps += ["-map", "[a]"]
    command += ["-filter_complex", ";".join(filters), *maps,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"]
    command += ["-c:a", "aac", "-b:a", "160k"] if plan.audio_dir is not None else ["-an"]
    command += ["-t", length, "-movflags", "+faststart", str(output)]
    return command


def enable(start: float, end: float) -> str:
    return f"enable='gte(t,{start:.3f})*lt(t,{end:.3f})'"


def cockpit_command(plan: RenderPlan, output: pathlib.Path) -> list[str]:
    """The cockpit composition: one still frame for the whole video, the screens in its windows.

    Driver window: the intro's lead, the preamble cards, then the recording with the chapter
    cards over it at their times (the opening title, the intermission). Passenger window: its
    idle frame until the stream starts. The intro frames lie over both windows from the first
    frame and ease out as the preamble begins, the frame and the intro glow over those, then
    the story rail over the device timeline, the closing card over everything, the captions
    and the disclaimer. All times are output times.
    """
    over = plan.cockpit
    lay = over["layout"]
    root = pathlib.Path(lay["root"])
    dbox, pbox = tuple(lay["driver"]), tuple(lay["passenger"])
    (dx, dy, dw, dh), (px, py, pw, ph) = dbox, pbox
    width, height = lay["canvas"]
    target = plan.duration
    span = target + (plan.outro[1] if plan.outro else 0)
    head = plan.lead + sum(seconds for _, seconds in plan.preamble)  # the device timeline's 0
    total = plan.total()
    length = f"{total:.3f}"
    command = ["ffmpeg", "-y", "-v", "error", "-i", str(plan.driver_raw)]
    count = 1
    filters: list[str] = []
    flat = "setsar=1,format=yuv420p"
    # Driver window: lead (under the intro), preamble cards, recording.
    parts = []
    if plan.lead > 0:
        filters.append(f"color=c={BACKGROUND}:s={dw}x{dh}:r={FPS}:d={plan.lead:.3f},"
                       f"{flat}[dlead]")
        parts.append("[dlead]")
    for n, (card, seconds) in enumerate(plan.preamble):
        command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{seconds:.3f}", "-i", str(card)]
        filters.append(
            f"[{count}:v]{card_fit(dw, dh)},{flat},"
            f"fade=t=in:st=0:d={PREAMBLE_FADE_S}:color={BACKGROUND},"
            f"fade=t=out:st={seconds - PREAMBLE_FADE_S:.3f}:d={PREAMBLE_FADE_S}:"
            f"color={BACKGROUND},trim=duration={seconds:.3f},setpts=PTS-STARTPTS[dpre{n}]")
        parts.append(f"[dpre{n}]")
        count += 1
    filters.append(fit(stream(0, span, plan.hold_from) + ",", dbox, "[drec]"))
    rec = "[drec]"
    if plan.preamble:  # the story fades in after the last card's dip
        filters.append(f"{rec}fade=t=in:st=0:d={PREAMBLE_FADE_S}:color={BACKGROUND}[drecf]")
        rec = "[drecf]"
    for n, card in enumerate(plan.chapters):  # in the driver window; the frame stays still
        command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{card.seconds:.3f}",
                    "-i", str(card.image)]
        filters.append(chapter_stream(count, card, dw, dh, f"[dtitle{n}]"))
        filters.append(f"{rec}[dtitle{n}]overlay=0:0:eof_action=pass:repeatlast=0,{flat}"
                       f"[drect{n}]")
        rec = f"[drect{n}]"
        count += 1
    parts.append(rec)
    filters.append("".join(parts) + f"concat=n={len(parts)}:v=1:a=0[dwin]")
    # Passenger window: idle frame (or dark) until the stream starts, on the output timeline.
    start = round(plan.passenger_start * FPS) / FPS
    hold = plan.hold_from
    rec_end = None if hold is None else hold - start
    record = plan.passenger_raw is not None and start < target and (
        rec_end is None or rec_end > 0)
    idle = head + (span if not record else start)
    pparts = []
    if plan.passenger_still is not None:
        command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{idle:.3f}",
                    "-i", str(plan.passenger_still)]
        filters.append(fit(f"[{count}:v]trim=duration={idle:.3f},", pbox, "[ppre]"))
        count += 1
    else:
        filters.append(f"color=c={BACKGROUND}:s={pw}x{ph}:r={FPS}:d={idle:.3f},{flat}[ppre]")
    pparts.append("[ppre]")
    if record:
        command += ["-i", str(plan.passenger_raw)]
        filters.append(fit(stream(count, span - start, rec_end) + ",", pbox, "[prec]"))
        count += 1
        pparts.append("[prec]")
    filters.append("".join(pparts) + f"concat=n={len(pparts)}:v=1:a=0[pwin]")
    windows = {"driver": "[dwin]", "passenger": "[pwin]"}
    intro = over.get("intro")
    if intro:  # frame-exact sequences with the push-in and the eased alpha baked in
        for name in ("driver", "passenger"):
            command += ["-framerate", str(intro["fps"]), "-i", intro[name]]
            filters.append(f"{windows[name]}[{count}:v]overlay=0:0:eof_action=pass,{flat}"
                           f"[{name[0]}intro]")
            windows[name] = f"[{name[0]}intro]"
            count += 1
    filters.append(f"color=c={BACKGROUND}:s={width}x{height}:r={FPS}:d={length}[bg]")
    command += ["-i", str(root / lay["frame"])]
    filters.append(f"[bg]{windows['driver']}overlay={dx}:{dy}[k1];"
                   f"[k1]{windows['passenger']}overlay={px}:{py}[k2];"
                   f"[k2][{count}:v]overlay=0:0[k3]")
    count += 1
    last = "[k3]"
    if intro:
        command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{intro['endS']:.3f}",
                    "-i", str(root / lay["glow"])]
        fade = intro["endS"] - intro["fadeStartS"]
        filters.append(f"[{count}:v]format=rgba,fade=t=out:st={intro['fadeStartS']:.3f}:"
                       f"d={fade:.3f}:alpha=1[glow];{last}[glow]overlay=0:0:eof_action=pass[k4]")
        last = "[k4]"
        count += 1
    rx, ry = lay["railBand"][:2]
    for n, (png, begin, end) in enumerate(over["rail"]):
        command += ["-i", png]
        filters.append(f"{last}[{count}:v]overlay={rx}:{ry}:{enable(begin, end)}[r{n}]")
        last = f"[r{n}]"
        count += 1
    if plan.outro is not None:
        card, seconds = plan.outro
        command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{seconds:.3f}",
                    "-i", str(card)]
        filters.append(f"[{count}:v]{card_fit(width, height)},format=rgba,fade=t=in:st=0:"
                       f"d={OUTRO_FADE_S}:alpha=1,setpts=PTS-STARTPTS+{head + target:.3f}/TB"
                       f"[outro];{last}[outro]overlay=0:0:eof_action=pass[ko]")
        last = "[ko]"
        count += 1
    cx, cy = lay["captionBand"][:2]
    for n, (png, begin, end) in enumerate(over["captions"]):
        command += ["-i", png]
        filters.append(f"{last}[{count}:v]overlay={cx}:{cy}:{enable(begin, end)}[s{n}]")
        last = f"[s{n}]"
        count += 1
    if plan.disclaimer is not None:  # centred in the rail band, clear of title and progress
        _, band_y, _, band_h = lay["railBand"]
        if plan.outro is not None:  # the closing card carries its own note: gone before it
            ends = head + target    # fades in, so the two lines never ghost over each other
            command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{ends:.3f}",
                        "-i", str(plan.disclaimer)]
            filters.append(f"[{count}:v]format=rgba,fade=t=out:st={ends - OUTRO_FADE_S:.3f}:"
                           f"d={OUTRO_FADE_S}:alpha=1[note];{last}[note]overlay=x=(W-w)/2:"
                           f"y={band_y}+({band_h}-h)/2:eof_action=pass[noted]")
        else:
            command += ["-i", str(plan.disclaimer)]
            filters.append(f"{last}[{count}:v]overlay=x=(W-w)/2:y={band_y}+({band_h}-h)/2"
                           f"[noted]")
        last = "[noted]"
        count += 1
    filters.append(f"{last}format=yuv420p[v]")
    return finish(plan, output, command, filters, count, length)


def preamble_seconds(scenario: dict) -> float:
    return round(sum(float(card["seconds"]) for card in scenario.get("preamble", [])), 3)


def outro_seconds(scenario: dict) -> float:
    return float(scenario["outro"]["seconds"]) if scenario.get("outro") else 0.0


def total_seconds(scenario: dict) -> float:
    """The output's length: preamble + device timeline + outro."""
    return preamble_seconds(scenario) + int(scenario["durationSeconds"]) + outro_seconds(scenario)


def section_offset_ms(scenario: dict, section: str, lead_ms: int = 0) -> int:
    """Where a clip's section starts on the output timeline. Preamble clips count from the
    start of the preamble (after a cockpit intro's lead_ms), timeline clips from the device
    timeline, outro clips from the outro."""
    preamble = lead_ms + round(preamble_seconds(scenario) * 1000)
    if section == "preamble":
        return lead_ms
    if section == "timeline":
        return preamble
    if section == "outro":
        return preamble + int(scenario["durationSeconds"]) * 1000
    raise DemoFailure(f"unknown clip section {section!r}")


def placed_clips(scenario: dict, lead_ms: int = 0) -> list[dict]:
    return [dict(clip, atMs=section_offset_ms(scenario, clip.get("section", "timeline"), lead_ms)
                 + int(clip["atMs"])) for clip in scenario["clips"]]


def placed_cues(scenario: dict, lead_ms: int = 0) -> list[dict]:
    offset = section_offset_ms(scenario, "timeline", lead_ms)
    return [dict(cue, atMs=offset + int(cue["atMs"])) for cue in scenario.get("soundEffects", [])]


def build_cockpit(scenario: dict, clips: list[dict], prefix: pathlib.Path, *, lead: float,
                  caption_audio: pathlib.Path | None, estimate: bool,
                  srt: pathlib.Path) -> dict:
    """demo_overlays.py, in a child interpreter (this recorder never imports Pillow): the intro
    frames, captions for every section, the story rail and the SRT, all on the output timeline."""
    spec = {"clips": clips, "scenario": scenario,
            "railOffsetS": lead + preamble_seconds(scenario),
            "audioDir": str(caption_audio) if caption_audio else None,
            "estimateMissing": estimate, "srt": str(srt),
            "intro": {"hold": INTRO_HOLD_S, "fade": INTRO_FADE_S, "zoom": INTRO_ZOOM}}
    spec_path = prefix.with_name(f"{prefix.name}.overlays.spec.json")
    spec_path.write_text(json.dumps(spec, indent=2) + "\n", encoding="utf-8")
    out = prefix.with_name(f"{prefix.name}.overlays")
    run([sys.executable, str(OVERLAY_RENDERER), "overlays", "--spec", str(spec_path),
         "--out", str(out)], timeout=600)
    return json.loads((out / "overlays.json").read_text(encoding="utf-8"))


def extract_frame(video: pathlib.Path | None, seconds: float,
                  out: pathlib.Path) -> pathlib.Path | None:
    """The frame on screen at `seconds`, or None. screenrecord emits nothing while the screen is
    still, so a time past the stream's last frame means that last frame."""
    if video is None or not video.is_file() or seconds < 0:
        return None
    out.unlink(missing_ok=True)
    try:
        at = min(seconds, max(0.0, probe_duration(video) - 0.05))
    except (DemoFailure, ValueError, KeyError):
        return None
    attempts = [["-ss", f"{at:.3f}", "-i", str(video), "-frames:v", "1"],
                ["-sseof", "-3", "-i", str(video), "-update", "1"]]  # the last decoded frame
    for attempt in attempts:
        result = run(["ffmpeg", "-nostdin", "-y", "-v", "error", *attempt, str(out)],
                     check=False, timeout=60)
        if not result.returncode and out.is_file() and out.stat().st_size > 0:
            return out
    return None


def render_card(*args: str) -> None:
    """render-demo-cards.py in a child interpreter, so this recorder never imports Pillow."""
    run([sys.executable, str(CARD_RENDERER), *args], timeout=180)


def build_preamble(scenario: dict, raw_video: pathlib.Path,
                   prefix: pathlib.Path) -> tuple[list[tuple[pathlib.Path, float]], list[dict]]:
    """Each preamble card, rendered around the raw driver frame at its frameAtMs."""
    cards, evidence = [], []
    for n, entry in enumerate(scenario.get("preamble", []), start=1):
        frame = extract_frame(raw_video, int(entry["frameAtMs"]) / 1000,
                              prefix.with_name(f"{prefix.name}.preamble-{n}.frame.png"))
        card = prefix.with_name(f"{prefix.name}.preamble-{n}.png")
        args = ["preamble", "--style", entry["style"], "--out", str(card)]
        if frame is not None:
            args += ["--frame", str(frame)]
        if entry.get("chip"):
            args += ["--chip", entry["chip"]]
        if entry.get("lockText"):
            args += ["--lock-text", entry["lockText"]]
        render_card(*args)
        cards.append((card, float(entry["seconds"])))
        evidence.append({"style": entry["style"], "frameAtMs": int(entry["frameAtMs"]),
                         "frame": "raw driver recording" if frame else "unavailable; dark panel",
                         "seconds": float(entry["seconds"]), "sha256": sha256(card)})
    return cards, evidence


def journey(scenario: dict) -> list[dict]:
    """The car's state segments in order, from the carState actions (scenarios start parked)."""
    segments = [{"state": "park", "startMs": 0}]
    for action in sorted(scenario["actions"], key=lambda item: int(item["atMs"])):
        if action["type"] == "carState" and action["state"] != segments[-1]["state"]:
            segments[-1]["endMs"] = int(action["atMs"])
            segments.append({"state": action["state"], "startMs": int(action["atMs"])})
    segments[-1]["endMs"] = int(scenario["durationSeconds"]) * 1000
    return segments


def segment_at(segments: list[dict], at_ms: int) -> int:
    return next((n for n, s in enumerate(segments) if s["startMs"] <= at_ms < s["endMs"]),
                len(segments) - 1)


def outro_spec(scenario: dict, frames: dict[int, pathlib.Path | None]) -> dict:
    """render-demo-cards.py's outro spec: segments labelled from the scenario's places, beats
    assigned to the segment they happen in, each with its frame (None draws a placeholder)."""
    outro = scenario["outro"]
    segments = journey(scenario)
    places = iter(outro.get("places", []))
    beats = outro.get("beats", [])
    driver = [n for n, beat in enumerate(beats) if beat.get("display", "driver") == "driver"]
    spec_segments = []
    for n, segment in enumerate(segments):
        if segment["state"] == "park":
            place = next(places, None)
            text = f"Parked · {place}" if place else "Parked"
        else:
            text = "Driving"
        slots = sum(segment_at(segments, int(beats[i]["atMs"])) == n for i in driver)
        spec_segments.append({"state": segment["state"], "label": text, "slots": max(1, slots)})

    def lane(display: str) -> list[dict]:
        return [{"segment": segment_at(segments, int(beat["atMs"])), "label": beat["label"],
                 "image": str(frames[n]) if frames.get(n) else None}
                for n, beat in enumerate(beats) if beat.get("display", "driver") == display]

    icon = ROOT / outro["badgeIcon"] if outro.get("badgeIcon") else None
    return {"segments": spec_segments, "driver": lane("driver"), "passenger": lane("passenger"),
            "architecture": outro.get("architecture", []), "note": outro.get("note", ""),
            "icon": str(icon) if icon is not None and icon.is_file() else None}


def build_outro(scenario: dict, raw_video: pathlib.Path, passenger_raw: pathlib.Path | None,
                passenger_start: float, passenger_still: pathlib.Path | None,
                prefix: pathlib.Path) -> tuple[tuple[pathlib.Path, float], list[dict]]:
    """The closing card, its thumbnails taken from the raw recordings at each beat's time."""
    frames: dict[int, pathlib.Path | None] = {}
    evidence = []
    for n, beat in enumerate(scenario["outro"].get("beats", [])):
        out = prefix.with_name(f"{prefix.name}.outro-{n + 1}.frame.png")
        at = int(beat["atMs"]) / 1000
        if beat.get("display", "driver") == "passenger":
            if passenger_raw is not None and at >= passenger_start:
                frames[n] = extract_frame(passenger_raw, at - passenger_start, out)
            else:
                frames[n] = passenger_still if passenger_still and passenger_still.is_file() \
                    else None
        else:
            frames[n] = extract_frame(raw_video, at, out)
        evidence.append({"label": beat["label"], "display": beat.get("display", "driver"),
                         "atMs": int(beat["atMs"]),
                         "frame": "extracted" if frames[n] else "unavailable; placeholder"})
    spec_path = prefix.with_name(f"{prefix.name}.outro.json")
    spec_path.write_text(json.dumps(outro_spec(scenario, frames), indent=2) + "\n",
                         encoding="utf-8")
    card = prefix.with_name(f"{prefix.name}.outro.png")
    render_card("outro", "--spec", str(spec_path), "--out", str(card))
    return (card, outro_seconds(scenario)), evidence


def disclaimer_image(scenario: dict) -> pathlib.Path | None:
    note = scenario.get("disclaimer")
    if not note:
        return None
    path = ROOT / note["image"]
    if not path.is_file() or path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        raise DemoFailure(f"disclaimer {note['image']} is missing or not a PNG")
    return path


def probe_size(path: pathlib.Path) -> tuple[int, int]:
    data = json.loads(run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                           "stream=width,height", "-of", "json", str(path)]).stdout)
    stream_info = data["streams"][0]
    return int(stream_info["width"]), int(stream_info["height"])


def probe_duration(path: pathlib.Path) -> float:
    data = json.loads(run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                           "-of", "json", str(path)]).stdout)
    return float(data["format"]["duration"])


def render(plan: RenderPlan, output: pathlib.Path) -> None:
    if plan.cockpit is not None:
        run(cockpit_command(plan, output), timeout=1800)
        return
    if plan.layout == "single" and not legacy(plan) and plan.driver_size is None:
        plan.driver_size = probe_size(plan.driver_raw)
    run(render_command(plan, output), timeout=900)


def check_duration(duration: float, expected: float) -> None:
    # Container duration is frame/AAC-packet based (observed 90.019934 for a 90 s cap): keep a
    # sub-frame tolerance only.
    if not MIN_S <= duration <= MAX_S + 0.1:
        raise DemoFailure(f"recording duration {duration:.1f}s is outside the {MIN_S}–{MAX_S}s "
                          "target")
    if abs(duration - expected) > 0.1:
        raise DemoFailure(f"recording duration {duration:.3f}s is not the manifest's "
                          f"{expected}s")


def validate_video(path: pathlib.Path, expected_duration: float, require_audio: bool,
                   expected_size: tuple[int, int] | None = None) -> dict:
    data = path.read_bytes()
    if len(data) < 100_000 or data[4:8] != b"ftyp" or b"moov" not in data:
        raise DemoFailure("recording is missing MP4 ftyp/moov structure or is implausibly small")
    probe = json.loads(run(["ffprobe", "-v", "error", "-show_entries",
                            "format=duration:stream=codec_type,codec_name,sample_rate,channels,"
                            "width,height", "-of", "json", str(path)]).stdout)
    duration = float(probe["format"]["duration"])
    check_duration(duration, expected_duration)
    hashes = run(["ffmpeg", "-v", "error", "-i", str(path), "-vf",
                  "crop=iw:ih*0.76:0:ih*0.12", "-f", "framehash", "-"], timeout=300).stdout
    distinct = len({line.split()[-1] for line in hashes.splitlines() if line and line[0] != "#"})
    if distinct < 5:
        raise DemoFailure(f"recording has only {distinct} distinct cropped content frames")
    video_streams = [stream_info for stream_info in probe.get("streams", [])
                     if stream_info.get("codec_type") == "video"]
    audio_streams = [stream_info for stream_info in probe.get("streams", [])
                     if stream_info.get("codec_type") == "audio"]
    if len(video_streams) != 1 or video_streams[0].get("codec_name") != "h264":
        raise DemoFailure("recording must contain exactly one H.264 video stream")
    size = (video_streams[0]["width"], video_streams[0]["height"])
    if expected_size and size != expected_size:
        raise DemoFailure(f"recording is {size[0]}x{size[1]}, expected "
                          f"{expected_size[0]}x{expected_size[1]}")
    audio_validation = None
    if require_audio:
        if len(audio_streams) != 1 or audio_streams[0].get("codec_name") != "aac":
            raise DemoFailure("narrated recording must contain exactly one AAC audio stream")
        volume = run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-map",
                      "0:a:0", "-af", "volumedetect", "-f", "null", "-"], check=False,
                     timeout=120)
        diagnostic = volume.stderr
        mean_match = re.search(r"mean_volume:\s*(-?[0-9.]+) dB", diagnostic)
        max_match = re.search(r"max_volume:\s*(-?[0-9.]+) dB", diagnostic)
        if volume.returncode or not mean_match or not max_match:
            raise DemoFailure("could not measure the narrated recording's audio level")
        mean_db = float(mean_match.group(1))
        max_db = float(max_match.group(1))
        if not -40 <= mean_db <= -8 or not -12 <= max_db <= 0:
            raise DemoFailure(f"audio level is implausible (mean {mean_db} dB, max {max_db} dB)")
        audio_validation = {
            "codec": audio_streams[0]["codec_name"],
            "sampleRateHz": int(audio_streams[0]["sample_rate"]),
            "channels": int(audio_streams[0]["channels"]),
            "meanVolumeDb": mean_db,
            "maxVolumeDb": max_db,
        }
    return {"durationSeconds": duration, "bytes": len(data), "distinctContentFrames": distinct,
            "sha256": sha256(path), "targetDurationSeconds": expected_duration,
            "video": {"codec": video_streams[0]["codec_name"],
                      "width": video_streams[0]["width"],
                      "height": video_streams[0]["height"]},
            "audio": audio_validation}


def installed_package_evidence() -> dict:
    package_path_output = adb_shell("pm", "path", "--user", "current", PACKAGE).stdout.strip()
    package_path = next((line.removeprefix("package:") for line in
                         package_path_output.splitlines() if line.startswith("package:")), None)
    if not package_path:
        raise DemoFailure("could not resolve installed Host B APK path")
    checksum = adb_shell("sha256sum", package_path).stdout.strip().split()
    if len(checksum) < 2 or not re.fullmatch(r"[0-9a-f]{64}", checksum[0]):
        raise DemoFailure("could not hash installed Host B APK")
    package_dump = adb_shell("dumpsys", "package", PACKAGE).stdout
    version_name = re.search(r"versionName=([^\s]+)", package_dump)
    version_code = re.search(r"versionCode=(\d+)", package_dump)
    return {"sha256": checksum[0],
            "versionName": version_name.group(1) if version_name else None,
            "versionCode": int(version_code.group(1)) if version_code else None}


def source_state_evidence() -> dict:
    """Bind tracked edits and every non-ignored untracked byte without exposing their contents."""
    tracked = subprocess.run(["git", "diff", "--binary", "HEAD"], check=True,
                             capture_output=True).stdout
    untracked_result = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"], check=True,
        capture_output=True).stdout
    untracked_paths = sorted(path.decode() for path in untracked_result.split(b"\0") if path)
    untracked_tree = hashlib.sha256()
    for relative in untracked_paths:
        path = ROOT / relative
        if not path.is_file():
            continue
        untracked_tree.update(relative.encode())
        untracked_tree.update(b"\0")
        untracked_tree.update(hashlib.sha256(path.read_bytes()).digest())
        untracked_tree.update(b"\0")
    return {
        "dirty": bool(tracked or untracked_paths),
        "trackedDiffSha256": hashlib.sha256(tracked).hexdigest(),
        "untrackedTreeSha256": untracked_tree.hexdigest(),
        "untrackedFileCount": len(untracked_paths),
    }


def chapter_cards(scenario: dict) -> list[ChapterCard]:
    """The scenario's chapter cards, in time order, each a PNG held 2–3.5 s inside the device
    timeline, its fades inside its hold, none overlapping the next."""
    if "titleCard" in scenario:
        raise DemoFailure("titleCard is now the first of chapterCards")
    cards = []
    for spec in scenario.get("chapterCards", []):
        path = ROOT / spec["image"]
        card = ChapterCard(image=path, at=int(spec.get("atMs", 0)) / 1000,
                           seconds=float(spec["seconds"]),
                           fade_in=float(spec.get("fadeIn", 0)),
                           fade_out=float(spec.get("fadeOut", TITLE_FADE_S)))
        if not path.is_file() or path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
            raise DemoFailure(f"chapter card {spec['image']} is missing or not a PNG")
        if not CHAPTER_SECONDS[0] <= card.seconds <= CHAPTER_SECONDS[1]:
            raise DemoFailure("a chapter card holds for 2–3.5 s")
        if card.fade_in < 0 or card.fade_out <= 0 or card.fade_in + card.fade_out >= card.seconds:
            raise DemoFailure(f"chapter card {spec['image']} fades outside its hold")
        if cards and card.at < cards[-1].at + cards[-1].seconds:
            raise DemoFailure("chapter cards are in time order and never overlap")
        if card.at + card.seconds > int(scenario["durationSeconds"]):
            raise DemoFailure(f"chapter card {spec['image']} runs past the device timeline")
        cards.append(card)
    return cards


def post_production(scenario: dict, *, layout: str, output: pathlib.Path,
                    raw_video: pathlib.Path, passenger_raw: pathlib.Path | None,
                    passenger_start: float, passenger_still: pathlib.Path | None,
                    audio_dir: pathlib.Path | None, cockpit: bool = False,
                    caption_audio: pathlib.Path | None = None) -> tuple[RenderPlan, dict]:
    """The render plan for raw recordings already on disk: preamble and outro cards built from
    their frames, the disclaimer, and every clip and cue placed on the output timeline. Also
    re-renders an earlier capture without the device (scripts/run-nyc-demo.py --render-only)."""
    chapters = chapter_cards(scenario)
    hold_ms = scenario.get("endCardHoldFromMs")
    prefix = output.with_name(output.stem)
    preamble, preamble_evidence = build_preamble(scenario, raw_video, prefix)
    outro, outro_evidence = (build_outro(scenario, raw_video, passenger_raw, passenger_start,
                                         passenger_still, prefix)
                             if scenario.get("outro") else (None, []))
    note = disclaimer_image(scenario)
    use_cockpit = cockpit and layout == "dual"
    lead = INTRO_HOLD_S if use_cockpit else 0.0
    lead_ms = round(lead * 1000)
    clips = placed_clips(scenario, lead_ms)
    overlays = build_cockpit(scenario, clips, prefix, lead=lead,
                             caption_audio=caption_audio or audio_dir,
                             estimate=audio_dir is None,
                             srt=output.with_suffix(".srt")) if use_cockpit else None
    plan = RenderPlan(
        duration=int(scenario["durationSeconds"]), layout=layout, driver_raw=raw_video,
        passenger_raw=passenger_raw, passenger_start=passenger_start,
        passenger_still=passenger_still, chapters=chapters,
        hold_from=hold_ms / 1000 if hold_ms is not None else None, audio_dir=audio_dir,
        clips=clips, cues=placed_cues(scenario, lead_ms), preamble=preamble,
        outro=outro, disclaimer=note, cockpit=overlays, lead=lead)
    evidence = {
        "totalSeconds": plan.total(),
        "preambleSeconds": preamble_seconds(scenario),
        "timelineSeconds": int(scenario["durationSeconds"]),
        "outroSeconds": outro_seconds(scenario),
        "preamble": preamble_evidence,
        "outro": {"sha256": sha256(outro[0]), "beats": outro_evidence} if outro else None,
        "disclaimer": {"image": scenario["disclaimer"]["image"], "sha256": sha256(note),
                       "text": scenario["disclaimer"]["text"]} if note else None,
        "cockpit": cockpit_evidence(overlays, lead) if overlays else None,
    }
    return plan, evidence


def cockpit_evidence(overlays: dict, lead: float) -> dict:
    lay = overlays["layout"]
    root = pathlib.Path(lay["root"])
    intro = overlays.get("intro") or {}
    return {
        "frame": {"image": lay["frame"], "sha256": sha256(root / lay["frame"])},
        "boxes": {"driver": lay["driver"], "passenger": lay["passenger"],
                  "captionBand": lay["captionBand"], "railBand": lay["railBand"]},
        "intro": {"leadSeconds": lead, "holdSeconds": intro.get("fadeStartS"),
                  "endsSeconds": intro.get("endS"), "frames": intro.get("frames"),
                  "screens": {name: sha256(root / path) for name, path in
                              lay.get("intro", {}).items()}} if intro else None,
        "captions": len(overlays["captions"]), "railStates": len(overlays["rail"]),
        "srt": overlays.get("srt"), "estimatedCaptionClips": overlays.get("estimatedClips", []),
    }


def render_only(scenario: dict, args: argparse.Namespace) -> int:
    """Compose an earlier capture without a device: post-production, render and validation."""
    layout = layout_of(scenario)
    prefix = args.from_raw or args.output.with_name(args.output.stem)
    raw_video = prefix.with_name(prefix.name + ".raw.mp4")
    passenger_raw = prefix.with_name(prefix.name + ".passenger.raw.mp4")
    still = prefix.with_name(prefix.name + ".passenger-idle.png")
    if not raw_video.is_file():
        raise DemoFailure(f"no raw driver recording at {raw_video}")
    start = args.passenger_start
    if start is None:
        start = next((int(a["atMs"]) / 1000 for a in sorted(
            scenario["actions"], key=lambda item: int(item["atMs"]))
            if a["type"] == "passenger"), 0.0)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    plan, post = post_production(
        scenario, layout=layout, output=args.output, raw_video=raw_video,
        passenger_raw=passenger_raw if layout == "dual" and passenger_raw.is_file() else None,
        passenger_start=start, passenger_still=still if still.is_file() else None,
        audio_dir=None if args.no_mux else args.audio_dir, cockpit=not args.no_cockpit,
        caption_audio=args.audio_dir)
    began = time.monotonic()
    render(plan, args.output)
    seconds = time.monotonic() - began
    validation = validate_video(args.output, plan.total(), not args.no_mux,
                                CANVAS if layout == "dual" else None)
    print(f"PASS: {args.output} ({validation['durationSeconds']:.1f}s = intro lead "
          f"{plan.lead:g} + preamble {post['preambleSeconds']:g} + timeline "
          f"{post['timelineSeconds']} + outro {post['outroSeconds']:g}; rendered in "
          f"{seconds:.0f}s; {validation['distinctContentFrames']} distinct content frames)")
    if post.get("cockpit"):
        print(f"      cockpit: {post['cockpit']['captions']} captions, "
              f"{post['cockpit']['railStates']} rail states; SRT {post['cockpit']['srt']}")
    return 0


def resolve_layout(scenario: dict, passenger_display: str | None) -> str:
    layout = layout_of(scenario)
    if layout == "dual" and not passenger_display:
        raise DemoFailure(f"{scenario['id']} records two displays; pass --passenger-display-id")
    if layout == "single" and passenger_display:
        raise DemoFailure(f"{scenario['id']} records one display; drop --passenger-display-id")
    if layout == "dual":
        for path in LABELS.values():
            if not path.is_file():
                raise DemoFailure(f"missing panel label {path.relative_to(ROOT)}")
    return layout


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=[item["id"] for item in manifest["scenarios"]])
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--display-id",
                        help="driver physical SurfaceFlinger display id; never inferred "
                             "(required unless --render-only)")
    parser.add_argument("--passenger-display-id",
                        help="passenger physical display id, for dual-layout scenarios")
    parser.add_argument("--passenger-launch-display", default="2",
                        help="logical display id the passenger activity starts on (dual layout)")
    parser.add_argument("--require-avd", default="AAOS_35_1408p_GoogleAPIs")
    parser.add_argument("--audio-dir", type=pathlib.Path, default=AUDIO_DIR,
                        help="content-addressed TTS/STT cache (defaults to the ElevenLabs cache)")
    parser.add_argument("--allow-scripted-transcript", action="store_true",
                        help="engineering preflight only: inject expected text without STT evidence")
    parser.add_argument("--no-mux", action="store_true",
                        help="engineering preflight only: compose without the narration mix")
    parser.add_argument("--render-only", action="store_true",
                        help="no device: compose an earlier capture (<prefix>.raw.mp4, "
                             ".passenger.raw.mp4, .passenger-idle.png) into <output>")
    parser.add_argument("--from-raw", type=pathlib.Path,
                        help="with --render-only: the capture's path prefix (default: output's)")
    parser.add_argument("--passenger-start", type=float,
                        help="with --render-only: second the passenger stream began (default: "
                             "the first passenger action)")
    parser.add_argument("--no-cockpit", action="store_true",
                        help="compose a dual recording side by side instead of in the cockpit")
    args = parser.parse_args()

    scenario = next(item for item in manifest["scenarios"] if item["id"] == args.scenario)
    if args.render_only:
        return render_only(scenario, args)
    if not args.display_id:
        parser.error("--display-id is required to record")
    layout = resolve_layout(scenario, args.passenger_display_id)
    dual = layout == "dual"
    chapters = chapter_cards(scenario)
    hold_ms = scenario.get("endCardHoldFromMs")
    avd_name = verify_preconditions(args.require_avd)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    raw_video = args.output.with_name(args.output.stem + ".raw.mp4")
    passenger_raw = args.output.with_name(args.output.stem + ".passenger.raw.mp4")
    still = args.output.with_name(args.output.stem + ".passenger-idle.png")

    set_car_state("park")
    if any(action["type"] == "vehicleSignal" for action in scenario["actions"]):
        # Host B reads EV_BATTERY_LEVEL only with this runtime permission (VehicleEnergy).
        adb_shell("pm", "grant", PACKAGE, "android.car.permission.CAR_ENERGY", check=False)
    launch(args.passenger_launch_display if layout == "dual" else None)
    if dual:
        broadcast(ACTION_PASSENGER, "--es", EXTRA_MEDIA, "stop")
    adb("shell", "rm", "-f", DEVICE_VIDEO, DEVICE_PASSENGER_VIDEO, check=False)
    evidence = run_timeline(scenario, Adb(), Clock(), driver_display=args.display_id,
                            passenger_display=args.passenger_display_id,
                            audio_dir=args.audio_dir,
                            allow_scripted=args.allow_scripted_transcript, still_path=still)

    adb("pull", DEVICE_VIDEO, str(raw_video))
    if evidence["passengerStartSeconds"] is not None:
        adb("pull", DEVICE_PASSENGER_VIDEO, str(passenger_raw))
    adb("shell", "rm", "-f", DEVICE_VIDEO, DEVICE_PASSENGER_VIDEO, check=False)
    plan, post = post_production(
        scenario, layout=layout, output=args.output, raw_video=raw_video,
        passenger_raw=passenger_raw if evidence["passengerStartSeconds"] is not None else None,
        passenger_start=evidence["passengerStartSeconds"] or 0.0,
        passenger_still=evidence["passengerStill"],
        audio_dir=None if args.no_mux else args.audio_dir, cockpit=not args.no_cockpit,
        caption_audio=args.audio_dir)
    render(plan, args.output)
    validation = validate_video(args.output, plan.total(), not args.no_mux,
                                CANVAS if dual else None)
    commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    command = ["scripts/run-nyc-demo.py", scenario["id"], "<output>", "--display-id",
               args.display_id]
    if dual:
        command += ["--passenger-display-id", args.passenger_display_id]
    if args.allow_scripted_transcript:
        command.append("--allow-scripted-transcript")
    if args.no_mux:
        command.append("--no-mux")
    proves = ["Host B current-display re-projection", "parked interaction",
              "in-motion restriction transition"]
    if dual:
        proves.append("driver and passenger physical displays recorded side by side")
    metadata = {
        "schemaVersion": 1,
        "scenarioId": scenario["id"],
        "corpusCase": scenario["corpusCase"],
        "corpusSequence": scenario.get("corpusSequence", [scenario["corpusCase"]]),
        "commit": commit,
        "sourceState": source_state_evidence(),
        "installedApk": installed_package_evidence(),
        "host": "Host B",
        "package": PACKAGE,
        "avd": avd_name,
        "displayId": args.display_id,
        "layout": layout,
        "displays": {"driver": args.display_id, "passenger": args.passenger_display_id},
        "composition": {
            "canvas": list(CANVAS),
            "driverBox": plan.cockpit["layout"]["driver"] if plan.cockpit else list(DRIVER_BOX),
            "passengerBox": plan.cockpit["layout"]["passenger"] if plan.cockpit
            else list(PASSENGER_BOX),
            "cockpit": bool(plan.cockpit),
            "labels": {name: sha256(path) for name, path in LABELS.items()}
            if not plan.cockpit else None,
            "passengerStreamStartsAtSeconds": evidence["passengerStartSeconds"],
            "passengerIdleFrame": ("screencap before the passenger stream"
                                   if evidence["passengerStill"] else "unavailable; dark panel"),
            "rawDurationSeconds": {
                "driver": probe_duration(raw_video),
                "passenger": probe_duration(passenger_raw)
                if evidence["passengerStartSeconds"] is not None else None},
        } if dual else None,
        "chapterCards": [{"image": spec["image"], "sha256": sha256(card.image),
                          "atMs": round(card.at * 1000), "seconds": card.seconds,
                          "fadeIn": card.fade_in, "fadeOut": card.fade_out}
                         for spec, card in zip(scenario.get("chapterCards", []), chapters)],
        "endCardHoldFromMs": hold_ms,
        "postProduction": post,
        "restrictionSource": "live car_service park→drive (no DEMO substitution)",
        "command": " ".join(command),
        "manifestSha256": sha256(MANIFEST),
        "runnerSha256": sha256(pathlib.Path(__file__).resolve()),
        "contextSnapshot": scenario.get("contextSnapshot"),
        "contextSnapshotSha256": sha256(ROOT / scenario["contextSnapshot"]),
        "agentProfiles": scenario.get("agentProfiles"),
        "agentProfilesSha256": sha256(ROOT / scenario["agentProfiles"])
            if scenario.get("agentProfiles") else None,
        "transcripts": evidence["transcripts"],
        "vehicleSignals": evidence["vehicleSignals"],
        "timeline": evidence["steps"],
        "audioTrack": "none — engineering preflight" if args.no_mux else
            "ElevenLabs timed dialogue and sound-effects mix",
        "soundEffects": [{
            "effectId": cue["effectId"],
            "atMs": cue["atMs"],
            "gainDb": cue["gainDb"],
            "audioSha256": sha256(args.audio_dir / "sfx" / f"{cue['effectId']}.mp3"),
            "metadataSha256": sha256(args.audio_dir / "sfx"
                                      / f"{cue['effectId']}.sfx.json"),
        } for cue in scenario.get("soundEffects", [])] if not args.no_mux else [],
        "validation": validation,
        "claims": {
            "proves": proves,
            "doesNotProve": scenario["doNotClaim"],
        },
    }
    metadata_path = args.output.with_suffix(args.output.suffix + ".metadata.json")
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True, default=str) + "\n",
                             encoding="utf-8")
    print(f"PASS: {args.output} ({validation['durationSeconds']:.1f}s, "
          f"{validation['distinctContentFrames']} distinct content frames)")
    print(f"      metadata: {metadata_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
