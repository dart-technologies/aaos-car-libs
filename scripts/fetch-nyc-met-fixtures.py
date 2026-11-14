#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Fetch the nyc_met route (OSRM), The Met's location (Overpass) and today's NWS forecast.

Raw responses land in a new, empty --download-dir (one subfolder per source) and are treated as
untrusted data: only json.loads touches them. Curated fixtures are written under
a2ui-automotive/demo/fixtures/{route,nws}/ with source URL, licence, fetch time and SHA-256.
Run with `python3 -I`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "a2ui-automotive/demo/fixtures"
USER_AGENT = ("car-libs-fork-a2ui-demo/1.0 "
              "(+https://github.com/dart-technologies/aaos-car-libs; demo fixture fetch)")
ORIGIN = {"label": "Hudson Yards", "lat": 40.7536, "lon": -74.0009}
OVERPASS = ["https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"]
OVERPASS_QUERY = ('[out:json][timeout:25];'
                  'nwr["name"="The Metropolitan Museum of Art"]["tourism"="museum"]'
                  '(40.77,-73.97,40.785,-73.955);out center tags;')
OSRM = "https://router.project-osrm.org/route/v1/driving/"
NWS = "https://api.weather.gov/points/"
ODBL = {"license": "ODbL 1.0", "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
        "attribution": "© OpenStreetMap contributors"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def get(url: str, *, data: bytes | None = None, accept: str = "application/json",
        attempts: int = 3) -> bytes:
    headers = {"User-Agent": USER_AGENT, "Accept": accept}
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, data=data, headers=headers)
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == attempts - 1:
                raise
        except urllib.error.URLError:
            if attempt == attempts - 1:
                raise
        time.sleep(3 * (attempt + 1))
    raise AssertionError("unreachable")


def save(folder: pathlib.Path, name: str, raw: bytes) -> str:
    folder.mkdir(parents=True, exist_ok=False)  # each source gets its own new folder
    (folder / name).write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def write_json(path: pathlib.Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def fetch_met(download: pathlib.Path) -> dict:
    body = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    last_error: Exception | None = None
    for endpoint in OVERPASS:
        try:
            raw = get(endpoint, data=body)
            break
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
    else:
        raise SystemExit(f"Overpass unavailable: {last_error}")
    fetched = now()
    digest = save(download / "overpass", "met.json", raw)
    data = json.loads(raw)
    elements = [e for e in data.get("elements", []) if e.get("type") in ("way", "relation")]
    if not elements:
        raise SystemExit("Overpass returned no Met building")
    element = elements[0]
    center = element.get("center") or {}
    tags = element.get("tags", {})
    return {
        "source": "OpenStreetMap via the Overpass API", **ODBL,
        "endpoint": endpoint, "query": OVERPASS_QUERY, "fetchedAt": fetched,
        "responseSha256": digest, "osmBase": data.get("osm3s", {}).get("timestamp_osm_base"),
        "element": {"type": element["type"], "id": element["id"],
                    "url": f"https://www.openstreetmap.org/{element['type']}/{element['id']}",
                    "center": {"lat": float(center["lat"]), "lon": float(center["lon"])},
                    "tags": {key: tags[key] for key in sorted(tags) if key in {
                        "name", "tourism", "addr:housenumber", "addr:street", "addr:postcode",
                        "wikidata", "website"}}},
    }


def fetch_route(download: pathlib.Path, met: dict) -> dict:
    end = met["element"]["center"]
    url = (f"{OSRM}{ORIGIN['lon']},{ORIGIN['lat']};{end['lon']},{end['lat']}"
           "?overview=full&geometries=geojson&steps=false&alternatives=false")
    raw = get(url)
    fetched = now()
    digest = save(download / "osrm", "route.json", raw)
    data = json.loads(raw)
    if data.get("code") != "Ok" or not data.get("routes"):
        raise SystemExit(f"OSRM did not return a route: {data.get('code')}")
    route = data["routes"][0]
    coordinates = route["geometry"]["coordinates"]
    if route["geometry"].get("type") != "LineString" or len(coordinates) < 10:
        raise SystemExit("OSRM geometry is not a usable LineString")
    return {
        "source": "OSRM demo server (router.project-osrm.org), OpenStreetMap data", **ODBL,
        "url": url, "fetchedAt": fetched, "responseSha256": digest,
        "origin": ORIGIN,
        "destination": {"label": "The Met", "lat": end["lat"], "lon": end["lon"],
                        "osm": met["element"]["url"]},
        "waypoints": [{"name": w.get("name", ""), "location": w.get("location")}
                      for w in data.get("waypoints", [])],
        "distanceMeters": route["distance"], "durationSeconds": route["duration"],
        "durationNote": "Free-flow estimate from the public demo server; not a traffic ETA.",
        "geometry": {"type": "LineString", "coordinates": coordinates},
    }


def fetch_forecast(download: pathlib.Path, route: dict) -> dict:
    coordinates = route["geometry"]["coordinates"]
    lon, lat = coordinates[len(coordinates) // 2]
    point_url = f"{NWS}{lat:.4f},{lon:.4f}"
    point_raw = get(point_url, accept="application/geo+json")
    point_digest = save(download / "nws-point", "point.json", point_raw)
    point = json.loads(point_raw)["properties"]
    forecast_url = point["forecast"]
    if not forecast_url.startswith("https://api.weather.gov/gridpoints/"):
        raise SystemExit("NWS point did not name a gridpoint forecast")
    forecast_raw = get(forecast_url, accept="application/geo+json")
    fetched = now()
    forecast_digest = save(download / "nws-forecast", "forecast.json", forecast_raw)
    forecast = json.loads(forecast_raw)["properties"]
    periods = [{key: period.get(key) for key in (
        "number", "name", "startTime", "endTime", "isDaytime", "temperature", "temperatureUnit",
        "probabilityOfPrecipitation", "windSpeed", "windDirection", "shortForecast",
        "detailedForecast")} for period in forecast.get("periods", [])[:4]]
    if not periods:
        raise SystemExit("NWS forecast has no periods")
    return {
        "source": "U.S. National Weather Service (api.weather.gov)",
        "license": "Public domain (U.S. Government work)",
        "licenseUrl": "https://www.weather.gov/disclaimer",
        "point": {"lat": round(lat, 4), "lon": round(lon, 4), "reason": "route midpoint",
                  "url": point_url, "responseSha256": point_digest,
                  "gridId": point.get("gridId"), "gridX": point.get("gridX"),
                  "gridY": point.get("gridY")},
        "forecastUrl": forecast_url, "fetchedAt": fetched, "responseSha256": forecast_digest,
        "generatedAt": forecast.get("generatedAt"), "updateTime": forecast.get("updateTime"),
        "periods": periods,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-dir", type=pathlib.Path, required=True,
                        help="new or empty folder for the raw responses")
    parser.add_argument("--date", required=True, help="forecast date label, YYYY-MM-DD")
    args = parser.parse_args()
    download = args.download_dir
    if download.exists() and any(download.iterdir()):
        raise SystemExit(f"{download} is not empty")
    download.mkdir(parents=True, exist_ok=True)
    met = fetch_met(download)
    route = fetch_route(download, met)
    forecast = fetch_forecast(download, route)
    write_json(FIXTURES / "route/met-osm.json", met)
    write_json(FIXTURES / "route/hudson-yards-to-met.json", route)
    write_json(FIXTURES / f"nws/forecast-{args.date}.json", forecast)
    print(f"met {met['element']['url']} at {met['element']['center']}")
    print(f"route {route['distanceMeters']:.0f} m, {len(route['geometry']['coordinates'])} points, "
          f"snapped to {[w['name'] for w in route['waypoints']]}")
    print(f"forecast {forecast['point']['gridId']} {forecast['point']['gridX']},"
          f"{forecast['point']['gridY']}: {forecast['periods'][0]['name']} — "
          f"{forecast['periods'][0]['shortForecast']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
