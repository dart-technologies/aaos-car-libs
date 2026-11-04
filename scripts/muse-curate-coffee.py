#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Pick the real café for nyc_met's coffee beat with Muse Spark: OSM candidates, cited research,
scores computed here.

Candidates are OpenStreetMap amenity=cafe within 400 m of the charger "take me to the first one"
selects (Host B lists fixtures/chargers/chargers.json nearest its origin first). Walking metres and
minutes are computed here from coordinates (x1.3 for the street grid, 80 m/min); the model never
estimates distance. Each named café within the 6-minute cap is researched by one Responses API call
with web_search (biased to New York) answering in cited prose; one structured-output call then
scores every café from those findings only, citing sources by number from that café's url_citation
list. The open-on-Thursday-5-to-6-pm gate, the weighted total and the ranking are computed here:
a criterion without a valid citation scores 0, and a café with no cited opening hours is
disqualified, not guessed.

  scripts/muse-curate-coffee.py [--research-effort low] [--scoring-effort medium] [--refresh]

The Overpass response, the research and the scoring answer are cached in build/muse-curate-coffee/:
a rerun repeats only the scoring call, and --reuse-scores rebuilds the fixture with no call at all.
Writes fixtures/coffee/{COFFEE,SOURCE}.json. The key is read from META_API_KEY only and never
printed; every request carries a neutral User-Agent.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import math
import os
import pathlib
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))

from a2ui_gateway import adapters  # noqa: E402  (stdlib only: shared transport and parsing)

FIXTURES = ROOT / "a2ui-automotive/demo/fixtures"
OUT = FIXTURES / "coffee"
CACHE = ROOT / "build/muse-curate-coffee"
USER_AGENT = ("car-libs-fork-a2ui-demo/1.0 "
              "(+https://github.com/dart-technologies/aaos-car-libs; demo fixture fetch)")
OVERPASS = ["https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"]
ODBL = {"license": "ODbL 1.0", "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
        "attribution": "© OpenStreetMap contributors"}
# ChargerFixture.ORIGIN_LAT/LON: Host B draws the chargers nearest this point first.
HOST_ORIGIN = (40.7794, -73.9632)
RADIUS_M = 400
GRID_FACTOR = 1.3      # Manhattan blocks: walking is about 1.3x the straight line
METRES_PER_MIN = 80
WALK_CAP_MIN = 6       # one way, inside a ~22 minute charge
BASE = "https://api.meta.ai/v1"
MODEL = "muse-spark-1.3"
WEB_SEARCH = {"search_context_size": "medium", "user_location": {
    "type": "approximate", "city": "New York", "region": "New York", "country": "US",
    "timezone": "America/New_York"}}
VISIT = {"date": "2026-11-05", "day": "Thursday", "from": "17:15", "to": "17:45",
         "timezone": "America/New_York", "chargingMinutes": 22, "usual": "oat milk latte"}
WEIGHTS = {"oatMilkLatte": 0.30, "reputation": 0.25, "walk": 0.20, "pickup": 0.15, "open": 0.10}
WALK_SCORE = {1: 5, 2: 5, 3: 4, 4: 3, 5: 2, 6: 1}
CRITERIA = ("open", "oatMilkLatte", "reputation", "pickup")
RESEARCH = (
    "You research one coffee shop for a driver charging an electric car a short walk away. Search "
    "the web: the shop's own website or menu first, then maps listings, review sites or press. "
    "Answer in short factual sentences about this location, citing a source for every fact: "
    "whether it still operates at this address or has closed; its opening hours on Thursdays, and "
    "whether it is open between 5 and 6 pm on a Thursday; whether its menu offers a latte with "
    "oat milk, and that drink's price only if a menu shows one; what reviews or press say about "
    "it, never inventing a rating; and whether it suits a quick pickup (ordering ahead online or "
    "in an app, counter service). When no source says, write that it is not stated; never guess. "
    "Plain prose: no lists, no URLs.")
SCORING = (
    "You score coffee shops for a driver parked charging for about 22 minutes on Thursday, "
    "November 5, 2026, from about 5:15 to 5:45 pm, who wants their usual oat milk latte. Use only "
    "the findings given for each shop, nothing else you know. For each criterion give a score from "
    "0 to 5, one short evidence sentence drawn from that shop's findings, and the numbers of that "
    "shop's sources that support it. When its findings do not support a criterion, score it 0, say "
    "so, and list no sources. Findings about a different branch or a namesake do not count.\n"
    "operating: operating, closed (permanently or for this period) or unknown.\n"
    "open: verdict open only when cited hours cover 5 to 6 pm on Thursday; closed when they do "
    "not; unknown when hours are not stated. closes is Thursday's closing time as HH:MM on a "
    "24-hour clock when stated, else empty; when sources disagree, use the earliest closing time "
    "they give and say so. 5 closes 7 pm or later, 4 by 6:30 to 7 pm, 3 by 6 to 6:30 pm, 0 closed "
    "or unknown.\n"
    "oatMilkLatte: 5 an oat milk latte, or oat milk offered for lattes, is on its menu; 3 oat or "
    "other plant milk is mentioned without lattes; 0 not found.\n"
    "reputation: cited reviews or press only. 5 strong, consistent praise; 3 mixed or little; 0 "
    "nothing found. Never state a rating a source does not give. Being a chain is not by itself a "
    "reason to score lower.\n"
    "pickup: 5 the findings say this shop takes orders ahead for pickup, online or in an app; 3 "
    "take-out or quick counter service is stated, or online ordering without saying pickup; 0 "
    "not stated. A delivery-only listing is not order-ahead pickup.\n"
    "price: the oat milk latte's price as $N.NN only when a menu in the findings states it, else "
    "empty.")
PRICE = re.compile(r"^\$\d{1,2}(\.\d{2})?$")
HHMM = re.compile(r"^([01]?\d|2[0-4]):([0-5]\d)$")


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


# Geometry: ours, never the model's.

def metres(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Great-circle distance in metres."""
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * 6371000 * math.asin(min(1.0, math.sqrt(h)))


def walk_minutes(straight_m: float) -> int:
    """Whole minutes, rounded up, at 80 m/min over 1.3x the straight line; at least 1."""
    return max(1, math.ceil(straight_m * GRID_FACTOR / METRES_PER_MIN))


def host_label(station: dict) -> str:
    """The name Host B shows for a charger (ChargerFixture.parse)."""
    name, operator = station["name"].strip(), (station.get("operator") or "").strip()
    return name if not operator or operator in name else f"{name} · {operator}"


def first_charger(path: pathlib.Path) -> dict:
    stations = json.loads(path.read_text(encoding="utf-8"))["stations"]
    station = min(stations, key=lambda s: metres(HOST_ORIGIN, (s["lat"], s["lon"])))
    return {"name": station["name"], "hostLabel": host_label(station),
            "operator": station.get("operator"), "osmId": station["osmId"],
            "osmUrl": station["osmUrl"], "lat": station["lat"], "lon": station["lon"],
            "selection": "the first row Host B draws (nearest its origin), which "
                         "\"take me to the first one\" selects"}


# OpenStreetMap candidates.

def overpass_query(charger: dict) -> str:
    return (f'[out:json][timeout:25];nwr["amenity"="cafe"]'
            f'(around:{RADIUS_M},{charger["lat"]},{charger["lon"]});out center tags;')


def fetch_overpass(query: str, attempts: int = 4) -> tuple[bytes, str]:
    """POST to each mirror in turn, backing off 5, 10, 20 s; Overpass often times out."""
    body = urllib.parse.urlencode({"data": query}).encode()
    last: Exception | None = None
    for attempt in range(attempts):
        for endpoint in OVERPASS:
            try:
                request = urllib.request.Request(endpoint, data=body, headers={
                    "User-Agent": USER_AGENT, "Accept": "application/json"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    raw = response.read()
                json.loads(raw)  # an HTML error page is not an answer
                return raw, endpoint
            except urllib.error.HTTPError as error:
                last = error
            except (urllib.error.URLError, TimeoutError, socket.timeout, ValueError) as error:
                last = error
        if attempt < attempts - 1:
            time.sleep(5 * 2 ** attempt)
    raise SystemExit(f"Overpass unavailable on every mirror: {last}")


def candidates(data: dict, charger: dict) -> list[dict]:
    """Named cafés nearest first, with walk minutes; an unnamed one cannot be researched."""
    out = []
    origin = (charger["lat"], charger["lon"])
    for element in data.get("elements", []):
        tags = element.get("tags", {})
        point = element.get("center") or {"lat": element.get("lat"), "lon": element.get("lon")}
        if point.get("lat") is None:
            continue
        lat, lon = float(point["lat"]), float(point["lon"])
        straight = metres(origin, (lat, lon))
        street = " ".join(p for p in (tags.get("addr:housenumber"), tags.get("addr:street")) if p)
        out.append({
            "osmId": f"{element['type']}/{element['id']}",
            "osmUrl": f"https://www.openstreetmap.org/{element['type']}/{element['id']}",
            "name": (tags.get("name") or "").strip(),
            "brand": tags.get("brand"), "address": street or None,
            "lat": round(lat, 6), "lon": round(lon, 6),
            "straightMetres": round(straight), "walkMetres": round(straight * GRID_FACTOR),
            "walkMinutes": walk_minutes(straight),
            "osmTags": {k: tags[k] for k in sorted(tags) if k in {
                "opening_hours", "website", "contact:website", "cuisine", "takeaway",
                "diet:vegan", "brand:wikidata"}},
        })
    out.sort(key=lambda c: (c["straightMetres"], c["osmId"]))
    return out


# Muse research and scoring.

def call(key: str, body: dict, timeout: float = 240) -> dict:
    return adapters._post(f"{BASE}/responses", {"Authorization": f"Bearer {key}",
                                                "User-Agent": USER_AGENT}, body, timeout)


def research(key: str, cafe: dict, effort: str, refresh: bool) -> dict:
    path = CACHE / "research" / f"{cafe['osmId'].replace('/', '-')}-{effort}.json"
    if path.exists() and not refresh:
        return {**json.loads(path.read_text(encoding="utf-8")), "cached": True}
    where = cafe["address"] or "no street address in OpenStreetMap"
    site = cafe["osmTags"].get("website") or cafe["osmTags"].get("contact:website")
    prompt = (f"{cafe['name']}, {where}, Upper East Side, Manhattan, New York "
              f"({cafe['lat']}, {cafe['lon']}). OpenStreetMap: {cafe['osmUrl']}."
              + (f" Website: {site}." if site else "")
              + (f" Brand: {cafe['brand']}." if cafe.get("brand") else "")
              + " The visit is Thursday, November 5, 2026, about 5:15 to 5:45 pm.")
    body = {"model": MODEL, "instructions": RESEARCH,
            "tools": [{"type": "web_search", **WEB_SEARCH}],
            "input": [{"role": "user", "content": prompt}],
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 6000}
    found: dict = {}
    for _ in range(2):  # one retry when nothing was cited
        started = time.monotonic()
        response = call(key, body)
        text, cited = adapters._meta_answer(response)
        found = {"osmId": cafe["osmId"], "findings": " ".join(text.split()),
                 "servedModel": response.get("model"),
                 "ms": round((time.monotonic() - started) * 1000),
                 "sources": [{"url": u, "title": t} for u, t in dict.fromkeys(cited)]}
        if found["sources"]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(found, indent=1) + "\n", encoding="utf-8")
            break
    return {**found, "cached": False}


def scoring_schema(ids: list[str]) -> dict:
    def criterion(**extra: dict) -> dict:
        props = {"score": {"type": "integer", "enum": [0, 1, 2, 3, 4, 5]},
                 "evidence": {"type": "string"},
                 "sources": {"type": "array", "items": {"type": "integer"}}, **extra}
        return {"type": "object", "additionalProperties": False, "required": list(props),
                "properties": props}

    status = {"type": "object", "additionalProperties": False,
              "required": ["status", "evidence", "sources"],
              "properties": {"status": {"type": "string",
                                        "enum": ["operating", "closed", "unknown"]},
                             "evidence": {"type": "string"},
                             "sources": {"type": "array", "items": {"type": "integer"}}}}
    price = {"type": "object", "additionalProperties": False, "required": ["price", "sources"],
             "properties": {"price": {"type": "string"},
                            "sources": {"type": "array", "items": {"type": "integer"}}}}
    shop = {"type": "object", "additionalProperties": False,
            "required": ["id", "operating", *CRITERIA, "price"],
            "properties": {"id": {"type": "string", "enum": ids}, "operating": status,
                           "open": criterion(verdict={"type": "string",
                                                      "enum": ["open", "closed", "unknown"]},
                                             closes={"type": "string"}),
                           "oatMilkLatte": criterion(), "reputation": criterion(),
                           "pickup": criterion(), "price": price}}
    return {"type": "object", "additionalProperties": False, "required": ["shops"],
            "properties": {"shops": {"type": "array", "items": shop}}}


def scoring_prompt(cafes: list[dict], found: dict) -> str:
    blocks = []
    for cafe in cafes:
        f = found[cafe["osmId"]]
        sources = "\n".join(f"[{n}] {s['title'] or s['url']} ({urllib.parse.urlparse(s['url']).hostname})"
                            for n, s in enumerate(f["sources"], 1))
        blocks.append(f"id {cafe['osmId']}: {cafe['name']}, {cafe['address'] or 'no address'}\n"
                      f"Sources:\n{sources}\nFindings: {f['findings']}")
    return "\n\n".join(blocks)


def score(key: str, cafes: list[dict], found: dict, effort: str) -> tuple[list[dict], dict]:
    ids = [c["osmId"] for c in cafes]
    body = {"model": MODEL, "instructions": SCORING,
            "input": [{"role": "user", "content": scoring_prompt(cafes, found)}],
            "text": {"format": {"type": "json_schema", "name": "coffee_scores",
                                "schema": scoring_schema(ids), "strict": True}},
            "reasoning": {"effort": effort}, "store": False, "max_output_tokens": 16000}
    started = time.monotonic()
    response = call(key, body, timeout=300)
    shops = json.loads(adapters._meta_answer(response)[0])["shops"]
    seen = [s["id"] for s in shops]
    if sorted(seen) != sorted(ids):
        raise RuntimeError(f"scores do not cover each café exactly once: {sorted(set(ids) ^ set(seen))}")
    meta = {"ms": round((time.monotonic() - started) * 1000), "servedModel": response.get("model")}
    # The raw answer, for audit and as the recorded sample test_muse_curate_coffee.py replays.
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / f"scores-{effort}.json").write_text(json.dumps({**meta, "shops": shops}, indent=1)
                                                 + "\n", encoding="utf-8")
    return shops, meta


# Deterministic from here: validation, gate, total, rank. No network.

def minutes_of(hhmm: str) -> int | None:
    """'19:30' -> 1170; an early-morning close (00:00-04:59) counts as after midnight."""
    m = HHMM.match(hhmm.strip()) if hhmm else None
    if not m:
        return None
    total = int(m.group(1)) * 60 + int(m.group(2))
    return total + 24 * 60 if total < 5 * 60 else total


def clock(hhmm: str) -> str:
    """'19:00' -> '7', '19:30' -> '7:30', '00:00' -> 'midnight'."""
    total = minutes_of(hhmm) or 0
    hour, minute = divmod(total % (24 * 60), 60)
    if hour == 0 and minute == 0:
        return "midnight"
    hour12 = hour % 12 or 12
    return f"{hour12}" if minute == 0 else f"{hour12}:{minute:02d}"


def cited(numbers: list, sources: list[dict]) -> list[dict]:
    """The sources a criterion names, by 1-based number; anything else is dropped."""
    out = []
    for n in numbers:
        if isinstance(n, int) and 1 <= n <= len(sources) and sources[n - 1] not in out:
            out.append(sources[n - 1])
    return out


def judged(shop: dict, sources: list[dict]) -> dict:
    """Each criterion with its citations resolved; one without a valid citation scores 0."""
    out = {}
    for name in CRITERIA:
        c = shop[name]
        refs = cited(c.get("sources", []), sources)
        value = {"score": max(0, min(5, int(c.get("score", 0)))) if refs else 0,
                 "evidence": " ".join(str(c.get("evidence", "")).split()), "sources": refs}
        if not refs:
            value["uncited"] = True
        if name == "open":
            value["verdict"] = c.get("verdict", "unknown")
            value["closes"] = c["closes"].strip() if minutes_of(c.get("closes", "")) else ""
        out[name] = value
    op = shop["operating"]
    out["operating"] = {"status": op.get("status", "unknown"),
                        "evidence": " ".join(str(op.get("evidence", "")).split()),
                        "sources": cited(op.get("sources", []), sources)}
    price = shop.get("price", {})
    refs = cited(price.get("sources", []), sources)
    out["price"] = ({"price": price["price"].strip(), "sources": refs}
                    if refs and PRICE.match(price.get("price", "").strip()) else None)
    return out


def gate(scores: dict) -> str | None:
    """Why a researched café is out, or None. Hours must be cited; nothing is assumed."""
    op = scores["operating"]
    if op["status"] == "closed" and op["sources"]:
        return f"closed (cited): {op['evidence']}"
    o = scores["open"]
    if not o["sources"] or o["verdict"] == "unknown":
        return "no cited evidence it is open Thursday 5-6 pm"
    if o["verdict"] != "open":
        return f"not open Thursday 5-6 pm (cited): {o['evidence']}"
    close = minutes_of(o["closes"])
    if close is not None and close < 18 * 60:
        return f"closes at {o['closes']} on Thursday (cited)"
    return None


def open_score(o: dict) -> int:
    """From the cited closing time when there is one; else the model's, at most 3."""
    close = minutes_of(o["closes"])
    if close is None:
        return min(o["score"], 3)
    return 5 if close >= 19 * 60 else 4 if close >= 18 * 60 + 30 else 3


def total(scores: dict, walk: int) -> float:
    parts = {"open": open_score(scores["open"]), "walk": WALK_SCORE[walk],
             **{k: scores[k]["score"] for k in ("oatMilkLatte", "reputation", "pickup")}}
    return round(100 * sum(WEIGHTS[k] * parts[k] / 5 for k in WEIGHTS), 1)


def rank(cafes: list[dict], shops: list[dict] | None, found: dict) -> tuple[list, list]:
    """(ranked, disqualified). Ties: total, then shorter walk, then nearer, then OSM id."""
    by_id = {s["id"]: s for s in shops or []}
    ranked, out = [], []
    for cafe in cafes:
        base = {k: cafe[k] for k in ("osmId", "osmUrl", "name", "brand", "address",
                                     "walkMinutes", "walkMetres", "straightMetres")}
        if not cafe["name"]:
            out.append({**base, "reason": "no name in OpenStreetMap, so nothing to research"})
            continue
        if cafe["walkMinutes"] > WALK_CAP_MIN:
            out.append({**base, "reason": f"{cafe['walkMinutes']} min walk is over the "
                                          f"{WALK_CAP_MIN} min cap"})
            continue
        f = found.get(cafe["osmId"])
        if not f or not f.get("sources"):
            out.append({**base, "reason": "research returned no cited findings"})
            continue
        if cafe["osmId"] not in by_id:
            out.append({**base, "reason": "not scored"})
            continue
        scores = judged(by_id[cafe["osmId"]], f["sources"])
        reason = gate(scores)
        if reason:
            out.append({**base, "reason": reason, "scores": scores})
            continue
        ranked.append({**base, "lat": cafe["lat"], "lon": cafe["lon"],
                       "total": total(scores, cafe["walkMinutes"]),
                       "walkScore": WALK_SCORE[cafe["walkMinutes"]],
                       "openScore": open_score(scores["open"]), "scores": scores})
    ranked.sort(key=lambda r: (-r["total"], r["walkMinutes"], r["straightMetres"], r["osmId"]))
    for n, r in enumerate(ranked, 1):
        r["rank"] = n
    return ranked, out


def host(url: str) -> str:
    name = urllib.parse.urlparse(url).hostname or url
    return name[4:] if name.startswith("www.") else name


STREET_WORDS = {"Avenue": "Ave", "Street": "St", "East": "E", "West": "W", "Place": "Pl"}


def label(pick: dict, cafes: list[dict]) -> str:
    """The OSM name, plus its OSM address when another café in range has the same name."""
    twins = [c for c in cafes if c["name"] == pick["name"] and c["osmId"] != pick["osmId"]]
    where = " ".join(STREET_WORDS.get(w, w) for w in (pick.get("address") or "").split())
    return f"{pick['name']}, {where}" if twins and where else pick["name"]


def display(pick: dict, charger: dict, cafes: list[dict]) -> dict:
    """What Host B shows, built from the pick's cited fields and OSM's name and address."""
    s = pick["scores"]
    parts = ["Muse picked it"]
    if s["open"]["closes"]:
        parts.append(f"open till {clock(s['open']['closes'])}")
    if s["oatMilkLatte"]["score"] >= 3:
        parts.append("oat milk")
    parts.append(f"{pick['walkMinutes']} min walk")
    source = (s["open"]["sources"] or s["oatMilkLatte"]["sources"])[0]
    return {"name": label(pick, cafes), "walkMinutes": pick["walkMinutes"],
            "why": " · ".join(parts),
            "source": f"Source · {host(source['url'])}", "sourceUrl": source["url"],
            "price": s["price"]["price"] if s["price"] else None,
            "charger": charger["hostLabel"]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--research-effort", default="low", choices=("low", "medium"))
    parser.add_argument("--scoring-effort", default="medium", choices=("low", "medium", "high"))
    parser.add_argument("--refresh", action="store_true", help="research again, ignoring the cache")
    parser.add_argument("--refresh-osm", action="store_true", help="query Overpass again")
    parser.add_argument("--osm-only", action="store_true", help="list candidates; no Muse call")
    parser.add_argument("--reuse-scores", action="store_true",
                        help="rebuild the fixture from the cached research and scoring answer; "
                             "no Muse call and no key")
    parser.add_argument("--chargers", type=pathlib.Path,
                        default=FIXTURES / "chargers/chargers.json")
    parser.add_argument("--out", type=pathlib.Path, default=OUT)
    args = parser.parse_args()
    charger = first_charger(args.chargers)
    query = overpass_query(charger)
    raw_path, meta_path = CACHE / "overpass.json", CACHE / "overpass.meta.json"
    if args.refresh_osm or not raw_path.exists():
        raw, endpoint = fetch_overpass(query)
        CACHE.mkdir(parents=True, exist_ok=True)
        raw_path.write_bytes(raw)
        meta_path.write_text(json.dumps({"endpoint": endpoint, "query": query,
                                         "fetchedAt": now()}) + "\n", encoding="utf-8")
    raw = raw_path.read_bytes()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    if meta["query"] != query:
        raise SystemExit("cached Overpass response is for another query; use --refresh-osm")
    data = json.loads(raw)
    cafes = candidates(data, charger)
    print(f"charger {charger['hostLabel']} {charger['osmId']}; {len(cafes)} cafés within "
          f"{RADIUS_M} m ({meta['endpoint']}, osm base {data.get('osm3s', {}).get('timestamp_osm_base')})")
    for c in cafes:
        print(f"  {c['walkMinutes']} min {c['straightMetres']:>4} m  {c['osmId']:<18} "
              f"{c['name'] or '(unnamed)'} · {c['address'] or '-'}")
    if args.osm_only:
        return 0
    key = os.environ.get("META_API_KEY", "").strip()
    if not key and not args.reuse_scores:
        print("META_API_KEY is not set; no request was made", file=sys.stderr)
        return 2
    eligible = [c for c in cafes if c["name"] and c["walkMinutes"] <= WALK_CAP_MIN]
    calls = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            futures = {c["osmId"]: pool.submit(research, key, c, args.research_effort,
                                               args.refresh) for c in eligible}
            found = {}
            for osm_id, future in futures.items():
                try:
                    found[osm_id] = future.result()
                except adapters.AdapterError as error:  # token only: never a body or a key
                    print(f"research {osm_id} failed: {error}", file=sys.stderr)
        for c in eligible:
            f = found.get(c["osmId"])
            if f:
                calls.append({"stage": "research", "osmId": c["osmId"], "ms": f["ms"],
                              "sources": len(f["sources"]), "cached": f["cached"]})
                print(f"research {c['name']}: {len(f['sources'])} source(s), {f['ms']} ms"
                      + (" (cached)" if f["cached"] else ""))
        scorable = [c for c in eligible if found.get(c["osmId"], {}).get("sources")]
        if not scorable:
            raise RuntimeError("no café has cited findings")
        if args.reuse_scores:
            meta_scoring = json.loads((CACHE / f"scores-{args.scoring_effort}.json")
                                      .read_text(encoding="utf-8"))
            shops = meta_scoring["shops"]
            if sorted(s["id"] for s in shops) != sorted(c["osmId"] for c in scorable):
                raise RuntimeError("the cached scores are for other cafés; score again")
        else:
            shops, meta_scoring = score(key, scorable, found, args.scoring_effort)
        calls.append({"stage": "scoring", "ms": meta_scoring["ms"], "cafes": len(scorable),
                      "cached": args.reuse_scores})
        print(f"scoring: {len(scorable)} cafés, {meta_scoring['ms']} ms")
    except adapters.AdapterError as error:
        print(f"FAIL: Muse call failed: {error}", file=sys.stderr)
        return 1
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    ranked, disqualified = rank(cafes, shops, found)
    for r in ranked:
        s = r["scores"]
        print(f"#{r['rank']} {r['total']:5.1f} {r['name']} · {r['walkMinutes']} min · open "
              f"{r['openScore']} (closes {s['open']['closes'] or '?'}) oat {s['oatMilkLatte']['score']}"
              f" rep {s['reputation']['score']} pickup {s['pickup']['score']}")
    for d in disqualified:
        print(f"out: {d['name'] or d['osmId']}: {d['reason']}")
    if not ranked:
        print("FAIL: no café passed the gate; nothing is written", file=sys.stderr)
        return 1
    served = sorted({f.get("servedModel") for f in found.values() if f.get("servedModel")}
                    | {meta_scoring["servedModel"]} - {None})
    pick = ranked[0]
    cited_urls = list(dict.fromkeys(src["url"] for r in ranked for k in CRITERIA
                                    for src in r["scores"][k]["sources"]))
    osm = {"source": "OpenStreetMap via the Overpass API", **ODBL, "endpoint": meta["endpoint"],
           "query": query, "fetchedAt": meta["fetchedAt"],
           "osmBase": data.get("osm3s", {}).get("timestamp_osm_base"),
           "responseSha256": hashlib.sha256(raw).hexdigest(), "candidates": len(cafes)}
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "COFFEE.json").write_text(json.dumps({
        "schemaVersion": 1, "generatedAt": now(), "generator": "scripts/muse-curate-coffee.py",
        "model": MODEL, "servedModel": served[0] if len(served) == 1 else served,
        "reasoningEffort": {"research": args.research_effort, "scoring": args.scoring_effort},
        "webSearch": WEB_SEARCH, "asOf": VISIT, "charger": charger,
        "walking": {"gridFactor": GRID_FACTOR, "metresPerMinute": METRES_PER_MIN,
                    "capMinutes": WALK_CAP_MIN, "radiusMetres": RADIUS_M,
                    "method": "great-circle metres x grid factor / metres per minute, rounded "
                              "up; computed by the script, never by the model"},
        "scoring": {"weights": WEIGHTS, "walkScore": {str(k): v for k, v in WALK_SCORE.items()},
                    "scale": "each criterion 0-5; total = 100 x sum(weight x score / 5)",
                    "gate": "open Thursday 5-6 pm with a cited source, else disqualified",
                    "openScore": "from the cited Thursday closing time: 7 pm or later 5, "
                                 "6:30 pm 4, 6 pm 3; without one the model's score, at most 3",
                    "uncited": "a criterion without a valid citation scores 0",
                    "tieBreak": "total, then shorter walk, then nearer, then OSM id"},
        "calls": calls, "display": display(pick, charger, cafes), "pick": pick["osmId"],
        "ranked": ranked, "disqualified": disqualified, "osm": osm,
        "attribution": {"osm": "© OpenStreetMap contributors (ODbL 1.0)",
                        "citedSources": cited_urls},
        "note": "Demo data. No order is placed and nothing is sent to any café; hours, menu "
                "and reputation are as cited when generated, not live."},
        indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (args.out / "SOURCE.json").write_text(json.dumps({
        "schemaVersion": 1, **osm,
        "research": f"{MODEL} via the Meta Responses API with web_search; findings and "
                    "evidence sentences are Muse's summaries of the cited pages, linked by URL, "
                    "not copies of them",
        "fixture": "COFFEE.json",
        "caveat": "Locations and names are OpenStreetMap's; hours, menu and reputation come from "
                  "the cited pages on the generation date and are not live. No order is placed.",
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    shown = display(pick, charger, cafes)
    print(f"pick: {shown['name']} · {shown['why']}")
    print(f"wrote {args.out / 'COFFEE.json'}: {len(ranked)} ranked, {len(disqualified)} out")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
