#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Render route_met.png from the committed OSRM geometry: no map tiles, car-UI styling."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
ROUTE = ROOT / "a2ui-automotive/demo/fixtures/route/hudson-yards-to-met.json"
OUT = ROOT / "a2ui-host-carui/src/main/res/drawable-nodpi/route_met.png"
W, H, SS = 1024, 576, 2  # output size; supersampling factor for anti-aliasing
FONTS = ["/System/Library/Fonts/Avenir Next.ttc",
         "/System/Library/Fonts/HelveticaNeue.ttc",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
FACE = {"bold": 2, "medium": 5}  # Avenir Next: Demi Bold, Medium
BG_TOP, BG_BOTTOM = (16, 22, 30), (9, 12, 17)
DOT, TEXT, MUTED = (28, 36, 46), (236, 241, 246), (139, 148, 158)
ROUTE_CORE, ROUTE_GLOW, PILL, PILL_EDGE = (90, 169, 255), (61, 139, 255), (21, 28, 37), (46, 56, 69)
END = (227, 179, 65)
CREDIT = "Route data © OpenStreetMap contributors (ODbL)"


def font(size: int, face: str) -> ImageFont.FreeTypeFont:
    for path in FONTS:
        if pathlib.Path(path).exists():
            index = FACE[face] if path.endswith("Avenir Next.ttc") else 0
            return ImageFont.truetype(path, size * SS, index=index)
    raise SystemExit("no usable font; pass one with --font")


def mercator(lon: float, lat: float) -> tuple[float, float]:
    return math.radians(lon), math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def project(points: list[tuple[float, float]], box: tuple[int, int, int, int]):
    xy = [mercator(lon, lat) for lon, lat in points]
    xs, ys = [p[0] for p in xy], [p[1] for p in xy]
    left, top, right, bottom = box
    scale = min((right - left) / (max(xs) - min(xs)), (bottom - top) / (max(ys) - min(ys)))
    off_x = left + ((right - left) - scale * (max(xs) - min(xs))) / 2
    off_y = top + ((bottom - top) - scale * (max(ys) - min(ys))) / 2
    return [((x - min(xs)) * scale * SS + off_x * SS, (max(ys) - y) * scale * SS + off_y * SS)
            for x, y in xy]


def pill(draw: ImageDraw.ImageDraw, anchor: tuple[float, float], text: str, side: str) -> None:
    face = font(20, "bold")
    l, t, r, b = draw.textbbox((0, 0), text, font=face)
    pad_x, pad_y = 14 * SS, 8 * SS
    w, h = r - l + 2 * pad_x, b - t + 2 * pad_y
    x, y = anchor
    x = {"left": x - w - 18 * SS, "right": x + 18 * SS}[side]
    y = y - h / 2
    x = max(12 * SS, min(x, W * SS - w - 12 * SS))
    y = max(12 * SS, min(y, H * SS - h - 40 * SS))
    draw.rounded_rectangle((x, y, x + w, y + h), radius=h / 2, fill=PILL, outline=PILL_EDGE,
                           width=2 * SS)
    draw.text((x + pad_x - l, y + pad_y - t), text, font=face, fill=TEXT)


def render(route: dict) -> Image.Image:
    image = Image.new("RGB", (W * SS, H * SS))
    draw = ImageDraw.Draw(image)
    for row in range(H * SS):
        k = row / (H * SS - 1)
        draw.line([(0, row), (W * SS, row)], fill=tuple(
            round(a + (b - a) * k) for a, b in zip(BG_TOP, BG_BOTTOM)))
    for gx in range(24, W, 32):
        for gy in range(24, H, 32):
            draw.ellipse((gx * SS - SS, gy * SS - SS, gx * SS + SS, gy * SS + SS), fill=DOT)

    points = project([tuple(p) for p in route["geometry"]["coordinates"]], (330, 70, 980, 500))
    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).line(points, fill=ROUTE_GLOW + (150,), width=22 * SS, joint="curve")
    glow = glow.filter(ImageFilter.GaussianBlur(9 * SS))
    image.paste(glow, (0, 0), glow)
    draw = ImageDraw.Draw(image)
    draw.line(points, fill=ROUTE_CORE, width=7 * SS, joint="curve")

    (sx, sy), (ex, ey) = points[0], points[-1]
    draw.ellipse((sx - 11 * SS, sy - 11 * SS, sx + 11 * SS, sy + 11 * SS), fill=BG_BOTTOM,
                 outline=TEXT, width=4 * SS)
    draw.ellipse((ex - 15 * SS, ey - 15 * SS, ex + 15 * SS, ey + 15 * SS), fill=END,
                 outline=BG_BOTTOM, width=4 * SS)
    draw.ellipse((ex - 5 * SS, ey - 5 * SS, ex + 5 * SS, ey + 5 * SS), fill=BG_BOTTOM)
    pill(draw, (sx, sy), "Hudson Yards", "left")
    pill(draw, (ex, ey), "The Met", "right" if ex < W * SS * 0.8 else "left")

    small, big = font(13, "medium"), font(34, "bold")
    miles = route["distanceMeters"] / 1609.344
    draw.text((40 * SS, 56 * SS), "ROUTE PREVIEW", font=small, fill=MUTED)
    draw.text((40 * SS, 78 * SS), f"{miles:.1f} mi", font=big, fill=TEXT)
    draw.text((40 * SS, 126 * SS), "Hudson Yards to", font=font(17, "medium"), fill=MUTED)
    draw.text((40 * SS, 148 * SS), "The Met Fifth Avenue", font=font(17, "medium"), fill=MUTED)

    # North arrow, bottom left.
    nx, ny = 52 * SS, 500 * SS
    draw.polygon([(nx, ny - 22 * SS), (nx - 9 * SS, ny), (nx + 9 * SS, ny)], fill=MUTED)
    draw.text((nx - 5 * SS, ny + 4 * SS), "N", font=small, fill=MUTED)

    l, t, r, b = draw.textbbox((0, 0), CREDIT, font=small)
    draw.text((W * SS - (r - l) - 16 * SS, H * SS - (b - t) - 14 * SS - t), CREDIT, font=small,
              fill=MUTED)
    return image.resize((W, H), Image.LANCZOS)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--route", type=pathlib.Path, default=ROUTE)
    parser.add_argument("--out", type=pathlib.Path, default=OUT)
    parser.add_argument("--font", help="TrueType font to use instead of the defaults")
    args = parser.parse_args()
    if args.font:
        FONTS.insert(0, args.font)
    route = json.loads(args.route.read_text(encoding="utf-8"))
    render(route).save(args.out, format="PNG", optimize=True)
    print(f"wrote {args.out} ({W}x{H}, {len(route['geometry']['coordinates'])} route points)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
