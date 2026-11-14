#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Fetch EV charging stations near The Met from OpenStreetMap (Overpass) and keep three.

The raw response lands in a new, empty --download-dir and is read only with json.loads. Writes
a2ui-automotive/demo/fixtures/chargers/{chargers,SOURCE}.json. Run with `python3 -I`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "a2ui-automotive/demo/fixtures/chargers"
USER_AGENT = ("car-libs-fork-a2ui-demo/1.0 "
              "(+https://github.com/dart-technologies/aaos-car-libs; demo fixture fetch)")
ENDPOINTS = ["https://overpass-api.de/api/interpreter",
             "https://overpass.kumi.systems/api/interpreter"]
MET = (40.7794, -73.9634)
RADIUS_M = 1500
QUERY = (f'[out:json][timeout:25];nwr["amenity"="charging_station"]'
         f'(around:{RADIUS_M},{MET[0]},{MET[1]});out center tags;')
KEEP = 3


def fetch() -> tuple[bytes, str]:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    last = None
    for endpoint in ENDPOINTS:
        for attempt in range(3):
            try:
                request = urllib.request.Request(endpoint, data=body, headers={
                    "User-Agent": USER_AGENT, "Accept": "application/json"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    return response.read(), endpoint
            except (urllib.error.URLError, TimeoutError) as error:
                last = error
                time.sleep(3 * (attempt + 1))
    raise SystemExit(f"Overpass unavailable: {last}")


def kilowatts(tags: dict) -> float | None:
    """The highest socket:*:output in kW, if any socket output is tagged."""
    values = []
    for key, value in tags.items():
        if re.fullmatch(r"socket:[^:]+:output", key):
            for number, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(kW|W|kw)?", str(value)):
                kw = float(number) / 1000 if unit == "W" else float(number)
                values.append(kw)
    return max(values) if values else None


def distance_m(lat: float, lon: float) -> float:
    dlat = math.radians(lat - MET[0])
    dlon = math.radians(lon - MET[1]) * math.cos(math.radians(MET[0]))
    return 6371000 * math.hypot(dlat, dlon)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-dir", type=pathlib.Path, required=True,
                        help="new or empty folder for the raw response, or one holding it")
    parser.add_argument("--reparse", metavar="FETCHED_AT",
                        help="re-select from the response already in --download-dir")
    args = parser.parse_args()
    raw_path = args.download_dir / "chargers.json"
    if args.reparse:
        raw, endpoint, fetched = raw_path.read_bytes(), ENDPOINTS[0], args.reparse
    else:
        if args.download_dir.exists() and any(args.download_dir.iterdir()):
            raise SystemExit(f"{args.download_dir} is not empty")
        args.download_dir.mkdir(parents=True, exist_ok=True)
        raw, endpoint = fetch()
        fetched = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
        raw_path.write_bytes(raw)
    data = json.loads(raw)
    stations = []
    for element in data.get("elements", []):
        tags = element.get("tags", {})
        point = element.get("center") or {"lat": element.get("lat"), "lon": element.get("lon")}
        if not tags.get("name") or point.get("lat") is None:
            continue
        capacity = tags.get("capacity")
        stations.append({
            "name": tags["name"],
            "lat": round(float(point["lat"]), 6), "lon": round(float(point["lon"]), 6),
            "operator": tags.get("operator") or tags.get("network") or tags.get("brand"),
            "kW": kilowatts(tags),
            "capacity": int(capacity) if str(capacity or "").isdigit() else None,
            "osmId": f"{element['type']}/{element['id']}",
            "osmUrl": f"https://www.openstreetmap.org/{element['type']}/{element['id']}",
            "distanceFromMetMeters": round(distance_m(float(point["lat"]), float(point["lon"]))),
        })
    # Tagged power first (highest), then tagged capacity, then nearest; one per name, so the
    # card never lists two indistinguishable rows. Three is what the card shows.
    stations.sort(key=lambda s: (s["kW"] is None, -(s["kW"] or 0), s["capacity"] is None,
                                 s["distanceFromMetMeters"]))
    kept, names = [], set()
    for station in stations:
        if station["name"] not in names:
            kept.append(station)
            names.add(station["name"])
    if len(kept) < KEEP:
        raise SystemExit(f"only {len(kept)} distinct named stations within {RADIUS_M} m")
    kept = kept[:KEEP]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "chargers.json").write_text(json.dumps({
        "schemaVersion": 1, "near": {"label": "The Met Fifth Avenue", "lat": MET[0],
                                     "lon": MET[1], "radiusMeters": RADIUS_M},
        "attribution": "© OpenStreetMap contributors (ODbL)",
        "note": "Locations and tags from OpenStreetMap; availability, pricing and live status "
                "are not known and must not be shown as live.",
        "stations": kept}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (OUT / "SOURCE.json").write_text(json.dumps({
        "schemaVersion": 1, "source": "OpenStreetMap via the Overpass API",
        "license": "ODbL 1.0", "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
        "attribution": "© OpenStreetMap contributors", "endpoint": endpoint, "query": QUERY,
        "fetchedAt": fetched, "osmBase": data.get("osm3s", {}).get("timestamp_osm_base"),
        "responseSha256": hashlib.sha256(raw).hexdigest(),
        "candidates": len(stations), "kept": KEEP,
        "selection": "named stations within 1.5 km, one per name; tagged socket output first "
                     "(highest kW), then tagged capacity, then nearest",
        "fastChargersTagged": sum(1 for s in kept if (s["kW"] or 0) >= 50),
        "caveat": "Only stations with a tagged socket output of 50 kW or more may be called "
                  "fast; an untagged station's power is unknown, not slow."},
        indent=2) + "\n", encoding="utf-8")
    for station in kept:
        print(f"{station['name']} · {station['operator']} · {station['kW']} kW · "
              f"{station['distanceFromMetMeters']} m · {station['osmId']}")
    print(f"{len(stations)} named candidates")
    return 0


if __name__ == "__main__":
    sys.exit(main())
