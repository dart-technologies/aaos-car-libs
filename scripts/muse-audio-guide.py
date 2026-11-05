#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Write the Met audio guide with Muse Spark: one grounded narration per Open Access work.

Each work in a2ui-automotive/demo/fixtures/met/SOURCE.json is researched by a Responses API call
with web_search (biased to New York), answering in cited prose; one structured-output call then
writes every narration from those findings. Two stages, because web_search and text.format combine
but the JSON carries no url_citation annotations (verified 2026-11-04): seven calls for six works.

  scripts/muse-audio-guide.py [--research-effort low] [--narration-effort medium]
                              [--refresh [OBJECT_ID ...]]

Research is cached in build/muse-audio-guide/, so a rerun repeats only the narration call; --refresh
researches again (every work, or only the IDs given). A cited page that is The Met's page for
another object is dropped. Writes AUDIO_GUIDE.json beside SOURCE.json. The key is read from
META_API_KEY only and never printed.
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
RESEARCH = ("You research one painting in The Met's collection for an in-car audio guide. Search "
            "the web: open The Met's page for this object first, then at most one other reputable "
            "page about this same painting. Cite only pages about this exact object, the one in "
            "The Met with the object number given: never a page about another version, replica, "
            "study or copy, an auction lot, or a work of the same name in another collection, "
            "and leave out what only such pages say. Answer in 3 to 5 short factual sentences on "
            "the story it tells, the people and the moment in it, how it was made and why it "
            "matters, citing a source for every fact. Plain prose: no lists, no URLs.")
NARRATION = ("You write an audio guide heard by everyone in a car on the way to The Met; each line "
             "plays over one shot of a film, about 4 seconds aloud. For each work write one "
             "spoken sentence of at most 11 words, in the present tense, that gives the listener "
             "one concrete, vivid thing from the work's story to listen for: a person, a moment, "
             "a gesture or an object in it. Every detail must be stated in that work's findings: "
             "add no action, time of day or feeling they do not give. Warm, natural English that "
             "is easy to follow by ear. Weave the artist's name in once, naturally, usually as a "
             "possessive, spelled as given with its hyphens and accents; the title is optional. "
             "For example, for another painting: 'In Turner's storm, the fishermen race their "
             "boat for the harbor.' Never open with the artist's name and a verb such as painted, "
             "made or created, never tack the name on at the end with for or by, and begin each "
             "line with a different word. The driver cannot look: never ask anyone to look, and "
             "describe nothing that needs eyes. No URLs, lists or quotation marks.")
WORDS = (5, 11)  # at most 11 words: about 4.4 s aloud, 1.5 s of breath in a 5.87 s film shot
VISUAL = re.compile(r"\b(look(ing)? at|you(?:'ll| will| can)? see|notice|glance|pictured|"
                    r"on (the )?screen|in this (image|picture))\b", re.I)
# The opening the guide must not fall back on: "[Artist] painted [Title] ...".
STOCK_OPENING = re.compile(r"^(\S+\s+){0,4}(painted|paints|made|makes|created|creates)\b", re.I)
# ...or on: "... for Johannes Vermeer." A name belongs inside the sentence.
TACKED_ON = re.compile(r"\b(?:for|by)(?:\s+(?:[A-Z][\w'’-]*|van|the|de|der|von))+\W*$")
# The Met's API drops the hyphen and wraps Rembrandt's name; the guide says them as spoken.
SPOKEN_NAMES = {"Jacques Louis David": "Jacques-Louis David",
                "Rembrandt (Rembrandt van Rijn)": "Rembrandt van Rijn"}
UNHYPHENATED = re.compile(r"\bJacques Louis\b")
OTHER_MET_OBJECT = re.compile(r"metmuseum\.org/art/collection/search/(\d+)")


def spoken_name(work: dict) -> str:
    return SPOKEN_NAMES.get(work["artistDisplayName"], work["artistDisplayName"])


def about_this_object(source: dict, object_id: int) -> bool:
    """False for The Met's page for a different object; other pages are judged by the prompt."""
    match = OTHER_MET_OBJECT.search(source["url"])
    return match is None or int(match.group(1)) == object_id


def call(key: str, body: dict) -> dict:
    return adapters._post(f"{BASE}/responses", {"Authorization": f"Bearer {key}"}, body, 180)


def research(key: str, work: dict, effort: str, refresh: bool) -> dict:
    path = CACHE / f"{work['objectID']}-{effort}.json"
    if path.exists() and not refresh:
        return json.loads(path.read_text(encoding="utf-8"))
    body = {"model": MODEL, "instructions": RESEARCH, "tools": [{"type": "web_search", **WEB_SEARCH}],
            "input": [{"role": "user", "content": f"{work['title']}, {spoken_name(work)}, "
                       f"{work['objectDate']}. The Met, object {work['objectID']}: "
                       f"{work['objectURL']}. Cite only pages about this object."}],
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 4000}
    for _ in range(2):  # one retry when nothing was cited
        started = time.monotonic()
        response = call(key, body)
        text, cited = adapters._meta_answer(response)
        sources = [{"url": u, "title": t} for u, t in dict.fromkeys(cited)]
        found = {"findings": text.strip(), "servedModel": response.get("model"),
                 "ms": round((time.monotonic() - started) * 1000),
                 "sources": [s for s in sources if about_this_object(s, work["objectID"])]}
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
        f"objectID {w['objectID']}: {w['title']}, {spoken_name(w)}, {w['objectDate']}\n"
        f"Findings: {found[w['objectID']]['findings']}" for w in works)
    body = {"model": MODEL, "instructions": NARRATION,
            "input": [{"role": "user", "content": request}],
            "text": {"format": {"type": "json_schema", "name": "audio_guide", "schema": schema,
                                "strict": True}},
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 8000}
    response = call(key, body)
    # As the gateway parses: a fenced or prose-wrapped object still parses; no object is an
    # AdapterError token, not a traceback.
    guide = json.loads(adapters._structured(adapters._first_object(
        adapters._meta_answer(response)[0])))["guide"]
    texts = {item["objectID"]: " ".join(item["text"].split()) for item in guide}
    if sorted(texts) != sorted(ids) or len(guide) != len(ids):
        raise RuntimeError("narration does not cover each work exactly once")
    return texts, response.get("model")


def problems(text: str) -> list[str]:
    found = []
    if TACKED_ON.search(text):
        found.append(f"name tacked on '{TACKED_ON.search(text).group(0)}'")
    words = len(text.split())
    if not WORDS[0] <= words <= WORDS[1]:
        found.append(f"{words} words")
    sentences = len(re.findall(r"[.!?](\s|$)", text))
    if not 1 <= sentences <= 2:
        found.append(f"{sentences} sentences")
    if VISUAL.search(text):
        found.append(f"visual phrase '{VISUAL.search(text).group(0)}'")
    if "http" in text or '"' in text:
        found.append("URL or quotation marks")
    if STOCK_OPENING.match(text):
        found.append(f"stock opening '{STOCK_OPENING.match(text).group(0)}'")
    if UNHYPHENATED.search(text):
        found.append("'Jacques Louis' for 'Jacques-Louis'")
    return found


def repeated_openings(texts: dict) -> list[str]:
    """First words that more than one line opens with."""
    firsts = [(re.findall(r"[\w'-]+", t.casefold()) or [""])[0] for t in texts.values()]
    return sorted({f for f in firsts if firsts.count(f) > 1})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--research-effort", default="low", choices=("low", "medium"))
    parser.add_argument("--narration-effort", default="medium", choices=("low", "medium"))
    parser.add_argument("--refresh", nargs="*", type=int, metavar="OBJECT_ID",
                        help="research again, ignoring the cache: every work, or only these")
    parser.add_argument("--out", type=pathlib.Path, default=MET / "AUDIO_GUIDE.json")
    args = parser.parse_args()
    key = os.environ.get("META_API_KEY", "").strip()
    if not key:
        print("META_API_KEY is not set; no request was made", file=sys.stderr)
        return 2
    source = json.loads((MET / "SOURCE.json").read_text(encoding="utf-8"))
    works = source["objects"]
    unknown = set(args.refresh or []) - {w["objectID"] for w in works}
    if unknown:
        print(f"not works in SOURCE.json: {sorted(unknown)}", file=sys.stderr)
        return 2

    def refresh(work: dict) -> bool:
        return args.refresh is not None and (not args.refresh or work["objectID"] in args.refresh)

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = {w["objectID"]: pool.submit(research, key, w, args.research_effort,
                                                  refresh(w)) for w in works}
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
    if repeated_openings(texts):
        bad["openings"] = repeated_openings(texts)
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
    print(f"wrote {args.out}: {len(guide)} narrations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
