#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Render the recorder's overlay PNGs: panel labels and per-scenario title cards.

The ffmpeg used for recording may lack drawtext (no freetype), so text is rendered here once,
committed under a2ui-automotive/demo/cards/, and overlaid as images by run-nyc-demo.py.

  render-demo-cards.py labels
  render-demo-cards.py title --title T --subtitle S [--footer F] --out card.png
"""

from __future__ import annotations

import argparse
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
CARDS = ROOT / "a2ui-automotive/demo/cards"
FONTS = ["/System/Library/Fonts/Avenir Next.ttc",
         "/System/Library/Fonts/HelveticaNeue.ttc",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
FACE = {"bold": 0, "demi": 2, "medium": 5}
LABELS = {"driver": ("Driver display", (90, 169, 255)),
          "passenger": ("Passenger display", (227, 179, 65))}


def font(size: int, face: str) -> ImageFont.FreeTypeFont:
    for path in FONTS:
        if pathlib.Path(path).exists():
            index = FACE[face] if path.endswith("Avenir Next.ttc") else 0
            return ImageFont.truetype(path, size, index=index)
    raise SystemExit("no usable font")


GLYPH_FALLBACK = "/System/Library/Fonts/SFNS.ttf"  # has arrows Avenir Next lacks


def missing(face: ImageFont.FreeTypeFont, char: str) -> bool:
    """True when face would draw its .notdef box for char."""
    return face.getmask(char).getbbox() == face.getmask("￿").getbbox()


def runs(text: str, face: ImageFont.FreeTypeFont, size: int):
    """(text, font) runs: face, except glyphs it lacks, drawn with the fallback font."""
    fallback = ImageFont.truetype(GLYPH_FALLBACK, size) \
        if pathlib.Path(GLYPH_FALLBACK).exists() else face
    out: list[tuple[str, ImageFont.FreeTypeFont]] = []
    for char in text:
        chosen = fallback if char.strip() and missing(face, char) else face
        if out and out[-1][1] is chosen:
            out[-1] = (out[-1][0] + char, chosen)
        else:
            out.append((char, chosen))
    return out


def draw_text(draw: ImageDraw.ImageDraw, xy, text: str, size: int, face: str, fill) -> None:
    x, y = xy
    base = font(size, face)
    baseline = y + base.getmetrics()[0]
    for chunk, chunk_font in runs(text, base, size):
        draw.text((x, baseline), chunk, font=chunk_font, fill=fill, anchor="ls")
        x += draw.textlength(chunk, font=chunk_font)


def text_width(draw: ImageDraw.ImageDraw, text: str, size: int, face: str) -> float:
    return sum(draw.textlength(chunk, font=f) for chunk, f in runs(text, font(size, face), size))


def label(text: str, accent: tuple[int, int, int]) -> Image.Image:
    face = font(24, "demi")
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    left, top, right, bottom = probe.textbbox((0, 0), text, font=face)
    image = Image.new("RGBA", (right - left + 26, 34), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((0, 12, 10, 22), fill=accent + (255,))
    draw.text((22 - left, (34 - (bottom - top)) // 2 - top), text, font=face,
              fill=(201, 209, 217, 255))
    return image


def title_card(title: str, subtitle: str, footer: str) -> Image.Image:
    width, height = 1920, 1080
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    top, bottom = (16, 22, 30), (8, 10, 14)
    for row in range(height):
        k = row / (height - 1)
        draw.line([(0, row), (width, row)],
                  fill=tuple(round(a + (b - a) * k) for a, b in zip(top, bottom)))
    draw.rectangle((160, 404, 256, 410), fill=(227, 179, 65))
    size = 104
    while size > 48 and text_width(draw, title, size, "bold") > width - 320:
        size -= 2  # fit long titles within the margins
    draw_text(draw, (160, 436 + (104 - size) // 2), title, size, "bold", (244, 246, 248))
    draw_text(draw, (164, 584), subtitle, 40, "medium", (157, 167, 179))
    if footer:
        draw_text(draw, (164, 960), footer, 26, "medium", (110, 118, 129))
    return image


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("labels")
    title = sub.add_parser("title")
    title.add_argument("--title", required=True)
    title.add_argument("--subtitle", required=True)
    title.add_argument("--footer", default="")
    title.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == "labels":
        CARDS.mkdir(parents=True, exist_ok=True)
        for name, (text, accent) in LABELS.items():
            path = CARDS / f"label-{name}.png"
            label(text, accent).save(path, format="PNG", optimize=True)
            print(f"wrote {path}")
    else:
        title_card(args.title, args.subtitle, args.footer).save(args.out, format="PNG",
                                                                 optimize=True)
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
