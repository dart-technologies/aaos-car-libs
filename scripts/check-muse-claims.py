#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Every claim in Muse's committed demo outputs is backed by a citation. Offline, no key.

  met/BRIEF.json         at least one url citation, and every Source row Host B shows is one
                         of them. Its rows count as cited at brief level: the structured call
                         that shapes the cited prose carries no per-row annotations.
  coffee/COFFEE.json     each nonzero criterion (and price) of a ranked café has evidence and a
                         cited URL; each disqualified café a reason; the ranking follows from
                         the scores, and the pick and its displayed name, why and runner-up (or
                         source) follow from the ranking.
  met/AUDIO_GUIDE.json   each line has a source, each metmuseum.org source is that line's
                         object, and no line is over 11 words.

  scripts/check-muse-claims.py [--fixtures DIR]

Exit codes: 0 pass, 1 an unbacked claim or a fixture that contradicts itself.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "a2ui-automotive/demo/fixtures"
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))

from a2ui_gateway import contract  # noqa: E402  (stdlib only: the gateway's own Source rows)

CRITERIA = ("open", "oatMilkLatte", "reputation", "pickup")
MAX_WORDS = 11
MET_OBJECT = re.compile(r"^/art/collection/search/(\d+)/?$")
HHMM = re.compile(r"^([01]?\d|2[0-4]):([0-5]\d)$")
# muse-curate-coffee.py label(): a namesake in range adds the abbreviated OSM address.
STREET_WORDS = {"Avenue": "Ave", "Street": "St", "East": "E", "West": "W", "Place": "Pl"}


def host(url: object) -> str | None:
    """The http(s) URL's host without www., or None for anything else."""
    if not isinstance(url, str):
        return None
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    return parts.hostname.removeprefix("www.")


def cited(sources: object) -> list[str]:
    """The valid http(s) URLs among a claim's sources."""
    return [s["url"] for s in sources or [] if isinstance(s, dict) and host(s.get("url"))]


class Tally:
    def __init__(self):
        self.claims: dict[str, list[int]] = {}
        self.failures: list[str] = []

    def claim(self, fixture: str, backed: bool, why: str) -> None:
        counts = self.claims.setdefault(fixture, [0, 0])
        counts[0] += backed
        counts[1] += 1
        if not backed:
            self.failures.append(f"{fixture}: {why}")

    def fail(self, fixture: str, why: str) -> None:
        self.failures.append(f"{fixture}: {why}")


def check_brief(brief: dict, tally: Tally) -> None:
    name = "met/BRIEF.json"
    rows = {row for c in brief.get("citations") or [] if isinstance(c, dict)
            for row, _ in contract.source_rows([(c.get("url"), c.get("title"))])}
    if not rows:
        tally.fail(name, "no url citation")
    components = [c for m in brief.get("output", {}).get("messages", [])
                  for c in m.get("updateComponents", {}).get("components", [])]
    shown, content = [], []
    for c in components:
        title = c.get("title")
        if c.get("component") != "Text" or not isinstance(title, str) or not title.strip():
            continue
        if title.startswith("Source"):
            parts = title.split(" · ")  # what Host B shows: "Source · host", never the subtitle
            shown.append(" · ".join(parts[:2]) if len(parts) >= 2 else title)
        else:
            content.append(c["id"])
    if not shown:
        tally.fail(name, "shows no Source row")
    for row in shown:
        if row not in rows:
            tally.fail(name, f"Source row {row!r} is none of its citations")
    if not content:
        tally.fail(name, "no rows to check")
    backed = bool(rows) and bool(shown) and set(shown) <= rows
    for cid in content:
        tally.claim(name, backed, f"row {cid} has no cited page behind it")


def minutes(hhmm: str) -> int | None:
    """'19:30' -> 1170; a close from 00:00 to 04:59 is after midnight (muse-curate-coffee.py)."""
    match = HHMM.match((hhmm or "").strip())
    if not match:
        return None
    total = int(match.group(1)) * 60 + int(match.group(2))
    return total + 24 * 60 if total < 5 * 60 else total


def clock(hhmm: str) -> str:
    hour, minute = divmod((minutes(hhmm) or 0) % (24 * 60), 60)
    if hour == minute == 0:
        return "midnight"
    return f"{hour % 12 or 12}" if minute == 0 else f"{hour % 12 or 12}:{minute:02d}"


def label(cafe: dict, everyone: list[dict]) -> str:
    twins = [c for c in everyone if c["name"] == cafe["name"] and c["osmId"] != cafe["osmId"]]
    where = " ".join(STREET_WORDS.get(w, w) for w in (cafe.get("address") or "").split())
    return f"{cafe['name']}, {where}" if twins and where else cafe["name"]


def open_score(o: dict) -> int:
    close = minutes(o.get("closes"))
    if close is None:
        return min(o["score"], 3)
    return 5 if close >= 19 * 60 else 4 if close >= 18 * 60 + 30 else 3


def check_coffee(coffee: dict, tally: Tally) -> None:
    name = "coffee/COFFEE.json"
    ranked, out = coffee.get("ranked") or [], coffee.get("disqualified") or []
    if not ranked:
        tally.fail(name, "no ranked café")
        return
    for r in ranked:
        for k in CRITERIA:
            c = r["scores"][k]
            if c["score"]:
                tally.claim(name, bool(str(c.get("evidence", "")).strip() and cited(c["sources"])),
                            f"{r['name']} ({r['osmId']}) {k} scores {c['score']} with no "
                            f"evidence or cited URL")
        price = r["scores"].get("price")
        if price:
            tally.claim(name, bool(cited(price.get("sources"))),
                        f"{r['name']} ({r['osmId']}) price {price.get('price')!r} is uncited")
    for d in out:
        if not str(d.get("reason") or "").strip():
            tally.fail(name, f"disqualified {d.get('name') or d.get('osmId')} has no reason")

    # The ranking follows from the scores: weights and walk scores as the fixture declares them.
    scoring = coffee["scoring"]
    for r in ranked:
        parts = {"open": open_score(r["scores"]["open"]),
                 "walk": scoring["walkScore"][str(r["walkMinutes"])],
                 **{k: r["scores"][k]["score"] for k in ("oatMilkLatte", "reputation", "pickup")}}
        total = round(100 * sum(w * parts[k] / 5 for k, w in scoring["weights"].items()), 1)
        if abs(total - r["total"]) > 0.05 or parts["open"] != r["openScore"]:
            tally.fail(name, f"{r['name']} ({r['osmId']}) total {r['total']} is not its scores'")
    order = sorted(ranked, key=lambda r: (-r["total"], r["walkMinutes"], r["straightMetres"],
                                          r["osmId"]))
    if [r["osmId"] for r in order] != [r["osmId"] for r in ranked] \
            or [r["rank"] for r in ranked] != list(range(1, len(ranked) + 1)):
        tally.fail(name, "ranked is not in rank order")

    # The pick and what Host B shows for it.
    pick, shown, everyone = ranked[0], coffee["display"], ranked + out
    if coffee.get("pick") != pick["osmId"]:
        tally.fail(name, f"pick {coffee.get('pick')} is not rank 1 ({pick['osmId']})")
    if shown.get("name") != label(pick, everyone):
        tally.fail(name, f"displayed name {shown.get('name')!r} is not rank 1's")
    if shown.get("walkMinutes") != pick["walkMinutes"]:
        tally.fail(name, "displayed walk is not rank 1's")
    s = pick["scores"]
    compared = sum(1 for c in everyone if c.get("scores"))
    allowed = {f"{pick['walkMinutes']} min walk"}
    if s["open"]["closes"] and cited(s["open"]["sources"]):
        allowed.add(f"open till {clock(s['open']['closes'])}")
    if s["oatMilkLatte"]["score"] >= 3 and cited(s["oatMilkLatte"]["sources"]):
        allowed.add("oat milk")
    why = str(shown.get("why", "")).split(" · ")
    if why[0] != f"Muse compared {compared} café{'' if compared == 1 else 's'}":
        tally.fail(name, f"why says {why[0]!r}; {compared} cafés were scored")
    for part in why[1:]:
        if part not in allowed:
            tally.fail(name, f"why says {part!r}, which rank 1's cited scores do not back")
    if len(why[1:]) != len(set(why[1:])):
        tally.fail(name, "why repeats a part")
    if shown.get("sourceUrl") not in cited(s["open"]["sources"]) + cited(
            s["oatMilkLatte"]["sources"]):
        tally.fail(name, "displayed source URL is not one rank 1's hours or oat milk cite")
    second = (f"Runner-up: {label(ranked[1], everyone)}" if len(ranked) > 1
              else f"Source · {host(shown.get('sourceUrl'))}")
    if shown.get("source") != second:
        tally.fail(name, f"displayed {shown.get('source')!r}, ranking says {second!r}")
    if shown.get("price") != (s["price"]["price"] if s.get("price") else None):
        tally.fail(name, "displayed price is not rank 1's cited price")


def check_audio_guide(guide: dict, tally: Tally) -> None:
    name = "met/AUDIO_GUIDE.json"
    lines = guide.get("guide") or []
    if not lines:
        tally.fail(name, "no lines")
    for line in lines:
        what = f"line {line.get('clipId')} (object {line.get('objectID')})"
        sources = line.get("sources") or []
        urls = cited(sources)
        wrong = []
        for url in urls:
            h = host(url)
            if h == "metmuseum.org" or h.endswith(".metmuseum.org"):
                match = MET_OBJECT.match(urllib.parse.urlsplit(url).path)
                if not match or match.group(1) != str(line.get("objectID")):
                    wrong.append(url)
        if wrong:
            tally.fail(name, f"{what} cites another Met page: {', '.join(wrong)}")
        tally.claim(name, bool(urls) and not wrong and len(urls) == len(sources),
                    f"{what} has no valid source")
        words = len(str(line.get("text", "")).split())
        if not 1 <= words <= MAX_WORDS:
            tally.fail(name, f"{what} is {words} words (at most {MAX_WORDS})")


def check(fixtures: pathlib.Path) -> Tally:
    tally = Tally()
    for relative, checker in (("met/BRIEF.json", check_brief),
                              ("coffee/COFFEE.json", check_coffee),
                              ("met/AUDIO_GUIDE.json", check_audio_guide)):
        try:
            data = json.loads((fixtures / relative).read_text(encoding="utf-8"))
            checker(data, tally)
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            tally.fail(relative, f"unreadable or malformed ({type(error).__name__}: {error})")
    return tally


def summary(tally: Tally) -> str:
    backed = sum(c[0] for c in tally.claims.values())
    total = sum(c[1] for c in tally.claims.values())
    parts = ", ".join(f"{fixture.split('/')[-1].removesuffix('.json')} {c[0]}/{c[1]}"
                      for fixture, c in tally.claims.items())
    return f"citation coverage: {backed}/{total} claims cited ({parts})"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixtures", type=pathlib.Path, default=FIXTURES)
    args = parser.parse_args()
    tally = check(args.fixtures)
    for failure in tally.failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    print(summary(tally))
    return 1 if tally.failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
