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
"""Validate deterministic, locally generated Host B demo assets."""

from __future__ import annotations

import hashlib
import json
import pathlib
import struct
import sys


ROOT = pathlib.Path(__file__).resolve().parent.parent
EXPECTED = {
    "a2ui-host-carui/src/main/res/drawable-nodpi/demo_route_preview.png":
        "9aef1569b545255bdd87e763fd8b6444e32451c8bc208498bffe67881af5bd40",
    "a2ui-host-carui/src/main/res/drawable-nodpi/demo_media_poster.png":
        "b4e98ac3648a1561456a991dc84bd8b14c9a5b72fe85143534576fdc4e5dc07a",
    "a2ui-host-carui/src/main/res/drawable-nodpi/demo_album_art.png":
        "6d7ad2d78f90496839ac7a90fdba9d17b3e84ed76e3905af9bb76f24fb648518",
    "a2ui-host-carui/src/main/res/raw/demo_placeholder_video.mp4":
        "a92375f09d8c4d32c0da650788ca67086529b5cc76f2af3f307e980358b10b85",
    "a2ui-host-carui/src/main/res/raw/demo_placeholder_audio.m4a":
        "d553b28c8e86ce574d79e68f3cee220f4c234a5da3d2609a277fa205c77a551b",
    "a2ui-host-carui/src/main/res/raw/bbb_passenger.mp4":
        "8ca6121326adfc437ee9eb902344bd27e023f338f69202e4605d10844f6f4609",
    "a2ui-host-carui/src/main/res/raw/bbb_passenger_portrait.mp4":
        "fe80570672a0be0508453ab93e309c514ad02e98b5d1bafc99b0bfa99b875ce5",
}
# Third-party or derived media: the provenance record must name the same bytes and a licence.
PROVENANCE = {
    "a2ui-host-carui/src/main/res/raw/bbb_passenger.mp4":
        "a2ui-automotive/demo/fixtures/bbb/SOURCE.json",
    "a2ui-host-carui/src/main/res/raw/bbb_passenger_portrait.mp4":
        "a2ui-automotive/demo/fixtures/bbb/SOURCE_PORTRAIT.json",
}
MAX_BYTES = {
    "a2ui-host-carui/src/main/res/raw/bbb_passenger.mp4": 4_000_000,
    "a2ui-host-carui/src/main/res/raw/bbb_passenger_portrait.mp4": 4_000_000,
}


def main() -> int:
    failures: list[str] = []
    for relative, expected in EXPECTED.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing {relative}")
            continue
        data = path.read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            failures.append(f"checksum drift {relative}: {actual}")
            continue
        if path.suffix == ".png":
            if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
                failures.append(f"invalid PNG structure {relative}")
                continue
            width, height = struct.unpack(">II", data[16:24])
            if width < 256 or height < 256:
                failures.append(f"undersized PNG {relative}: {width}x{height}")
        elif b"ftyp" not in data[:64]:
            failures.append(f"invalid ISO media header {relative}")
        if len(data) > MAX_BYTES.get(relative, len(data)):
            failures.append(f"oversized {relative}: {len(data)} bytes")
        record = PROVENANCE.get(relative)
        if record:
            try:
                meta = json.loads((ROOT / record).read_text(encoding="utf-8"))
            except (OSError, ValueError) as error:
                failures.append(f"unreadable provenance {record}: {error}")
                continue
            if meta.get("output", {}).get("sha256") != actual:
                failures.append(f"provenance {record} does not name {relative}'s bytes")
            if not meta.get("license") or not meta.get("licenseUrl"):
                failures.append(f"provenance {record} has no licence")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"demo assets: {len(EXPECTED)} checksums and structures valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
