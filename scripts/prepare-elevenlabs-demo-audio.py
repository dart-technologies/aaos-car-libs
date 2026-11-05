#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Generate and cache the NYC demo's ElevenLabs TTS and STT fixtures.

The API key is accepted only from ELEVENLABS_API_KEY. Provider responses are reduced to the
minimum reproducibility metadata; account data and request identifiers are never persisted.

--stt muse instead transcribes the cached guest clips with Muse Voice Transcribe (key from
META_API_KEY only) into the same transcript fixtures, and makes no ElevenLabs request.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
DEFAULT_OUTPUT = ROOT / "a2ui-automotive/demo/audio/elevenlabs"
API = "https://api.elevenlabs.io"
# https://dev.meta.ai/docs/speech-to-text: multipart "request" (JSON) + "audio" (WAV only: mono
# 16-bit PCM at 16 or 24 kHz); the reply's "transcript" is the text.
MUSE_STT_URL = "https://api.meta.ai/v1/asr/transcribe"
MUSE_STT_MODEL = "muse-voice-transcribe-1.0"


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: pathlib.Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def write_json(path: pathlib.Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def request(key: str, method: str, path: str, body: bytes | None = None,
            content_type: str | None = None) -> tuple[bytes, dict[str, str]]:
    headers = {"xi-api-key": key, "User-Agent": "car-libs-fork-oem-demo/1"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.read(), dict(response.headers.items())
    except urllib.error.HTTPError as error:
        # Bodies can echo provider/account details. Keep failures intentionally terse.
        raise RuntimeError(f"ElevenLabs {method} {path.split('?')[0]} returned HTTP "
                           f"{error.code}") from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"ElevenLabs request failed: {error.reason}") from None


def multipart(fields: dict[str, str], file_name: str, file_bytes: bytes) -> tuple[bytes, str]:
    boundary = "----a2ui-" + digest(file_bytes)[:24]
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            value.encode(), b"\r\n",
        ])
    safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", file_name)
    chunks.extend([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{safe_name}"\r\n'.encode(),
        b"Content-Type: audio/mpeg\r\n\r\n", file_bytes, b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def normalized_transcript(text: str) -> str:
    words = re.findall(r"[\w']+", text.casefold(), flags=re.UNICODE)
    return " ".join(words)


def all_clips(manifest: dict) -> list[tuple[str, dict]]:
    return [(scenario["id"], clip)
            for scenario in manifest["scenarios"] for clip in scenario["clips"]]


def spoken(clip: dict) -> str:
    """What the voice says: the caption's text, or its "spoken" respelling (a name TTS mangles)."""
    return clip.get("spoken") or clip["text"]


def all_effects(manifest: dict) -> list[dict]:
    return list(manifest.get("sfx", {}).get("effects", []))


def subscription(key: str) -> dict:
    raw, _ = request(key, "GET", "/v1/user/subscription")
    parsed = json.loads(raw)
    return {
        "characterCount": int(parsed.get("character_count", 0)),
        "characterLimit": int(parsed.get("character_limit", 0)),
    }


def configured_personas(tts: dict, args: argparse.Namespace) -> dict[str, dict]:
    """Resolve roles without ever mixing credentials into checked-in configuration."""
    personas = {name: dict(value) for name, value in tts["personas"].items()}
    global_override = args.voice_id or os.environ.get("ELEVENLABS_VOICE_ID")
    overrides = {
        "navigator": args.navigator_voice_id
            or os.environ.get("ELEVENLABS_NAVIGATOR_VOICE_ID"),
        "guest": args.user_voice_id or os.environ.get("ELEVENLABS_USER_VOICE_ID"),
    }
    for name, persona in personas.items():
        persona["voiceId"] = global_override or overrides.get(name) or persona["voiceId"]
    return personas


def persona_for_role(personas: dict[str, dict], role: str) -> tuple[str, dict]:
    matches = [(name, persona) for name, persona in personas.items()
               if persona.get("role") == role]
    if len(matches) != 1:
        raise RuntimeError(f"role {role!r} maps to {len(matches)} personas; expected exactly one")
    return matches[0]


def verify_personas(key: str, personas: dict[str, dict]) -> None:
    """Verify only configured voices; never dump an account's potentially private voice library."""
    for name, persona in personas.items():
        voice_id = urllib.parse.quote(persona["voiceId"], safe="")
        raw, _ = request(key, "GET", f"/v1/voices/{voice_id}")
        provider = json.loads(raw)
        labels = provider.get("labels") or {}
        provider_name = str(provider.get("name", ""))
        expected_name = str(persona.get("voiceName", ""))
        expected_gender = str(persona.get("genderPresentation", "")).casefold()
        expected_accent = str(persona.get("accentIntent", "")).split(" ", 1)[0].casefold()
        actual_gender = str(labels.get("gender", "")).casefold()
        actual_accent = str(labels.get("accent", "")).casefold()
        if expected_name and not provider_name.casefold().startswith(expected_name.casefold()):
            raise RuntimeError(f"persona {name} expected voice {expected_name!r}, provider "
                               f"resolved {provider_name!r}")
        if actual_gender != expected_gender or actual_accent != expected_accent:
            raise RuntimeError(f"persona {name} provider labels do not match the checked-in "
                               "gender/accent contract")
        print(f"persona {name}: {provider.get('name', 'unnamed')} · "
              f"{labels.get('gender', 'unlabelled gender')} · "
              f"{labels.get('accent', 'unlabelled accent')}")


def wav_for_muse(mp3: pathlib.Path) -> bytes:
    result = subprocess.run(["ffmpeg", "-v", "error", "-i", str(mp3), "-ac", "1", "-ar", "24000",
                             "-c:a", "pcm_s16le", "-map_metadata", "-1", "-f", "wav", "-"],
                            capture_output=True, timeout=60)
    if result.returncode or len(result.stdout) < 1024:
        raise RuntimeError(f"ffmpeg could not convert {mp3.name} to WAV")
    return result.stdout


def muse_transcribe(key: str, settings: dict, name: str, wav: bytes) -> tuple[dict, int]:
    boundary = "----a2ui-" + digest(wav)[:24]
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        b'Content-Disposition: form-data; name="request"\r\n'
        b"Content-Type: application/json\r\n\r\n", canonical(settings), b"\r\n",
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="audio"; filename="{name}.wav"\r\n'.encode(),
        b"Content-Type: audio/wav\r\n\r\n", wav, b"\r\n", f"--{boundary}--\r\n".encode()])
    req = urllib.request.Request(MUSE_STT_URL, data=body, method="POST", headers={
        "Authorization": f"Bearer {key}", "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "User-Agent": "car-libs-fork-oem-demo/1"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        # Status only: the body and headers stay out of logs.
        raise RuntimeError(f"Meta POST /v1/asr/transcribe returned HTTP {error.code}") from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"Meta request failed: {error.reason}") from None
    return json.loads(raw), round((time.monotonic() - started) * 1000)


def transcribe_with_muse(manifest: dict, args: argparse.Namespace) -> int:
    """Guest clips (cached by the ElevenLabs run) through Muse Voice Transcribe."""
    key = os.environ.get("META_API_KEY", "").strip()
    if not key:
        raise SystemExit("META_API_KEY is not set; no provider request was made")
    utterances = [(sid, clip) for sid, clip in all_clips(manifest)
                  if clip["role"] == "user_utterance" and (not args.clip or clip["id"] in args.clip)]
    unknown = set(args.clip or []) - {clip["id"] for _, clip in utterances}
    if unknown:
        raise SystemExit(f"not guest utterance clips: {sorted(unknown)}")
    settings = {"model": MUSE_STT_MODEL, "audioEncoding": "WAV", "mode": "PUSH_TO_TALK",
                "languageBias": ["English"]}
    transcribed = 0
    for scenario_id, clip in utterances:
        mp3 = args.output_dir / f"{clip['id']}.mp3"
        meta = args.output_dir / f"{clip['id']}.tts.json"
        audio = mp3.read_bytes() if mp3.exists() else b""
        if not meta.exists() or read_json(meta).get("audioSha256") != digest(audio):
            raise RuntimeError(f"no cached TTS clip for {clip['id']}; run without --stt muse first")
        cache_key = digest(canonical({"audioSha256": digest(audio), "provider": "muse",
                                      **settings}))
        transcript_path = args.output_dir / "transcripts" / f"{clip['id']}.json"
        cached = read_json(transcript_path) if transcript_path.exists() else {}
        if cached.get("cacheKey") == cache_key and cached.get(
                "dispatchText") == clip["expectedTranscript"]:
            continue
        response, latency_ms = muse_transcribe(key, settings, clip["id"], wav_for_muse(mp3))
        transcript = str(response.get("transcript", "")).strip()
        expected = normalized_transcript(clip["expectedTranscript"])
        actual = normalized_transcript(transcript)
        if actual != expected:
            raise RuntimeError(f"STT mismatch for {clip['id']}: expected normalized "
                               f"'{expected}', received '{actual}'")
        write_json(transcript_path, {
            "schemaVersion": 1,
            "scenarioId": scenario_id,
            "clipId": clip["id"],
            "provider": "muse",
            "modelId": MUSE_STT_MODEL,
            "audioSha256": digest(audio),
            "cacheKey": cache_key,
            "expectedNormalized": expected,
            "transcript": transcript,
            "transcriptNormalized": actual,
            "dispatchText": clip["expectedTranscript"],
            "languageCode": None,
            "audioDurationMs": response.get("audioDurationMs"),
            "latencyMs": latency_ms,
        })
        transcribed += 1
        print(f"muse stt {clip['id']}: '{transcript}' in {latency_ms} ms")
    print(f"done: muse stt={transcribed}, cached={len(utterances) - transcribed}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=pathlib.Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-dir", type=pathlib.Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--budget-credits", type=int)
    parser.add_argument("--voice-id", help="Compatibility override: use one voice for all roles")
    parser.add_argument("--navigator-voice-id", help="Override the navigator persona voice")
    parser.add_argument("--user-voice-id", help="Override the guest/user persona voice")
    parser.add_argument("--verify-personas", action="store_true",
                        help="Verify configured voice metadata without generating audio")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-stt", action="store_true")
    parser.add_argument("--stt", choices=("elevenlabs", "muse"), default="elevenlabs",
                        help="muse: Muse Voice Transcribe on the cached guest clips only")
    parser.add_argument("--clip", action="append",
                        help="with --stt muse: only this guest clip (repeatable)")
    args = parser.parse_args()

    manifest = read_json(args.manifest)
    budget = args.budget_credits or int(manifest["creditBudget"])
    if budget <= 0 or budget > 10000:
        raise SystemExit("budget must be between 1 and the approved 10,000 credits")

    tts = manifest["tts"]
    personas = configured_personas(tts, args)
    clips = all_clips(manifest)
    effects = all_effects(manifest)
    planned_characters = sum(len(spoken(clip)) for _, clip in clips)
    estimated_sfx_credits = sum(math.ceil(float(effect["durationSeconds"])
                                          * int(manifest["sfx"][
                                              "estimatedCreditsPerSecond"]))
                                for effect in effects)
    planned_credits = planned_characters + estimated_sfx_credits
    persona_summary = ", ".join(
        f"{name}={persona['displayName']}" for name, persona in personas.items())
    print(f"plan: {len(clips)} cached TTS clips, {planned_characters} characters; "
          f"{sum(c['role'] == 'user_utterance' for _, c in clips)} STT fixtures; "
          f"{len(effects)} cached SFX, <= {estimated_sfx_credits} estimated SFX credits; "
          f"{persona_summary}")
    if planned_credits > budget:
        raise SystemExit(f"planned TTS and estimated SFX usage ({planned_credits}) exceeds "
                         f"budget ({budget})")
    if args.dry_run:
        return 0
    if args.stt == "muse":
        return transcribe_with_muse(manifest, args)

    key = os.environ.get("ELEVENLABS_API_KEY", "").strip()
    if not key:
        raise SystemExit("ELEVENLABS_API_KEY is not set; no provider request was made")

    if args.verify_personas:
        verify_personas(key, personas)
        return 0

    before = subscription(key)
    available = max(0, before["characterLimit"] - before["characterCount"])
    if planned_credits > available:
        raise SystemExit(f"account reports only {available} characters remaining; "
                         f"generation needs an estimated {planned_credits} credits")

    generated = 0
    reused = 0
    audio_by_id: dict[str, pathlib.Path] = {}
    for scenario_id, clip in clips:
        persona_name, persona = persona_for_role(personas, clip["role"])
        voice_id = persona["voiceId"]
        output = args.output_dir / f"{clip['id']}.mp3"
        metadata_path = args.output_dir / f"{clip['id']}.tts.json"
        payload = {
            "text": spoken(clip),
            "model_id": tts["modelId"],
            "voice_settings": persona["voiceSettings"],
        }
        cache_key = digest(canonical({"voiceId": voice_id,
                                      "outputFormat": tts["outputFormat"], **payload}))
        metadata = read_json(metadata_path) if metadata_path.exists() else {}
        if (output.exists() and metadata.get("cacheKey") == cache_key
                and metadata.get("audioSha256") == digest(output.read_bytes())):
            reused += 1
            audio_by_id[clip["id"]] = output
            continue
        path = "/v1/text-to-speech/" + urllib.parse.quote(voice_id, safe="") \
            + "?output_format=" + urllib.parse.quote(tts["outputFormat"], safe="")
        audio, headers = request(key, "POST", path, canonical(payload), "application/json")
        if len(audio) < 256:
            raise RuntimeError(f"provider returned an implausibly short clip for {clip['id']}")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(".mp3.tmp")
        temporary.write_bytes(audio)
        temporary.replace(output)
        write_json(metadata_path, {
            "schemaVersion": 1,
            "scenarioId": scenario_id,
            "clipId": clip["id"],
            "role": clip["role"],
            "persona": persona_name,
            "personaDisplayName": persona["displayName"],
            "provider": "elevenlabs",
            "modelId": tts["modelId"],
            "voiceId": voice_id,
            "voiceSettings": persona["voiceSettings"],
            "outputFormat": tts["outputFormat"],
            "inputSha256": digest(spoken(clip).encode()),
            "cacheKey": cache_key,
            "audioSha256": digest(audio),
            "characters": len(spoken(clip)),
            "requestIdSha256": digest(headers.get("request-id", "").encode())
                if headers.get("request-id") else None,
        })
        generated += 1
        audio_by_id[clip["id"]] = output

    generated_sfx = 0
    reused_sfx = 0
    sfx = manifest.get("sfx", {})
    for effect in effects:
        output = args.output_dir / "sfx" / f"{effect['id']}.mp3"
        metadata_path = args.output_dir / "sfx" / f"{effect['id']}.sfx.json"
        payload = {
            "text": effect["prompt"],
            "model_id": sfx["modelId"],
            "duration_seconds": effect["durationSeconds"],
            "prompt_influence": effect["promptInfluence"],
            "loop": False,
        }
        cache_key = digest(canonical({"outputFormat": sfx["outputFormat"], **payload}))
        metadata = read_json(metadata_path) if metadata_path.exists() else {}
        if (output.exists() and metadata.get("cacheKey") == cache_key
                and metadata.get("audioSha256") == digest(output.read_bytes())):
            reused_sfx += 1
            continue
        path = "/v1/sound-generation?output_format=" \
            + urllib.parse.quote(sfx["outputFormat"], safe="")
        audio, headers = request(key, "POST", path, canonical(payload), "application/json")
        if len(audio) < 256:
            raise RuntimeError(f"provider returned an implausibly short effect for {effect['id']}")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(".mp3.tmp")
        temporary.write_bytes(audio)
        temporary.replace(output)
        reported_cost = headers.get("character-cost") or headers.get("Character-Cost")
        write_json(metadata_path, {
            "schemaVersion": 1,
            "effectId": effect["id"],
            "provider": "elevenlabs",
            "modelId": sfx["modelId"],
            "outputFormat": sfx["outputFormat"],
            "durationSeconds": effect["durationSeconds"],
            "promptInfluence": effect["promptInfluence"],
            "inputSha256": digest(effect["prompt"].encode()),
            "cacheKey": cache_key,
            "audioSha256": digest(audio),
            "providerReportedCreditCost": int(reported_cost) if reported_cost else None,
            "requestIdSha256": digest(headers.get("request-id", "").encode())
                if headers.get("request-id") else None,
        })
        generated_sfx += 1

    transcribed = 0
    if not args.skip_stt:
        stt = manifest["stt"]
        for scenario_id, clip in clips:
            if clip["role"] != "user_utterance":
                continue
            audio = audio_by_id[clip["id"]].read_bytes()
            fields = {"model_id": stt["modelId"], "language_code": stt["languageCode"],
                      "tag_audio_events": "false", "timestamps_granularity": "none",
                      "seed": "20260907"}
            cache_key = digest(canonical({"audioSha256": digest(audio), **fields}))
            transcript_path = args.output_dir / "transcripts" / f"{clip['id']}.json"
            cached = read_json(transcript_path) if transcript_path.exists() else {}
            if cached.get("cacheKey") == cache_key:
                # Providers may add punctuation/capitalisation. Preserve that evidence, but make the
                # replay value byte-for-byte equal to the phrase the UI displayed and the driver
                # agreed to invoke. Normalized equality was established when this cache was made.
                if cached.get("dispatchText") != clip["expectedTranscript"]:
                    if normalized_transcript(str(cached.get("transcript", ""))) != \
                            normalized_transcript(clip["expectedTranscript"]):
                        raise RuntimeError(f"cached STT mismatch for {clip['id']}")
                    cached["dispatchText"] = clip["expectedTranscript"]
                    write_json(transcript_path, cached)
                continue
            body, content_type = multipart(fields, f"{clip['id']}.mp3", audio)
            raw, _ = request(key, "POST", "/v1/speech-to-text", body, content_type)
            response = json.loads(raw)
            transcript = str(response.get("text", "")).strip()
            expected = normalized_transcript(clip["expectedTranscript"])
            actual = normalized_transcript(transcript)
            if actual != expected:
                raise RuntimeError(f"STT mismatch for {clip['id']}: expected normalized "
                                   f"'{expected}', received '{actual}'")
            write_json(transcript_path, {
                "schemaVersion": 1,
                "scenarioId": scenario_id,
                "clipId": clip["id"],
                "provider": "elevenlabs",
                "modelId": stt["modelId"],
                "audioSha256": digest(audio),
                "cacheKey": cache_key,
                "expectedNormalized": expected,
                "transcript": transcript,
                "transcriptNormalized": actual,
                "dispatchText": clip["expectedTranscript"],
                "languageCode": response.get("language_code"),
            })
            transcribed += 1

    after = subscription(key)
    delta = max(0, after["characterCount"] - before["characterCount"])
    if delta > budget:
        raise RuntimeError(f"provider-reported usage delta {delta} exceeds approved budget {budget}")
    write_json(args.output_dir / "generation-summary.json", {
        "schemaVersion": 1,
        "provider": "elevenlabs",
        "manifestSha256": digest(args.manifest.read_bytes()),
        "generatedTtsClips": generated,
        "reusedTtsClips": reused,
        "generatedSttFixtures": transcribed,
        "generatedSoundEffects": generated_sfx,
        "reusedSoundEffects": reused_sfx,
        "plannedTtsCharacters": planned_characters,
        "estimatedSoundEffectCredits": estimated_sfx_credits,
        "providerCharacterDelta": delta,
        "approvedCreditBudget": budget,
        "personas": {
            name: {"role": persona["role"], "displayName": persona["displayName"],
                   "voiceId": persona["voiceId"]}
            for name, persona in personas.items()
        },
    })
    print(f"done: generated={generated}, reused={reused}, stt={transcribed}, "
          f"sfx={generated_sfx}, reused-sfx={reused_sfx}, "
          f"provider character delta={delta}, budget={budget}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
