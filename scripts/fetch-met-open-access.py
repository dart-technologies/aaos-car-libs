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
SEARCH_BASE = "https://collectionapi.metmuseum.org/public/collection/v1.1/"
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


def fetch_search_page(offset):
    # Fetch one v1.1 search page; returns (total, ids).
    url = (f"{SEARCH_BASE}search?isHighlight=true&hasImages=true"
           f"&departmentId=11&limit=100&offset={offset}")
    data = fetch_json(url)
    return data.get("total") or 0, data.get("objectIDs") or []


def candidate_ids(ids_arg):
    # Explicit IDs override the search; otherwise use sorted highlights.
    if ids_arg:
        ids = []
        for part in ids_arg.split(","):
            part = part.strip()
            if part:
                ids.append(int(part))
        return ids
    ids = []
    offset = 0
    total = None
    while offset + 100 <= 10000:
        page_total, page = fetch_search_page(offset)
        if total is None:
            total = page_total
        if not page:
            break
        ids.extend(page)
        offset += len(page)
        if len(page) < 100:
            break
        if total is not None and offset >= total:
            break
    return sorted(ids)


def collect_one(oid, out):
    # Fetch object; save image and return record if PD with image.
    try:
        obj = fetch_json(f"{BASE}objects/{oid}")
    except Exception:
        return None
    if not obj.get("isPublicDomain"):
        return None
    image_url = obj.get("primaryImageSmall") or ""
    if not image_url:
        return None
    try:
        image = fetch(image_url)
    except Exception:
        return None
    oid2 = obj.get("objectID", oid)
    dest = out / f"{oid2}.jpg"
    dest.write_bytes(image)
    return {
        "objectID": oid2,
        "title": obj.get("title", ""),
        "artistDisplayName": obj.get("artistDisplayName", ""),
        "objectDate": obj.get("objectDate", ""),
        "galleryNumber": obj.get("galleryNumber", ""),
        "objectURL": obj.get("objectURL", ""),
        "imageUrl": image_url,
        "file": dest.name,
        "sha256": hashlib.sha256(image).hexdigest(),
    }


def main():
    args = parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    try:
        explicit = candidate_ids(args.ids) if args.ids else None
    except ValueError:
        print("invalid --ids value", file=sys.stderr)
        return 1
    kept = []
    if explicit is not None:
        for oid in explicit:
            if len(kept) >= args.count:
                break
            rec = collect_one(oid, out)
            if rec:
                kept.append(rec)
    else:
        # Page v1.1 search until enough qualify or results run out.
        offset = 0
        total = None
        while len(kept) < args.count and offset + 100 <= 10000:
            page_total, page = fetch_search_page(offset)
            if total is None:
                total = page_total
            if not page:
                break
            for oid in sorted(page):
                if len(kept) >= args.count:
                    break
                rec = collect_one(oid, out)
                if rec:
                    kept.append(rec)
            offset += len(page)
            if len(page) < 100:
                break
            if total is not None and offset >= total:
                break
    if len(kept) < args.count:
        print(f"only {len(kept)} of {args.count} objects qualified",
              file=sys.stderr)
        return 1
    doc = {
        "source": "The Metropolitan Museum of Art Open Access",
        "apiVersion": "v1.1 search, v1 objects",
        "license": "CC0 1.0",
        "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        "fetchedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "objects": kept,
    }
    (out / "SOURCE.json").write_text(json.dumps(doc, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
