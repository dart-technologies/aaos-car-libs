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
"""Validate PNG structure and the auditable sidecar for every committed demo screenshot."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import struct
import subprocess
import sys
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCREENSHOTS = REPO / "docs" / "screenshots"
EXPECTED = {
    "host-a-demo-baseline.png",
    "host-a-demo-fully-restricted.png",
    "host-a-demo-limit-content.png",
    "host-a-demo-no-setup.png",
    "host-a-demo-no-video.png",
    "host-a-live-fully-restricted.png",
    "host-b-demo-fully-restricted.png",
    "host-b-demo-limit-content.png",
    "host-b-live-baseline.png",
    "paintbooth-on-plugin-apis-1.5.0.png",
    "host-a-poi-showcase.png",
    "host-a-poi-search.png",
    "host-a-poi-results.png",
    "host-a-poi-detail.png",
    "host-b-poi-baseline.png",
    "host-a-nav-preview.png",
    "host-a-nav-guidance.png",
}
REQUIRED_METADATA = {
    "commit", "host", "package", "display", "state", "command", "sha256", "captured_at"
}


def validate_png(path: Path) -> list[str]:
    errors: list[str] = []
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ["PNG signature is not at byte zero"]
    offset = 8
    chunks: list[bytes] = []
    while offset < len(data):
        if offset + 12 > len(data):
            errors.append("truncated chunk header")
            break
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(data):
            errors.append(f"truncated {kind!r} chunk")
            break
        payload = data[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length : end])[0]
        actual_crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            errors.append(f"bad CRC for {kind.decode('ascii', 'replace')}")
        chunks.append(kind)
        offset = end
        if kind == b"IEND":
            if offset != len(data):
                errors.append("bytes follow IEND")
            break
    if not chunks or chunks[0] != b"IHDR":
        errors.append("IHDR is missing or is not the first chunk")
    elif len(data) >= 33:
        ihdr_length = struct.unpack(">I", data[8:12])[0]
        width, height = struct.unpack(">II", data[16:24])
        if ihdr_length != 13 or width == 0 or height == 0:
            errors.append("IHDR is invalid")
    if b"IEND" not in chunks:
        errors.append("IEND is missing")
    return errors


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metadata_path(path: Path) -> Path:
    return path.with_name(path.name + ".metadata.json")


def validate_metadata(path: Path) -> list[str]:
    sidecar = metadata_path(path)
    if not sidecar.is_file():
        return [f"missing metadata sidecar {sidecar.name}"]
    try:
        meta = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"metadata is not valid JSON: {exc}"]
    errors: list[str] = []
    missing = REQUIRED_METADATA - set(meta) if isinstance(meta, dict) else REQUIRED_METADATA
    if missing:
        errors.append("metadata missing " + ", ".join(sorted(missing)))
        return errors
    if meta["sha256"] != checksum(path):
        errors.append("metadata checksum does not match PNG")
    if not re.fullmatch(r"[0-9a-f]{7,40}", str(meta["commit"])):
        errors.append("metadata commit is not a Git object id")
    if not str(meta["host"]).strip() or not str(meta["package"]).strip():
        errors.append("metadata host/package is empty")
    if str(meta["state"]).lower() not in {"live", "demo"}:
        errors.append("metadata state must be live or demo")
    if not str(meta["display"]).strip() or not str(meta["command"]).strip():
        errors.append("metadata display/command is empty")
    return errors


def write_metadata(args: argparse.Namespace) -> int:
    path = Path(args.write).resolve()
    png_errors = validate_png(path)
    if png_errors:
        print(f"FAIL: {path}: {'; '.join(png_errors)}", file=sys.stderr)
        return 1
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPO, check=True, text=True,
        stdout=subprocess.PIPE).stdout.strip()
    meta = {
        "commit": commit,
        "host": args.host,
        "package": args.package,
        "display": str(args.display),
        "state": args.state.lower(),
        "command": args.command,
        "sha256": checksum(path),
        "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    sidecar = metadata_path(path)
    sidecar.write_text(json.dumps(meta, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {sidecar}")
    return 0


def check_all() -> int:
    found = {p.name for p in SCREENSHOTS.glob("*.png")}
    problems: list[str] = []
    for name in sorted(EXPECTED - found):
        problems.append(f"{name}: expected screenshot is missing")
    for name in sorted(found - EXPECTED):
        problems.append(f"{name}: unregistered screenshot (add it to the evidence contract)")
    for name in sorted(EXPECTED & found):
        path = SCREENSHOTS / name
        problems.extend(f"{name}: {error}" for error in validate_png(path))
        problems.extend(f"{name}: {error}" for error in validate_metadata(path))
    if problems:
        print(f"FAIL: screenshot evidence has {len(problems)} problem(s):")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"PASS: {len(EXPECTED)} screenshots have valid PNG structure and complete metadata")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", metavar="PNG")
    parser.add_argument("--host")
    parser.add_argument("--package")
    parser.add_argument("--display")
    parser.add_argument("--state", choices=("live", "demo"))
    parser.add_argument("--command")
    args = parser.parse_args()
    if args.write:
        required = (args.host, args.package, args.display, args.state, args.command)
        if any(value is None for value in required):
            parser.error("--write requires --host, --package, --display, --state and --command")
        return write_metadata(args)
    return check_all()


if __name__ == "__main__":
    raise SystemExit(main())
