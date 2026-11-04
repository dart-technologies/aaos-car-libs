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
"""Render the demo's brand assets from committed sources in a2ui-automotive/demo/cards/brand/.

  icon       muse-icon-96.png and muse-icon-256.png from muse-icon.svg (ImageMagick + Pillow)
  intro      the opening shown ON the cockpit's screens, at the recordings' own sizes so it
             takes the same scale-into-the-box path and crossfades into the live UI in place:
             cards/nyc_met-intro-driver.png     1408x792 (16:9), the Muse2CarUI graffiti
             cards/nyc_met-intro-passenger.png   528x792 (2:3), tagline + the Muse mark
  thumbnail  build/demo/youtube-thumbnail.jpg (1280x720, < 2 MB): the cockpit showing the intro,
             plus its docs/media copy
  all        all three, in that order

The Muse icon is attribution only ("Built with Muse"), never this project's logo; see
brand/SOURCE.json. The graffiti plate is a Muse Image 1.0 generation; see brand/graffiti-3.json.
"""

from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[1]
BRAND = ROOT / "a2ui-automotive/demo/cards/brand"
ICON_SVG = BRAND / "muse-icon.svg"
PLATE = BRAND / "graffiti-3.webp"
THUMBNAIL = ROOT / "build/demo/youtube-thumbnail.jpg"
THUMBNAIL_DOCS = ROOT / "docs/media/youtube-thumbnail.jpg"
INTRO_DRIVER = ROOT / "a2ui-automotive/demo/cards/nyc_met-intro-driver.png"
INTRO_PASSENGER = ROOT / "a2ui-automotive/demo/cards/nyc_met-intro-passenger.png"
DRIVER_SIZE, PASSENGER_SIZE = (1408, 792), (528, 792)  # the raw screenrecord sizes
THUMB_TAGLINE = "Muse plans. The car decides."
# The icon's own linearGradient (userSpaceOnUse, viewBox 0..100), which ImageMagick's SVG
# renderer drops: it renders the path, so the path becomes the mask and Pillow paints this.
GRADIENT = ((133.245, 109.046), (24.9203, -12.5565),
            [(0.254808, (0x00, 0x82, 0xFB)), (0.697115, (0x00, 0x64, 0xE0)),
             (1.0, (0x00, 0x40, 0xDC))])


def overlays():
    """scripts/demo_overlays.py, for its fonts (loaded once; dataclasses need sys.modules)."""
    if "demo_overlays" not in sys.modules:
        spec = importlib.util.spec_from_file_location(
            "demo_overlays", pathlib.Path(__file__).resolve().with_name("demo_overlays.py"))
        module = importlib.util.module_from_spec(spec)
        sys.modules["demo_overlays"] = module
        spec.loader.exec_module(module)
    return sys.modules["demo_overlays"]


def gradient_colour(t: float) -> tuple[int, int, int]:
    stops = GRADIENT[2]
    if t <= stops[0][0]:
        return stops[0][1]
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
    return stops[-1][1]


def icon(size: int = 1024) -> Image.Image:
    """The Muse icon, transparent, size x size."""
    svg = ICON_SVG.read_text(encoding="utf-8")
    mask_svg = re.sub(r'fill="url\(#[^)]*\)"', 'fill="#FFFFFF"', svg).replace(' fill="none"', "", 1)
    with tempfile.TemporaryDirectory() as tmp:
        source, out = pathlib.Path(tmp) / "mask.svg", pathlib.Path(tmp) / "mask.png"
        source.write_text(mask_svg, encoding="utf-8")
        subprocess.run(["magick", "-background", "none", "-density", "1200", str(source),
                        "-resize", f"{size}x{size}!", f"PNG32:{out}"], check=True, timeout=60)
        alpha = Image.open(out).convert("RGBA").getchannel("A")
    (x1, y1), (x2, y2), _ = GRADIENT
    dx, dy = x2 - x1, y2 - y1
    norm = dx * dx + dy * dy
    k = 100 / size
    paint = Image.new("RGB", (size, size))
    paint.putdata([gradient_colour((((x + 0.5) * k - x1) * dx + ((y + 0.5) * k - y1) * dy) / norm)
                   for y in range(size) for x in range(size)])
    out_image = paint.convert("RGBA")
    out_image.putalpha(alpha)
    return out_image


def render_icons() -> list[pathlib.Path]:
    big = icon(1024)
    written = []
    for size in (96, 256):
        path = BRAND / f"muse-icon-{size}.png"
        big.resize((size, size), Image.LANCZOS).save(path, format="PNG", optimize=True)
        written.append(path)
    return written


def cover(plate: pathlib.Path, size: tuple[int, int], top: float) -> Image.Image:
    """Scale plate to cover size; crop horizontally centred, vertically from `top` (0..1)."""
    image = Image.open(plate).convert("RGB")
    scale = max(size[0] / image.width, size[1] / image.height)
    resized = image.resize((round(image.width * scale), round(image.height * scale)),
                           Image.LANCZOS)
    left = (resized.width - size[0]) // 2
    y = round((resized.height - size[1]) * top)
    return resized.crop((left, y, left + size[0], y + size[1]))


def bottom_gradient(image: Image.Image, start: float, strength: int) -> None:
    w, h = image.size
    shade = Image.new("L", (1, h))
    for row in range(h):
        k = (row / h - start) / (1 - start)
        shade.putpixel((0, row), 0 if k <= 0 else round(strength * min(1, k) ** 1.4))
    dark = Image.new("RGBA", (w, h), (4, 5, 9, 255))
    dark.putalpha(shade.resize((w, h)))
    image.alpha_composite(dark)


def shadowed_text(image: Image.Image, xy, text: str, face, fill, anchor="ls",
                  blur: int = 6) -> None:
    shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text(xy, text, font=face, fill=(0, 0, 0, 220), anchor=anchor)
    image.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)))
    ImageDraw.Draw(image).text(xy, text, font=face, fill=fill, anchor=anchor)


def built_with_muse(image: Image.Image, x: int, y: int, scale: float = 1.0,
                    centre: bool = False) -> None:
    """A small attribution pill: Muse icon + "Built with Muse" (x is its centre if centre)."""
    ov = overlays()
    face = ov.font(round(30 * scale), "demi")
    icon_px = round(44 * scale)
    label = "Built with Muse"
    pad = round(14 * scale)
    w = pad + icon_px + round(12 * scale) + round(ov.width(label, face)) + pad + round(6 * scale)
    h = icon_px + 2 * round(9 * scale)
    if centre:
        x -= w // 2
    pill = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(pill).rounded_rectangle((x, y, x + w, y + h), radius=h // 2,
                                           fill=(8, 10, 14, 190),
                                           outline=(255, 255, 255, 60), width=1)
    image.alpha_composite(pill)
    mark = Image.open(BRAND / "muse-icon-256.png").convert("RGBA").resize(
        (icon_px, icon_px), Image.LANCZOS)
    image.alpha_composite(mark, (x + pad, y + (h - icon_px) // 2))
    ImageDraw.Draw(image).text((x + pad + icon_px + round(12 * scale), y + h // 2), label,
                               font=face, fill=(244, 246, 248, 255), anchor="lm")


def intro_driver() -> Image.Image:
    """The graffiti wordmark, cropped 16:9 around its lettering."""
    return cover(PLATE, DRIVER_SIZE, 0.42)


def intro_passenger() -> Image.Image:
    """The same plate blurred to colour (no cut-off letters), the tagline, and the Muse mark."""
    ov = overlays()
    w, h = PASSENGER_SIZE
    image = cover(PLATE, (w, h), 0.5).filter(ImageFilter.GaussianBlur(24)).convert("RGBA")
    image.alpha_composite(Image.new("RGBA", (w, h), (6, 7, 12, 150)))
    bottom_gradient(image, 0.5, 190)
    cx = w // 2
    # Less is more: the graffiti carries the name; one tagline, and the Muse mark as the credit.
    for row, line in enumerate(("Muse plans", "the trip.")):
        shadowed_text(image, (cx, 330 + 62 * row), line, ov.font(54, "bold"),
                      (255, 255, 255, 255), anchor="ms")
    shadowed_text(image, (cx, 470), "The car decides.", ov.font(36, "medium"),
                  (214, 218, 226, 255), anchor="ms", blur=4)
    mark = Image.open(BRAND / "muse-icon-256.png").convert("RGBA").resize((56, 56), Image.LANCZOS)
    image.alpha_composite(mark, (cx - 28, 600))
    return image.convert("RGB")


def render_intro() -> list[pathlib.Path]:
    intro_driver().save(INTRO_DRIVER, format="PNG", optimize=True)
    intro_passenger().save(INTRO_PASSENGER, format="PNG", optimize=True)
    return [INTRO_DRIVER, INTRO_PASSENGER]


def cockpit_still() -> Image.Image:
    """The 1920x1080 cockpit with the intro on both screens and the intro glow."""
    ov = overlays()
    boxes = ov.layout()
    canvas = Image.new("RGBA", tuple(boxes["canvas"]), (11, 15, 20, 255))
    for name, path in (("driver", INTRO_DRIVER), ("passenger", INTRO_PASSENGER)):
        x, y, w, h = boxes[name]
        canvas.paste(Image.open(path).convert("RGB").resize((w, h), Image.LANCZOS), (x, y))
    canvas.alpha_composite(Image.open(ROOT / boxes["frame"]).convert("RGBA"))
    canvas.alpha_composite(Image.open(ROOT / boxes["glow"]).convert("RGBA"))
    return canvas


def render_thumbnail() -> list[pathlib.Path]:
    ov = overlays()
    image = cockpit_still().resize((1280, 720), Image.LANCZOS)
    face = ov.font(60, "bold")
    shadowed_text(image, (640, 694), THUMB_TAGLINE, face, (255, 255, 255, 255), anchor="ms",
                  blur=8)
    built_with_muse(image, 18, 6, scale=0.8)
    THUMBNAIL.parent.mkdir(parents=True, exist_ok=True)
    rgb = image.convert("RGB")
    for quality in (92, 88, 84, 80):
        rgb.save(THUMBNAIL, format="JPEG", quality=quality, optimize=True, progressive=True)
        if THUMBNAIL.stat().st_size < 2_000_000:
            break
    THUMBNAIL_DOCS.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(THUMBNAIL, THUMBNAIL_DOCS)
    return [THUMBNAIL, THUMBNAIL_DOCS]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("what", choices=["icon", "intro", "thumbnail", "all"])
    args = parser.parse_args()
    written: list[pathlib.Path] = []
    if args.what in ("icon", "all"):
        written += render_icons()
    if args.what in ("intro", "all"):
        written += render_intro()
    if args.what in ("thumbnail", "all"):
        written += render_thumbnail()
    for path in written:
        print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
