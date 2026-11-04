#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Run and record one timed NYC Host B scenario on a named AAOS display."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
AUDIO_DIR = ROOT / "a2ui-automotive/demo/audio/elevenlabs"
PACKAGE = "art.dart.a2ui.carui"
ACTIVITY = "art.dart.a2ui.carui.A2uiCarUiActivity"
ACTION_SET = "art.dart.a2ui.demo.action.SET"
EXTRA_ACTIVE = "art.dart.a2ui.demo.extra.ACTIVE"
ACTION_CASE = "art.dart.a2ui.carapp.action.CASE"
EXTRA_CASE = "art.dart.a2ui.carapp.extra.CASE_ID"
ACTION_SPEAK = "art.dart.a2ui.carui.action.SPEAK"
EXTRA_PHRASE = "art.dart.a2ui.carui.extra.PHRASE"
DEVICE_VIDEO = "/sdcard/a2ui-nyc-demo.mp4"


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


def launch() -> None:
    # Reset retained Activity state so every recording begins on the same base surface. Selecting
    # the scenario happens only after screenrecord starts; otherwise Android may use the first
    # interaction 10–15 seconds later as PTS zero and silently truncate the narration timeline.
    adb_shell("am", "force-stop", "--user", "current", PACKAGE)
    adb("shell", "am", "start", "--user", "current", "-n", f"{PACKAGE}/{ACTIVITY}")
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
    if data.get("transcriptNormalized") != expected.casefold().strip():
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


def validate_video(path: pathlib.Path, expected_duration: int, require_audio: bool) -> dict:
    data = path.read_bytes()
    if len(data) < 100_000 or data[4:8] != b"ftyp" or b"moov" not in data:
        raise DemoFailure("recording is missing MP4 ftyp/moov structure or is implausibly small")
    probe = json.loads(run(["ffprobe", "-v", "error", "-show_entries",
                            "format=duration:stream=codec_type,codec_name,sample_rate,channels,"
                            "width,height", "-of", "json", str(path)]).stdout)
    duration = float(probe["format"]["duration"])
    # A 90-second `-t` cap can end one encoded frame past 90.000 because the container duration is
    # frame-based (observed 90.019934 at 60 Hz). Keep a sub-frame/container tolerance only.
    if not 60 <= duration <= 90.1:
        raise DemoFailure(f"recording duration {duration:.1f}s is outside the 60–90s target")
    # screenrecord may omit runs of identical frames at either end, but the last transition must
    # land near the scripted target.
    if not expected_duration - 15 <= duration <= min(90.1, expected_duration + 8):
        raise DemoFailure(f"recording duration {duration:.1f}s is too far from its "
                          f"{expected_duration}s scripted target")
    hashes = run(["ffmpeg", "-v", "error", "-i", str(path), "-vf",
                  "crop=iw:ih*0.76:0:ih*0.12", "-f", "framehash", "-"]).stdout
    distinct = len({line.split()[-1] for line in hashes.splitlines() if line and line[0] != "#"})
    if distinct < 5:
        raise DemoFailure(f"recording has only {distinct} distinct cropped content frames")
    video_streams = [stream for stream in probe.get("streams", [])
                     if stream.get("codec_type") == "video"]
    audio_streams = [stream for stream in probe.get("streams", [])
                     if stream.get("codec_type") == "audio"]
    if len(video_streams) != 1 or video_streams[0].get("codec_name") != "h264":
        raise DemoFailure("recording must contain exactly one H.264 video stream")
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


def mux_audio(audio_dir: pathlib.Path, raw_video: pathlib.Path,
              final_video: pathlib.Path, clips: list[dict], cues: list[dict],
              target_seconds: int) -> None:
    speech_inputs = [str(audio_dir / f"{clip['id']}.mp3") for clip in clips]
    effect_inputs = [str(audio_dir / "sfx" / f"{cue['effectId']}.mp3") for cue in cues]
    inputs = speech_inputs + effect_inputs
    missing = [path for path in inputs if not pathlib.Path(path).exists()]
    if missing:
        raise DemoFailure(f"missing {len(missing)} TTS clip(s); prepare the ElevenLabs cache first")
    command = ["ffmpeg", "-y", "-v", "error", "-i", str(raw_video)]
    for path in inputs:
        command.extend(["-i", path])
    # Clone the last frame to the manifest duration: screenrecord is variable-frame-rate and may
    # end its timeline at the last UI change.
    filters = [f"[0:v]fps=30,tpad=stop_mode=clone:stop_duration={target_seconds},"
               f"trim=duration={target_seconds}[v]"]  # CFR + trim: -t alone overshot by ~0.5s
    labels = []
    for index, clip in enumerate(clips, start=1):
        label = f"a{index}"
        filters.append(f"[{index}:a]adelay=delays={int(clip['atMs'])}:all=1[{label}]")
        labels.append(f"[{label}]")
    for offset, cue in enumerate(cues, start=1 + len(clips)):
        label = f"a{offset}"
        filters.append(f"[{offset}:a]volume={float(cue['gainDb']):g}dB,"
                       f"adelay=delays={int(cue['atMs'])}:all=1[{label}]")
        labels.append(f"[{label}]")
    filters.append("".join(labels) + f"amix=inputs={len(labels)}:duration=longest:normalize=0,"
                   f"atrim=duration={target_seconds}[a]")
    command.extend(["-filter_complex", ";".join(filters), "-map", "[v]", "-map", "[a]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                    "-t", str(target_seconds), "-movflags", "+faststart",
                    str(final_video)])
    run(command, timeout=180)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=[
        "nyc_poi_order", "nyc_curated_video", "nyc_astra_concierge"])
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--display-id", required=True,
                        help="physical SurfaceFlinger display id; never inferred")
    parser.add_argument("--require-avd", default="AAOS_35_1408p_GoogleAPIs")
    parser.add_argument("--audio-dir", type=pathlib.Path, default=AUDIO_DIR,
                        help="content-addressed TTS/STT cache (defaults to the ElevenLabs cache)")
    parser.add_argument("--allow-scripted-transcript", action="store_true",
                        help="engineering preflight only: inject expected text without STT evidence")
    parser.add_argument("--no-mux", action="store_true",
                        help="engineering preflight only: retain a silent screen recording")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    scenario = next(item for item in manifest["scenarios"] if item["id"] == args.scenario)
    avd_name = verify_preconditions(args.require_avd)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    raw_video = args.output.with_name(args.output.stem + ".raw.mp4")
    transcript_evidence = []
    clips_by_id = {clip["id"]: clip for clip in scenario["clips"]}

    set_car_state("park")
    launch()
    adb("shell", "rm", "-f", DEVICE_VIDEO, check=False)
    recorder = subprocess.Popen(["adb", "shell", "screenrecord", "--display-id",
                                 args.display_id, "--time-limit", "90", DEVICE_VIDEO],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    started = time.monotonic()
    # This is the first deliberate content mutation in the recording, anchoring media PTS near the
    # same zero used by the manifest and the later ElevenLabs audio mix.
    broadcast(ACTION_CASE, "--es", EXTRA_CASE, scenario["corpusCase"])
    time.sleep(2)
    if recorder.poll() is not None:
        diagnostic = recorder.stderr.read().strip() if recorder.stderr else ""
        raise DemoFailure(f"screenrecord did not start: {diagnostic}")
    try:
        for action in sorted(scenario["actions"], key=lambda item: item["atMs"]):
            due = started + action["atMs"] / 1000
            time.sleep(max(0, due - time.monotonic()))
            if action["type"] == "speak":
                clip = clips_by_id[action["clipId"]]
                phrase, transcript_path = transcript_for(
                    args.audio_dir, action["clipId"], clip["expectedTranscript"],
                    args.allow_scripted_transcript)
                broadcast(ACTION_SPEAK, "--es", EXTRA_PHRASE, phrase)
                transcript_evidence.append({
                    "clipId": action["clipId"],
                    "source": "elevenlabs-stt-cache" if transcript_path else "scripted-preflight",
                    "sha256": sha256(transcript_path) if transcript_path else None,
                })
            elif action["type"] == "tapText":
                tap_text(action["text"])
            elif action["type"] == "carState":
                set_car_state(action["state"])
            elif action["type"] == "selectCase":
                broadcast(ACTION_CASE, "--es", EXTRA_CASE, action["caseId"])
                time.sleep(2)
            elif action["type"] == "keyEvent" and action.get("key") == "BACK":
                adb("shell", "input", "keyevent", "4")
            else:
                raise DemoFailure(f"unknown action type {action['type']}")
        time.sleep(max(0, started + scenario["durationSeconds"] - time.monotonic()))
    finally:
        adb("shell", "pkill", "-INT", "screenrecord", check=False)
        try:
            recorder.wait(timeout=20)
        except subprocess.TimeoutExpired:
            recorder.send_signal(signal.SIGINT)
            recorder.wait(timeout=10)
        set_car_state("park")

    adb("pull", DEVICE_VIDEO, str(raw_video))
    adb("shell", "rm", "-f", DEVICE_VIDEO, check=False)
    if args.no_mux:
        raw_video.replace(args.output)
    else:
        mux_audio(args.audio_dir, raw_video, args.output, scenario["clips"],
                  scenario.get("soundEffects", []),
                  scenario["durationSeconds"])
    validation = validate_video(args.output, scenario["durationSeconds"], not args.no_mux)
    commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    command = ["scripts/run-nyc-demo.py", scenario["id"], "<output>", "--display-id",
               args.display_id]
    if args.allow_scripted_transcript:
        command.append("--allow-scripted-transcript")
    if args.no_mux:
        command.append("--no-mux")
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
        "restrictionSource": "live car_service park→drive (no DEMO substitution)",
        "command": " ".join(command),
        "manifestSha256": sha256(MANIFEST),
        "runnerSha256": sha256(pathlib.Path(__file__).resolve()),
        "contextSnapshot": scenario.get("contextSnapshot"),
        "contextSnapshotSha256": sha256(ROOT / scenario["contextSnapshot"]),
        "agentProfiles": scenario.get("agentProfiles"),
        "agentProfilesSha256": sha256(ROOT / scenario["agentProfiles"])
            if scenario.get("agentProfiles") else None,
        "transcripts": transcript_evidence,
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
            "proves": ["Host B current-display re-projection", "parked interaction",
                       "in-motion restriction transition"],
            "doesNotProve": scenario["doNotClaim"],
        },
    }
    metadata_path = args.output.with_suffix(args.output.suffix + ".metadata.json")
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n",
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
