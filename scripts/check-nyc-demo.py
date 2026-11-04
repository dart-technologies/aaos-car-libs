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
             "nyc_concierge": "poi_discovery_order", "nyc_met": "met_visit"}
FILM = ROOT / "a2ui-automotive/demo/fixtures/met/FILM.json"
FILM_PORTRAIT = ROOT / "a2ui-automotive/demo/fixtures/met/FILM_PORTRAIT.json"
# The names other agents implement against; changing one here means changing it there.
MET_SEQUENCE = ["met_visit", "ev_charge_coffee"]
MET_UTTERANCES = {"take us to the met this afternoon", "reserve entry for two at three",
                  "what should we see first", "take me to the first one",
                  "order my usual coffee"}
MET_ASK = "Take us to The Met this afternoon"
ACTIONS = {"speak", "tapText", "carState", "keyEvent", "selectCase", "agent", "passenger",
           "endCard", "vehicleSignal", "wallClock"}
PASSENGER_MEDIA = {"video", "artwork", "stop"}
VEHICLE_ROUTES = {"vhal-set", "vhal-inject", "broadcast"}
WEATHER_HOSTS = {"forecast.weather.gov", "api.weather.gov"}
SECTIONS = {"preamble", "timeline", "outro"}
PREAMBLE_STYLES = ["unsafe", "useless", "answer"]
# Spoken lines never call the story a demo, nor claim a booking or order went anywhere; the
# margin disclaimer carries the demo's nature for the whole video.
NEVER_SAID = re.compile(r"\b(demo|sent|booked|paid|placed|ordered)\b", re.I)


def preamble_seconds(scenario: dict) -> float:
    return round(sum(float(card.get("seconds", 0)) for card in scenario.get("preamble", [])), 3)


def outro_seconds(scenario: dict) -> float:
    return float(scenario["outro"].get("seconds", 0)) if scenario.get("outro") else 0.0


def total_seconds(scenario: dict) -> float:
    """The video's length: post-produced preamble + device timeline + outro (run-nyc-demo.py)."""
    return (preamble_seconds(scenario) + int(scenario.get("durationSeconds", 0))
            + outro_seconds(scenario))


def section_bounds_ms(scenario: dict, section: str) -> tuple[int, int]:
    """(offset on the video timeline, section length) for a clip's section, in ms."""
    preamble = round(preamble_seconds(scenario) * 1000)
    timeline = int(scenario.get("durationSeconds", 0)) * 1000
    if section == "preamble":
        return 0, preamble
    if section == "timeline":
        return preamble, timeline
    return preamble + timeline, round(outro_seconds(scenario) * 1000)


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
    status = commerce.get("merchantStatus")
    if status not in {"fictional_demo", "none", "real_listing_no_order"}:
        fail(f"{name} merchant is neither fictional, absent, nor a real listing with no order")
    if status == "real_listing_no_order":
        # A real place may be named, located and explained, never ordered from.
        merchant = commerce.get("merchant", {})
        listing = urllib.parse.urlparse(str(merchant.get("source", "")))
        fixture = ROOT / str(merchant.get("fixture", ""))
        if commerce.get("orderStatus") != "demo_not_sent":
            fail(f"{name} names a real merchant, so its order must be a demo that is never sent")
        if (listing.scheme != "https" or listing.hostname != "www.openstreetmap.org"
                or "OpenStreetMap" not in str(merchant.get("attribution", ""))):
            fail(f"{name} real merchant must cite and credit its OpenStreetMap listing")
        if not merchant.get("name") or not fixture.is_file() or fixture.suffix != ".json":
            fail(f"{name} real merchant needs a name and its curated fixture")
        record = json.loads(fixture.read_text(encoding="utf-8"))
        if record.get("display", {}).get("name") != merchant["name"]:
            fail(f"{name} merchant is not the one {merchant['fixture']} picked")
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


def driving_at(scenario: dict, at_ms: int) -> bool:
    """Whether the scripted car state at at_ms is drive (scenarios start parked)."""
    state = "park"
    for action in scenario["actions"]:
        if action.get("type") == "carState" and int(action["atMs"]) <= at_ms:
            state = action["state"]
    return state == "drive"


def check_met_story(scenario: dict) -> None:
    """The nyc_met contract shared with the corpus, Host B and audio-guide work."""
    if scenario.get("corpusSequence") != MET_SEQUENCE:
        fail(f"nyc_met corpus sequence must be {MET_SEQUENCE}")
    clips = scenario["clips"]
    said = {clip["expectedTranscript"] for clip in clips if clip["role"] == "user_utterance"}
    if said != MET_UTTERANCES:
        fail(f"nyc_met guest lines drifted: {sorted(said ^ MET_UTTERANCES)}")
    if any("tomorrow" in clip["text"].casefold() for clip in clips):
        fail("nyc_met is set on Thursday itself; no line may say 'tomorrow'")
    jargon = re.compile(r"\b(fixture|emulator|car[ _]service|adb|broadcast|vhal|a2ui)\b", re.I)
    for clip in clips:
        if jargon.search(clip["text"]):
            fail(f"{clip['id']} speaks engineering jargon: {jargon.search(clip['text'])[0]!r}")
    actions = scenario["actions"]
    asks = [a for a in actions if a["type"] == "agent"]
    if [(a.get("provider"), a.get("ask")) for a in asks] != [("muse", MET_ASK)]:
        fail(f"nyc_met must make exactly one Muse request: {MET_ASK!r}")
    artwork = [int(a["atMs"]) for a in actions
               if a["type"] == "passenger" and a.get("media") == "artwork"]
    if len(artwork) != 1 or not driving_at(scenario, artwork[0]):
        fail("nyc_met starts the passenger artwork film once, while driving")
    works = json.loads(FILM.read_text(encoding="utf-8"))["works"]
    guide = sorted((c for c in clips if c.get("lane") == "audio_guide"), key=lambda c: c["id"])
    if [c["id"] for c in guide] != [f"met_guide_{n:02d}" for n in range(1, len(works) + 1)]:
        fail("nyc_met needs one audio-guide clip per film work, met_guide_01..")
    for clip, work in zip(guide, works):
        offset = int(clip["atMs"]) - artwork[0]
        if clip.get("objectID") != work["objectID"] or not (
                work["startMs"] <= offset <= work["startMs"] + 1500):
            fail(f"{clip['id']} is not timed to {work['title']} in the film")
        if not driving_at(scenario, int(clip["atMs"])):
            fail(f"{clip['id']} plays outside the drive")
    signals = [int(a["atMs"]) for a in actions if a["type"] == "vehicleSignal"]
    if not signals or not all(driving_at(scenario, at) for at in signals):
        fail("nyc_met lowers the battery while driving")
    # The story ends on the driver watching Big Buck Bunny while parked (after the coffee), and
    # a post-produced closing card replaces the in-car end card.
    if any(a["type"] == "endCard" for a in actions):
        fail("nyc_met ends on Big Buck Bunny and its closing card, not an in-car end card")
    confirms = [int(a["atMs"]) for a in actions
                if a["type"] == "tapText" and a.get("text") == "Confirm"]
    bunny = [int(a["atMs"]) for a in actions
             if a["type"] == "tapText" and a.get("text") == "Big Buck Bunny"]
    if len(bunny) != 1 or driving_at(scenario, bunny[0]) or not confirms or bunny[0] < max(
            confirms):
        fail("nyc_met taps Big Buck Bunny once, parked, after the coffee is confirmed")
    if any(driving_at(scenario, int(a["atMs"])) for a in actions if a["atMs"] > bunny[0]):
        fail("nyc_met stays parked while the driver watches")
    for clip in clips:
        if NEVER_SAID.search(clip["text"]):
            fail(f"{clip['id']} says {NEVER_SAID.search(clip['text'])[0]!r}; the disclaimer, "
                 "not the narration, says it is a demo, and nothing claims a real transaction")
    note = scenario.get("disclaimer") or {}
    image = (ROOT / str(note.get("image", ""))).resolve()
    if image.parent != CARDS.resolve() or not image.is_file() or \
            image.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"nyc_met disclaimer is not a PNG in {CARDS.relative_to(ROOT)}")
    if not all(word in str(note.get("text", "")) for word in (
            "emulator", "demo data", "no affiliation")):
        fail("nyc_met disclaimer must say simulated on the emulator, demo data, no affiliation")
    check_preamble(scenario)
    check_outro(scenario)
    portrait = json.loads(FILM_PORTRAIT.read_text(encoding="utf-8"))
    if [(w["objectID"], w["startMs"]) for w in portrait["works"]] != [
            (w["objectID"], w["startMs"]) for w in works]:
        fail("the portrait film's per-work startMs drifted from FILM.json; the guide would slip")


def check_preamble(scenario: dict) -> None:
    """Three cards from the recording's own frames: the parked full screen shown as unsafe, then
    locked out, then the real pruned driving frame; each with its own narration inside it."""
    cards = scenario.get("preamble", [])
    if [card.get("style") for card in cards] != PREAMBLE_STYLES:
        fail(f"nyc_met preamble cards must be {PREAMBLE_STYLES}")
    if not 10 <= preamble_seconds(scenario) <= 15:
        fail("nyc_met preamble runs 10–15 s")
    clips = {clip["id"]: clip for clip in scenario["clips"]}
    start = 0
    for card in cards:
        at = int(card.get("frameAtMs", -1))
        if not 0 <= at < int(scenario["durationSeconds"]) * 1000:
            fail(f"preamble {card['style']} needs a frame inside the timeline")
        if "caption" in card:
            fail(f"preamble {card['style']} bakes in no caption: the burned-in captions carry "
                 "its narration")
        if driving_at(scenario, at) != (card["style"] == "answer"):
            fail(f"preamble {card['style']} frame must be "
                 + ("a driving" if card["style"] == "answer" else "the parked full") + " screen")
        seconds = round(float(card.get("seconds", 0)) * 1000)
        if not 2500 <= seconds <= 7000:
            fail(f"preamble {card['style']} card holds 2.5–7 s")
        clip = clips.get(card.get("clipId"))
        if not clip or clip.get("section") != "preamble" or clip["role"] != "narration" or \
                not start <= int(clip["atMs"]) < start + seconds:
            fail(f"preamble {card['style']} narration must be a preamble clip inside its card")
        start += seconds
    if [c["id"] for c in scenario["clips"] if c.get("section") == "preamble"] != [
            card["clipId"] for card in cards]:
        fail("every preamble clip belongs to one preamble card, in order")


def check_outro(scenario: dict) -> None:
    """The closing card: one narration line then a silent hold, a calm note on what the drive
    was, the journey's beats taken from the recording at the right car state, and the
    architecture as title-only boxes. No tagline or footer, and Muse is named once."""
    outro = scenario.get("outro") or {}
    if not 5 <= outro_seconds(scenario) <= 10:
        fail("nyc_met closing card holds 5–10 s")
    clips = [c for c in scenario["clips"] if c.get("section") == "outro"]
    if [c["id"] for c in clips] != [outro.get("clipId")] or clips[0]["role"] != "narration":
        fail("nyc_met closing card has exactly one narration clip, its clipId")
    timeline = int(scenario["durationSeconds"]) * 1000
    beats = outro.get("beats", [])
    lanes = {"driver": [], "passenger": []}
    for beat in beats:
        at = int(beat.get("atMs", -1))
        if beat.get("display") not in lanes or not beat.get("label") or not 0 <= at < timeline:
            fail(f"closing-card beat needs a display, a label and a time in the timeline: {beat}")
        lanes[beat["display"]].append(beat)
    if len(lanes["driver"]) != 7 or not 1 <= len(lanes["passenger"]) <= 3:
        fail("closing card shows seven driver beats and the passenger lane")
    if any(not driving_at(scenario, int(b["atMs"])) for b in lanes["passenger"]):
        fail("closing card's passenger thumbnails sit under the driving segments")
    bunny = [int(a["atMs"]) for a in scenario["actions"]
             if a["type"] == "tapText" and a.get("text") == "Big Buck Bunny"]
    last = lanes["driver"][-1]
    if last["label"] != "Big Buck Bunny" or driving_at(scenario, int(last["atMs"])) or \
            not bunny or int(last["atMs"]) < bunny[0] + 3000:
        fail("closing card's last driver beat is Big Buck Bunny playing, parked")
    boxes = outro.get("architecture", [])
    if len([b for b in boxes if b.get("badge")]) != 1 or not 4 <= len(boxes) <= 5:
        fail("closing card's architecture is 4–5 boxes with one Muse badge")
    if any(len(box) != 1 or not set(box) <= {"badge", "title"} for box in boxes):
        fail("closing card's architecture boxes are titles only: one badge or title each")
    note = outro.get("note", "")
    if not re.search(r"\bsimulated\b", note, re.I) or not re.search(r"\bemulator\b", note, re.I):
        fail("closing card's note says the drive is simulated on the emulator")
    if "footer" in outro:
        fail("closing card has no tagline or footer: its narration carries the line")
    words = [note, *outro.get("places", []), *(b["label"] for b in beats),
             *(text for box in boxes for text in box.values())]
    if sum("Muse" in text for text in words) != 1 or "Muse" not in next(
            (b["badge"] for b in boxes if b.get("badge")), ""):
        fail("closing card names Muse once, on its architecture badge")


def corpus_ids() -> set[str]:
    return set(re.findall(r'new Builder\(\s*"([a-z_]+)"', CORPUS.read_text(encoding="utf-8")))


def audio_duration(path: pathlib.Path) -> float:
    result = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                             "-of", "csv=p=0", str(path)], text=True,
                            capture_output=True, timeout=30)
    if result.returncode:
        fail(f"could not measure audio duration for {path.name}")
    return float(result.stdout.strip())


# Transcript fixtures may come from either STT provider (prepare-elevenlabs-demo-audio.py --stt).
MUSE_STT_MODEL = "muse-voice-transcribe-1.0"


def known_stt(transcript: dict, manifest: dict) -> bool:
    models = {"elevenlabs": manifest.get("stt", {}).get("modelId"), "muse": MUSE_STT_MODEL}
    model = models.get(transcript.get("provider"))
    return model is not None and transcript.get("modelId") == model


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
        # The bound is on the video: the device timeline plus any post-produced preamble and
        # closing card. screenrecord's own limit bounds the timeline.
        if not 60 <= total_seconds(scenario) <= 180 or not 0 < duration <= 175:
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
            section = clip.get("section", "timeline")
            if section not in SECTIONS or (section != "timeline" and (
                    clip["role"] != "narration" or not scenario.get(section))):
                fail(f"clip {clip['id']} names a section its scenario does not have")
            if not 0 <= int(clip.get("atMs", -1)) < section_bounds_ms(scenario, section)[1]:
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
            if action.get("type") == "wallClock" and not re.fullmatch(
                    r"\d{4}-\d\d-\d\dT\d\d:\d\d", str(action.get("local", ""))):
                fail(f"wall clock needs local YYYY-MM-DDTHH:MM: {action}")
            if action.get("type") == "keyEvent" and action.get("key") != "BACK":
                fail(f"unsupported key event {action}")
            if action.get("type") == "speak" or (action.get("type") == "agent"
                                                 and "clipId" in action):
                clip = clips.get(action.get("clipId"))
                if not clip or clip.get("role") != "user_utterance":
                    fail(f"{action['type']} action does not name an utterance: {action}")
                if action["type"] == "agent" and normalized(action["ask"]) != normalized(
                        clip["expectedTranscript"]):
                    fail(f"agent request is not what the guest says: {action}")
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

    check_met_story(by_id["nyc_met"])
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
                if not known_stt(transcript, data):
                    fail(f"STT fixture for {clip['id']} names an unknown provider or model")
            # On the video's timeline: each clip at its section's offset, inside its section.
            placed = []
            for clip in scenario["clips"]:
                offset, length = section_bounds_ms(scenario, clip.get("section", "timeline"))
                placed.append((offset + float(clip["atMs"]), offset + length, clip))
            for start_ms, section_end_ms, clip in sorted(placed, key=lambda item: item[0]):
                path = AUDIO / f"{clip['id']}.mp3"
                end_ms = start_ms + audio_duration(path) * 1000
                if start_ms < previous_end_ms + 200:
                    fail(f"dialogue overlaps or has less than 200 ms separation at {clip['id']}")
                if end_ms > section_end_ms:
                    fail(f"dialogue extends past its section of the recording at {clip['id']}")
                previous_end_ms = end_ms
            card_start = 0.0
            for card in scenario.get("preamble", []):
                clip = next(c for c in scenario["clips"] if c["id"] == card["clipId"])
                end_ms = float(clip["atMs"]) + audio_duration(AUDIO / f"{clip['id']}.mp3") * 1000
                card_start += float(card["seconds"]) * 1000
                if end_ms > card_start:
                    fail(f"{clip['id']} runs past its preamble card")
            if scenario.get("outro"):
                clip = next(c for c in scenario["clips"]
                            if c["id"] == scenario["outro"]["clipId"])
                end_ms = float(clip["atMs"]) + audio_duration(AUDIO / f"{clip['id']}.mp3") * 1000
                if end_ms > outro_seconds(scenario) * 1000 - 1500:
                    fail(f"{clip['id']} leaves the closing card less than 1.5 s of silence")
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
