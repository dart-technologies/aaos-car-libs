#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Run and record one timed NYC Host B scenario on one AAOS display, or two side by side.

A scenario whose manifest entry says "recording": {"layout": "dual"} records the driver and the
passenger physical displays with two screenrecord streams and composes them on a 1920x1080 canvas
(driver larger, left; passenger right; each labelled). Everything else records one display, as
before. Either may open on a title card and hold its end card; the output is exactly the
manifest's duration (60–180 s).
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
# Side-by-side canvas, both panels 16:9: driver larger on the left, passenger on the right.
CANVAS = (1920, 1080)
DRIVER_BOX = (24, 186, 1344, 756)  # 16:9 driver and 2:3 passenger, same height
PASSENGER_BOX = (1392, 186, 504, 756)
LABEL_GAP = 12
BACKGROUND = "0x0b0f14"
LABELS = {"driver": CARDS / "label-driver.png", "passenger": CARDS / "label-passenger.png"}
TITLE_FADE_S = 0.4
STILL_LEAD_MS = 3000  # capture the idle passenger frame this long before its stream starts


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
    result = subprocess.run(command, text=True, capture_output=capture, timeout=timeout)
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


@dataclass
class RenderPlan:
    duration: int
    layout: str = "single"
    driver_raw: pathlib.Path = pathlib.Path()
    driver_size: tuple[int, int] | None = None  # needed for a single-display title card
    passenger_raw: pathlib.Path | None = None
    passenger_start: float = 0.0
    passenger_still: pathlib.Path | None = None
    title: pathlib.Path | None = None
    title_seconds: float = 0.0
    hold_from: float | None = None
    audio_dir: pathlib.Path | None = None  # None renders silently
    clips: list = field(default_factory=list)
    cues: list = field(default_factory=list)
    labels: dict = field(default_factory=lambda: dict(LABELS))


def legacy(plan: RenderPlan) -> bool:
    return plan.layout == "single" and plan.title is None and plan.hold_from is None


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
    """The single ffmpeg invocation that composes video, mixes timed audio and trims exactly."""
    target = plan.duration
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
            raise DemoFailure("a single-display title card needs the recording's size")
        filters.append(stream(0, target, plan.hold_from) + "[base]")
        last = "[base]"
    else:
        start = round(plan.passenger_start * FPS) / FPS
        hold = plan.hold_from
        filters.append(f"color=c={BACKGROUND}:s={CANVAS[0]}x{CANVAS[1]}:r={FPS}:d={target}[bg]")
        filters.append(fit(stream(0, target, hold) + ",", DRIVER_BOX, "[drv]"))
        rec_end = None if hold is None else hold - start
        record = plan.passenger_raw is not None and start < target and (
            rec_end is None or rec_end > 0)
        idle = target if not record else start
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
            filters.append(fit(stream(count, target - start, rec_end) + ",", PASSENGER_BOX,
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
        if plan.title is not None:
            seconds = plan.title_seconds
            command += ["-loop", "1", "-framerate", str(FPS), "-t", f"{seconds:.3f}",
                        "-i", str(plan.title)]
            filters.append(f"[{count}:v]scale={width}:{height}:force_original_aspect_ratio="
                           f"decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:"
                           f"color={BACKGROUND},format=rgba,fade=t=out:"
                           f"st={seconds - TITLE_FADE_S:.3f}:d={TITLE_FADE_S}:alpha=1[title]")
            filters.append(f"{last}[title]overlay=0:0:eof_action=pass:repeatlast=0[titled]")
            last = "[titled]"
            count += 1
        filters.append(f"{last}format=yuv420p[v]")
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
        filters.append("".join(labels) + f"amix=inputs={len(labels)}:duration=longest:"
                       f"normalize=0,atrim=duration={target}[a]")
        maps += ["-map", "[a]"]
    command += ["-filter_complex", ";".join(filters), *maps,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"]
    command += ["-c:a", "aac", "-b:a", "160k"] if plan.audio_dir is not None else ["-an"]
    command += ["-t", str(target), "-movflags", "+faststart", str(output)]
    return command


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
    if plan.layout == "single" and not legacy(plan) and plan.driver_size is None:
        plan.driver_size = probe_size(plan.driver_raw)
    run(render_command(plan, output), timeout=900)


def check_duration(duration: float, expected: int) -> None:
    # Container duration is frame/AAC-packet based (observed 90.019934 for a 90 s cap): keep a
    # sub-frame tolerance only.
    if not MIN_S <= duration <= MAX_S + 0.1:
        raise DemoFailure(f"recording duration {duration:.1f}s is outside the {MIN_S}–{MAX_S}s "
                          "target")
    if abs(duration - expected) > 0.1:
        raise DemoFailure(f"recording duration {duration:.3f}s is not the manifest's "
                          f"{expected}s")


def validate_video(path: pathlib.Path, expected_duration: int, require_audio: bool,
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


def title_card(scenario: dict) -> tuple[pathlib.Path | None, float]:
    card = scenario.get("titleCard")
    if not card:
        return None, 0.0
    path = ROOT / card["image"]
    seconds = float(card["seconds"])
    if not path.is_file() or path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        raise DemoFailure(f"title card {card['image']} is missing or not a PNG")
    if not 2 <= seconds <= 3:
        raise DemoFailure("a title card holds for 2–3 s")
    return path, seconds


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
    parser.add_argument("--display-id", required=True,
                        help="driver physical SurfaceFlinger display id; never inferred")
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
    args = parser.parse_args()

    scenario = next(item for item in manifest["scenarios"] if item["id"] == args.scenario)
    layout = resolve_layout(scenario, args.passenger_display_id)
    dual = layout == "dual"
    title, title_seconds = title_card(scenario)
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
    plan = RenderPlan(
        duration=int(scenario["durationSeconds"]), layout=layout, driver_raw=raw_video,
        passenger_raw=passenger_raw if evidence["passengerStartSeconds"] is not None else None,
        passenger_start=evidence["passengerStartSeconds"] or 0.0,
        passenger_still=evidence["passengerStill"], title=title, title_seconds=title_seconds,
        hold_from=hold_ms / 1000 if hold_ms is not None else None,
        audio_dir=None if args.no_mux else args.audio_dir, clips=scenario["clips"],
        cues=scenario.get("soundEffects", []))
    render(plan, args.output)
    validation = validate_video(args.output, scenario["durationSeconds"], not args.no_mux,
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
            "canvas": list(CANVAS), "driverBox": list(DRIVER_BOX),
            "passengerBox": list(PASSENGER_BOX),
            "labels": {name: sha256(path) for name, path in LABELS.items()},
            "passengerStreamStartsAtSeconds": evidence["passengerStartSeconds"],
            "passengerIdleFrame": ("screencap before the passenger stream"
                                   if evidence["passengerStill"] else "unavailable; dark panel"),
            "rawDurationSeconds": {
                "driver": probe_duration(raw_video),
                "passenger": probe_duration(passenger_raw)
                if evidence["passengerStartSeconds"] is not None else None},
        } if dual else None,
        "titleCard": {"image": scenario["titleCard"]["image"], "sha256": sha256(title),
                      "seconds": title_seconds} if title else None,
        "endCardHoldFromMs": hold_ms,
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
