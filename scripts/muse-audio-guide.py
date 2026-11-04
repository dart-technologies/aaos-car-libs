#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Write the Met audio guide with Muse Spark: one grounded narration per Open Access work.

Each work in a2ui-automotive/demo/fixtures/met/SOURCE.json is researched by a Responses API call
with web_search (biased to New York), answering in cited prose; one structured-output call then
writes every narration from those findings. Two stages, because web_search and text.format combine
but the JSON carries no url_citation annotations (verified 2026-11-04): seven calls for six works.

  scripts/muse-audio-guide.py [--research-effort low] [--narration-effort medium] [--refresh]

Research is cached in build/muse-audio-guide/, so a rerun repeats only the narration call. Writes
AUDIO_GUIDE.json beside SOURCE.json. The key is read from META_API_KEY only and never printed.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import re
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))

from a2ui_gateway import adapters  # noqa: E402  (stdlib only: shared transport and parsing)

MET = ROOT / "a2ui-automotive/demo/fixtures/met"
CACHE = ROOT / "build/muse-audio-guide"
BASE = "https://api.meta.ai/v1"
MODEL = "muse-spark-1.3"
WEB_SEARCH = {"search_context_size": "low", "user_location": {
    "type": "approximate", "city": "New York", "region": "New York", "country": "US",
    "timezone": "America/New_York"}}
RESEARCH = ("You research an artwork for an in-car audio guide. Search the web: open The Met's "
            "page for the work first, then one other reputable page. Answer in 3 to 5 short "
            "factual sentences on its story, how it was made and why it matters, citing a source "
            "for every fact. Plain prose: no lists, no URLs.")
NARRATION = ("You write an audio guide heard in a car on the way to The Met. For each work write "
             "2 or 3 spoken sentences, 20 to 30 words in all: warm, vivid and easy to follow by "
             "ear. Use only the findings given. The listener cannot see the work: never ask them "
             "to look, and describe nothing that needs eyes; tell its story, its people and why it "
             "matters. Name the work and the artist once. No URLs, lists or quotation marks.")
WORDS = (18, 34)  # "about 20 to 30"
VISUAL = re.compile(r"\b(look(ing)? at|you(?:'ll| will| can)? see|notice|glance|pictured|"
                    r"on (the )?screen|in this (image|picture))\b", re.I)


def call(key: str, body: dict) -> dict:
    return adapters._post(f"{BASE}/responses", {"Authorization": f"Bearer {key}"}, body, 180)


def research(key: str, work: dict, effort: str, refresh: bool) -> dict:
    path = CACHE / f"{work['objectID']}-{effort}.json"
    if path.exists() and not refresh:
        return json.loads(path.read_text(encoding="utf-8"))
    body = {"model": MODEL, "instructions": RESEARCH, "tools": [{"type": "web_search", **WEB_SEARCH}],
            "input": [{"role": "user", "content": f"{work['title']}, {work['artistDisplayName']}, "
                       f"{work['objectDate']}. The Met, object {work['objectID']}: "
                       f"{work['objectURL']}"}],
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 4000}
    for _ in range(2):  # one retry when nothing was cited
        started = time.monotonic()
        response = call(key, body)
        text, cited = adapters._meta_answer(response)
        found = {"findings": text.strip(), "servedModel": response.get("model"),
                 "ms": round((time.monotonic() - started) * 1000),
                 "sources": [{"url": u, "title": t} for u, t in dict.fromkeys(cited)]}
        if found["sources"]:
            CACHE.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(found, indent=1) + "\n", encoding="utf-8")
            break
    return found


def narrate(key: str, works: list[dict], found: dict, effort: str) -> tuple[dict, str | None]:
    ids = [str(w["objectID"]) for w in works]
    schema = {"type": "object", "additionalProperties": False, "required": ["guide"],
              "properties": {"guide": {"type": "array", "items": {
                  "type": "object", "additionalProperties": False,
                  "required": ["objectID", "text"],
                  "properties": {"objectID": {"type": "string", "enum": ids},
                                 "text": {"type": "string"}}}}}}
    request = "\n\n".join(
        f"objectID {w['objectID']}: {w['title']}, {w['artistDisplayName']}, {w['objectDate']}\n"
        f"Findings: {found[w['objectID']]['findings']}" for w in works)
    body = {"model": MODEL, "instructions": NARRATION,
            "input": [{"role": "user", "content": request}],
            "text": {"format": {"type": "json_schema", "name": "audio_guide", "schema": schema,
                                "strict": True}},
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 8000}
    response = call(key, body)
    guide = json.loads(adapters._meta_answer(response)[0])["guide"]
    texts = {item["objectID"]: " ".join(item["text"].split()) for item in guide}
    if sorted(texts) != sorted(ids) or len(guide) != len(ids):
        raise RuntimeError("narration does not cover each work exactly once")
    return texts, response.get("model")


def problems(text: str) -> list[str]:
    found = []
    words = len(text.split())
    if not WORDS[0] <= words <= WORDS[1]:
        found.append(f"{words} words")
    sentences = len(re.findall(r"[.!?](\s|$)", text))
    if not 2 <= sentences <= 3:
        found.append(f"{sentences} sentences")
    if VISUAL.search(text):
        found.append(f"visual phrase '{VISUAL.search(text).group(0)}'")
    if "http" in text or '"' in text:
        found.append("URL or quotation marks")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--research-effort", default="low", choices=("low", "medium"))
    parser.add_argument("--narration-effort", default="medium", choices=("low", "medium"))
    parser.add_argument("--refresh", action="store_true", help="research again, ignoring the cache")
    parser.add_argument("--out", type=pathlib.Path, default=MET / "AUDIO_GUIDE.json")
    args = parser.parse_args()
    key = os.environ.get("META_API_KEY", "").strip()
    if not key:
        print("META_API_KEY is not set; no request was made", file=sys.stderr)
        return 2
    source = json.loads((MET / "SOURCE.json").read_text(encoding="utf-8"))
    works = source["objects"]
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = {w["objectID"]: pool.submit(research, key, w, args.research_effort,
                                                  args.refresh) for w in works}
            found = {oid: future.result() for oid, future in futures.items()}
        for w in works:
            f = found[w["objectID"]]
            print(f"research {w['objectID']}: {len(f['sources'])} source(s), {f['ms']} ms")
            if not f["sources"]:
                raise RuntimeError(f"no url_citation for {w['objectID']}")
        started = time.monotonic()
        texts, served = narrate(key, works, found, args.narration_effort)
        print(f"narration: {round((time.monotonic() - started) * 1000)} ms")
    except adapters.AdapterError as error:  # token only: never a body or a key
        print(f"FAIL: Muse call failed: {error}", file=sys.stderr)
        return 1
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    bad = {oid: problems(text) for oid, text in texts.items() if problems(text)}
    for oid, text in texts.items():
        print(f"{oid}: {text}")
    if bad:
        print(f"FAIL: narration off-brief {bad}; rerun (research is cached)", file=sys.stderr)
        return 1
    guide = [{"objectID": w["objectID"], "clipId": f"met_guide_{n:02d}", "title": w["title"],
              "artist": w["artistDisplayName"], "text": texts[str(w["objectID"])],
              "sources": found[w["objectID"]]["sources"]} for n, w in enumerate(works, 1)]
    args.out.write_text(json.dumps({
        "schemaVersion": 1,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "generator": "scripts/muse-audio-guide.py",
        "model": MODEL, "servedModel": served,
        "reasoningEffort": {"research": args.research_effort, "narration": args.narration_effort},
        "webSearch": WEB_SEARCH,
        "works": "SOURCE.json (The Met Open Access, CC0); text written by Muse from cited findings",
        "guide": guide}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {args.out.relative_to(ROOT)}: {len(guide)} narrations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
