#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Static acceptance checks for the modular NYC 60–180 second demo fixtures."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import subprocess
import sys
import urllib.parse
from datetime import date, datetime, timedelta


ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
AUDIO = ROOT / "a2ui-automotive/demo/audio/elevenlabs"
CORPUS = ROOT / "a2ui-model/src/main/java/art/dart/a2ui/demo/UseCaseCorpus.java"
CONTEXTS = ROOT / "a2ui-automotive/demo/context"
CARDS = ROOT / "a2ui-automotive/demo/cards"
PROFILES = ROOT / "a2ui-automotive/demo/agent-profiles-v1.json"
RUNNER = ROOT / "scripts/run-nyc-demo.py"
# The three original stories and their corpus cases; more may be added beside them.
CANONICAL = {"nyc_poi_order": "poi_discovery_order", "nyc_curated_video": "big_buck_bunny",
             "nyc_concierge": "poi_discovery_order"}
ACTIONS = {"speak", "tapText", "carState", "keyEvent", "selectCase", "agent", "passenger",
           "endCard", "vehicleSignal"}
PASSENGER_MEDIA = {"video", "artwork", "stop"}
VEHICLE_ROUTES = {"vhal-set", "vhal-inject", "broadcast"}
WEATHER_HOSTS = {"forecast.weather.gov", "api.weather.gov"}


def normalized(text: str) -> str:
    return " ".join(re.findall(r"[\w']+", text.casefold(), flags=re.UNICODE))


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def timestamp(value: object, what: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value))
    except ValueError:
        fail(f"{what} is not an ISO-8601 time")
    if parsed.tzinfo is None:
        fail(f"{what} has no UTC offset")
    return parsed


def check_context(scenario_id: str, relative: str) -> None:
    """Every scenario's context is a dated, curated, non-device snapshot with sources."""
    path = (ROOT / relative).resolve()
    if path.parent != CONTEXTS.resolve() or not path.is_file():
        fail(f"{scenario_id} context {relative!r} is not a file in {CONTEXTS.relative_to(ROOT)}")
    context = json.loads(path.read_text(encoding="utf-8"))
    name = path.name
    if context.get("schemaVersion") != 1 or context.get("mode") != "curated_snapshot":
        fail(f"{name} must stay a clearly labelled curated snapshot")
    origin = context.get("origin", {})
    if origin.get("isDeviceLocation") is not False or origin.get("precision") not in {
            "neighborhood", "landmark", "city"}:
        fail(f"{name} origin must be a coarse, non-device location")
    captured = timestamp(context.get("capturedAt"), f"{name} capturedAt")
    weather = context.get("weather", {})
    source = urllib.parse.urlparse(str(weather.get("source", "")))
    if source.scheme != "https" or source.hostname not in WEATHER_HOSTS:
        fail(f"{name} weather must cite a weather.gov forecast")
    timestamp(weather.get("forecastIssuedAt"), f"{name} forecastIssuedAt")
    if not weather.get("dayForecast"):
        fail(f"{name} has no forecast text")
    commerce = context.get("commerce", {})
    if commerce.get("merchantStatus") not in {"fictional_demo", "none"}:
        fail(f"{name} merchant is neither explicitly fictional nor absent")
    if commerce.get("paymentStatus") != "not_connected":
        fail(f"{name} must not connect a payment")
    # A story set on a later day uses that day's forecast period and says which day it is.
    day = captured.date()
    if weather.get("forecastFor") is not None:
        try:
            day = date.fromisoformat(str(weather["forecastFor"]))
        except ValueError:
            fail(f"{name} forecastFor is not an ISO date")
        if not captured.date() <= day <= captured.date() + timedelta(days=6):
            fail(f"{name} forecastFor is outside the forecast issued at capture")
    freshness = context.get("freshness", {})
    label = str(freshness.get("displayLabel", ""))
    if not label.startswith("CURATED SNAPSHOT") or f"{day:%b} {day.day}".upper() not in label:
        fail(f"{name} display label must say CURATED SNAPSHOT and the day it describes")
    if timestamp(freshness.get("liveRefreshRequiredAfter"),
                 f"{name} liveRefreshRequiredAfter") <= captured or not freshness.get("policy"):
        fail(f"{name} needs a refresh deadline after capture and a relabelling policy")


def corpus_ids() -> set[str]:
    return set(re.findall(r'new Builder\(\s*"([a-z_]+)"', CORPUS.read_text(encoding="utf-8")))


def audio_duration(path: pathlib.Path) -> float:
    result = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                             "-of", "csv=p=0", str(path)], text=True,
                            capture_output=True, timeout=30)
    if result.returncode:
        fail(f"could not measure audio duration for {path.name}")
    return float(result.stdout.strip())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-audio", action="store_true")
    args = parser.parse_args()
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or data.get("creditBudget") != 10000:
        fail("manifest version/budget drift")
    personas = data.get("tts", {}).get("personas", {})
    if set(personas) != {"navigator", "guest"}:
        fail("the two canonical speech personas are not exact")
    if personas["navigator"].get("role") != "narration" or personas["guest"].get(
            "role") != "user_utterance":
        fail("speech personas no longer map exactly to narrator and guest roles")
    if personas["navigator"].get("voiceId") == personas["guest"].get("voiceId"):
        fail("navigator and guest must use distinct voices")
    if {personas[name].get("genderPresentation") for name in personas} != {"female", "male"}:
        fail("persona contrast must preserve distinct gender presentation")
    if len({personas[name].get("accentIntent") for name in personas}) != 2:
        fail("persona contrast must preserve distinct accent intent")
    if not (personas["navigator"].get("voiceSettings", {}).get("speed", 1) < 1
            < personas["guest"].get("voiceSettings", {}).get("speed", 1)):
        fail("navigator and guest delivery speeds no longer provide audible contrast")
    sfx = data.get("sfx", {})
    if (sfx.get("modelId") != "eleven_text_to_sound_v2"
            or sfx.get("outputFormat") != "mp3_44100_128"):
        fail("sound-effects model/output contract drifted")
    effects = {effect.get("id"): effect for effect in sfx.get("effects", [])}
    if set(effects) != {"fifth_house_welcome", "fifth_house_confirmed",
                       "fifth_house_focus", "fifth_house_arrival"}:
        fail("the four canonical hospitality sound effects are not exact")
    if None in effects or len(effects) != len(sfx.get("effects", [])):
        fail("sound-effect ids must be unique and non-empty")
    scenarios = data.get("scenarios", [])
    ids = [s.get("id") for s in scenarios]
    if len(set(ids)) != len(ids) or not all(re.fullmatch(r"[a-z][a-z0-9_]*", str(i))
                                            for i in ids):
        fail("scenario ids must be unique snake_case")
    by_id = {s["id"]: s for s in scenarios}
    for scenario_id, case in CANONICAL.items():
        if by_id.get(scenario_id, {}).get("corpusCase") != case:
            fail(f"canonical scenario {scenario_id} is missing or no longer maps to {case}")
    declared = corpus_ids()
    for scenario in scenarios:
        for case in [scenario.get("corpusCase"), *scenario.get("corpusSequence", [])]:
            if case not in declared:
                fail(f"{scenario['id']} names corpus case {case!r}, which UseCaseCorpus does "
                     "not declare")
    profiles = json.loads(PROFILES.read_text(encoding="utf-8"))
    if profiles.get("selectors") != ["claude", "chatgpt", "gemini", "grok", "muse"]:
        fail("provider swap selectors drifted")
    foreground = profiles.get("showcaseRouting", {}).get("foreground", {})
    background = profiles.get("showcaseRouting", {}).get("background", {})
    if foreground.get("model") != "haiku" or foreground.get("effort") != "low":
        fail("foreground latency profile is not Haiku low")
    if (background.get("profile") != "quality_cached"
            or background.get("background") is not True
            or background.get("directVehicleActions") is not False):
        fail("background route lost its staged, non-actioning boundary")
    runner = RUNNER.read_text(encoding="utf-8")
    if "tpad=stop_mode=clone" not in runner or '"-shortest"' in runner:
        fail("recorder would truncate timed narration at screenrecord's last changed frame")

    clip_ids: set[str] = set()
    clip_texts: dict[str, str] = {}
    tts_chars = 0
    placeholders = 0
    for scenario in scenarios:
        duration = int(scenario.get("durationSeconds", 0))
        if not 60 <= duration <= 180:
            fail(f"{scenario['id']} is outside the 60–180 second target")
        if not scenario.get("doNotClaim"):
            fail(f"{scenario['id']} has no explicit claim boundary")
        if not scenario.get("parameterRationale"):
            fail(f"{scenario['id']} has no parameter rationale")
        check_context(scenario["id"], scenario.get("contextSnapshot", ""))
        layout = scenario.get("recording", {}).get("layout", "single")
        if layout not in {"single", "dual"}:
            fail(f"{scenario['id']} has an unknown recording layout")
        card = scenario.get("titleCard")
        if card is not None:
            image = (ROOT / str(card.get("image", ""))).resolve()
            if image.parent != CARDS.resolve() or not image.is_file() or \
                    image.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
                fail(f"{scenario['id']} title card is not a PNG in {CARDS.relative_to(ROOT)}")
            if not 2 <= float(card.get("seconds", 0)) <= 3:
                fail(f"{scenario['id']} title card must hold for 2–3 s")
        last_action = -1
        clips = {clip["id"]: clip for clip in scenario.get("clips", [])}
        for clip in clips.values():
            if clip["id"] in clip_ids:
                fail(f"duplicate clip id {clip['id']}")
            clip_ids.add(clip["id"])
            clip_texts[clip["id"]] = clip.get("text", "")
            if clip.get("role") not in {"narration", "user_utterance"}:
                fail(f"unknown role on {clip['id']}")
            if not 0 <= int(clip.get("atMs", -1)) < duration * 1000:
                fail(f"clip {clip['id']} is outside its scenario timeline")
            tts_chars += len(clip.get("text", ""))
            placeholders += clip.get("placeholder") is True
            if clip["role"] == "user_utterance" and not clip.get("expectedTranscript"):
                fail(f"utterance {clip['id']} has no expected transcript")
        for action in scenario.get("actions", []):
            at = int(action.get("atMs", -1))
            if at < last_action or at >= duration * 1000:
                fail(f"{scenario['id']} actions are unordered or outside the timeline")
            last_action = at
            if action.get("type") not in ACTIONS:
                fail(f"unsupported action {action.get('type')}")
            if action.get("type") == "agent" and (
                    action.get("provider") not in profiles["selectors"]
                    or not str(action.get("ask", "")).strip()):
                fail(f"agent action needs a known provider and a request: {action}")
            if action.get("type") == "passenger" and (
                    layout != "dual" or action.get("media") not in PASSENGER_MEDIA):
                fail(f"passenger action needs a dual-display recording and known media: {action}")
            if action.get("type") == "vehicleSignal" and (
                    action.get("signal") != "battery"
                    or not 0 <= float(action.get("percent", -1)) <= 100
                    or not action.get("via") or not set(action["via"]) <= VEHICLE_ROUTES):
                fail(f"vehicle signal needs battery percent 0–100 and known routes: {action}")
            if action.get("type") == "keyEvent" and action.get("key") != "BACK":
                fail(f"unsupported key event {action}")
            if action.get("type") == "speak":
                clip = clips.get(action.get("clipId"))
                if not clip or clip.get("role") != "user_utterance":
                    fail(f"speak action does not name an utterance: {action}")
            if action.get("type") == "selectCase" and action.get("caseId") not in scenario.get(
                    "corpusSequence", []):
                fail(f"case transition is outside the declared sequence: {action}")
        types = [action.get("type") for action in scenario.get("actions", [])]
        if layout == "dual" and "passenger" not in types:
            fail(f"{scenario['id']} records two displays but never addresses the passenger")
        hold = scenario.get("endCardHoldFromMs")
        if hold is not None:
            cards = [int(a["atMs"]) for a in scenario["actions"] if a.get("type") == "endCard"]
            if not cards or not min(cards) <= int(hold) < duration * 1000:
                fail(f"{scenario['id']} holds an end card it never shows, or past the end")
        if not scenario.get("soundEffects"):
            fail(f"{scenario['id']} has no sound design cues")
        last_cue = -1
        for cue in scenario["soundEffects"]:
            at = int(cue.get("atMs", -1))
            if at < last_cue or at >= duration * 1000:
                fail(f"{scenario['id']} sound cues are unordered or outside the timeline")
            last_cue = at
            if cue.get("effectId") not in effects:
                fail(f"{scenario['id']} references an unknown sound effect")
            if not -24 <= float(cue.get("gainDb", 0)) <= -6:
                fail(f"{scenario['id']} sound effect is not safely below dialogue")

    estimated_sfx = sum(math.ceil(float(effect["durationSeconds"])
                                  * int(sfx["estimatedCreditsPerSecond"]))
                        for effect in effects.values())
    if tts_chars + estimated_sfx > data["creditBudget"]:
        fail("TTS and estimated sound-effect usage exceed the approved budget")
    corpus = CORPUS.read_text(encoding="utf-8")
    for required in ["Hudson Yards", "Brooklyn Museum", "FICTIONAL DEMO venue",
                     "Fifth House Coffee Atelier", "Curated snapshot · Sep 7",
                     "Long Island City", '"voicePhrase", "order ahead"',
                     '"voicePhrase", "play audio"']:
        if required not in corpus:
            fail(f"corpus is missing scenario contract {required!r}")

    if args.require_audio:
        for clip_id in sorted(clip_ids):
            audio = AUDIO / f"{clip_id}.mp3"
            meta = AUDIO / f"{clip_id}.tts.json"
            if not audio.exists() or not meta.exists() or audio.stat().st_size < 256:
                fail(f"missing generated audio or metadata for {clip_id}")
            metadata = json.loads(meta.read_text(encoding="utf-8"))
            expected_persona = "navigator" if metadata.get("role") == "narration" else "guest"
            if (metadata.get("persona") != expected_persona
                    or metadata.get("voiceId") != personas[expected_persona]["voiceId"]):
                fail(f"persona or voice drift for {clip_id}")
            if metadata.get("audioSha256") != hashlib.sha256(audio.read_bytes()).hexdigest():
                fail(f"audio checksum mismatch for {clip_id}")
            if metadata.get("inputSha256") != hashlib.sha256(
                    clip_texts[clip_id].encode()).hexdigest():
                fail(f"stale audio for {clip_id}: its text changed; regenerate it")
        for scenario in scenarios:
            previous_end_ms = -1.0
            for clip in scenario["clips"]:
                if clip["role"] != "user_utterance":
                    continue
                path = AUDIO / "transcripts" / f"{clip['id']}.json"
                if not path.exists():
                    fail(f"missing STT fixture for {clip['id']}")
                transcript = json.loads(path.read_text(encoding="utf-8"))
                if normalized(transcript.get("transcript", "")) != normalized(
                        clip["expectedTranscript"]):
                    fail(f"STT fixture drift for {clip['id']}")
                if transcript.get("dispatchText") != clip["expectedTranscript"]:
                    fail(f"STT replay is not exactly the displayed phrase for {clip['id']}")
            for clip in sorted(scenario["clips"], key=lambda item: item["atMs"]):
                path = AUDIO / f"{clip['id']}.mp3"
                start_ms = float(clip["atMs"])
                end_ms = start_ms + audio_duration(path) * 1000
                if start_ms < previous_end_ms + 200:
                    fail(f"dialogue overlaps or has less than 200 ms separation at {clip['id']}")
                if end_ms > scenario["durationSeconds"] * 1000:
                    fail(f"dialogue extends past the recording at {clip['id']}")
                previous_end_ms = end_ms
        for effect_id, effect in effects.items():
            audio = AUDIO / "sfx" / f"{effect_id}.mp3"
            meta = AUDIO / "sfx" / f"{effect_id}.sfx.json"
            if not audio.exists() or not meta.exists() or audio.stat().st_size < 256:
                fail(f"missing generated sound effect or metadata for {effect_id}")
            metadata = json.loads(meta.read_text(encoding="utf-8"))
            if (metadata.get("modelId") != sfx["modelId"]
                    or metadata.get("durationSeconds") != effect["durationSeconds"]
                    or metadata.get("inputSha256") != hashlib.sha256(
                        effect["prompt"].encode()).hexdigest()):
                fail(f"sound-effect contract drift for {effect_id}")
            if metadata.get("audioSha256") != hashlib.sha256(audio.read_bytes()).hexdigest():
                fail(f"sound-effect checksum mismatch for {effect_id}")
    dual = sum(s.get("recording", {}).get("layout") == "dual" for s in scenarios)
    print(f"PASS: {len(scenarios)} NYC scenarios ({dual} dual-display), {tts_chars} TTS "
          f"characters, {len(clip_ids)} content-addressed clips"
          + (f" ({placeholders} with placeholder text)" if placeholders else "")
          + f", {len(effects)} sound effects" +
          (", audio/STT/SFX cache verified" if args.require_audio else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
