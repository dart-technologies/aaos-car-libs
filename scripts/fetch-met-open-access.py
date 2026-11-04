#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Fetch public-domain Met artworks for the in-car demo slideshow."""
import argparse
import hashlib
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://collectionapi.metmuseum.org/public/collection/v1/"
USER_AGENT = "a2ui-automotive-demo/1.0"
TIMEOUT = 20
PAUSE = 0.2

_first_request = True


def fetch(url):
    # GET bytes with a fixed User-Agent and timeout.
    global _first_request
    if not _first_request:
        time.sleep(PAUSE)
    _first_request = False
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read()


def fetch_json(url):
    return json.loads(fetch(url).decode("utf-8"))


def parse_args():
    p = argparse.ArgumentParser(description="Fetch Met Open Access demo images.")
    p.add_argument("--count", type=int, default=6, help="Artworks to collect.")
    p.add_argument("--out", default="a2ui-automotive/demo/fixtures/met",
                   help="Output directory.")
    p.add_argument("--ids", default=None,
                   help="Comma-separated objectIDs overriding the search.")
    return p.parse_args()


def candidate_ids(ids_arg):
    # Explicit IDs override the search; otherwise use sorted highlights.
    if ids_arg:
        ids = []
        for part in ids_arg.split(","):
            part = part.strip()
            if part:
                ids.append(int(part))
        return ids
    data = fetch_json(BASE + "search?isHighlight=true&hasImages=true"
                      "&departmentId=11&q=painting")
    return sorted(data.get("objectIDs") or [])


def main():
    args = parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    try:
        ids = candidate_ids(args.ids)
    except ValueError:
        print("invalid --ids value", file=sys.stderr)
        return 1
    kept = []
    for oid in ids:
        if len(kept) >= args.count:
            break
        try:
            obj = fetch_json(f"{BASE}objects/{oid}")
        except Exception:
            continue
        if not obj.get("isPublicDomain"):
            continue
        image_url = obj.get("primaryImageSmall") or ""
        if not image_url:
            continue
        try:
            image = fetch(image_url)
        except Exception:
            continue
        dest = out / f"{obj.get('objectID', oid)}.jpg"
        dest.write_bytes(image)
        kept.append({
            "objectID": obj.get("objectID", oid),
            "title": obj.get("title", ""),
            "artistDisplayName": obj.get("artistDisplayName", ""),
            "objectDate": obj.get("objectDate", ""),
            "galleryNumber": obj.get("galleryNumber", ""),
            "objectURL": obj.get("objectURL", ""),
            "imageUrl": image_url,
            "file": dest.name,
            "sha256": hashlib.sha256(image).hexdigest(),
        })
    if len(kept) < args.count:
        print(f"only {len(kept)} of {args.count} objects qualified",
              file=sys.stderr)
        return 1
    doc = {
        "source": "The Metropolitan Museum of Art Open Access",
        "license": "CC0 1.0",
        "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        "fetchedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "objects": kept,
    }
    (out / "SOURCE.json").write_text(json.dumps(doc, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
