#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Render the recorder's overlay PNGs: panel labels, the corner disclaimer, title cards, and the
preamble and outro cards it builds from frames of the recording itself.

The ffmpeg used for recording may lack drawtext (no freetype), so text is rendered here with
Pillow and overlaid as images by run-nyc-demo.py. Labels, disclaimer and title cards are
committed under a2ui-automotive/demo/cards/; preamble and outro cards are rendered per recording.

  render-demo-cards.py labels
  render-demo-cards.py disclaimer --text T --out disclaimer.png
  render-demo-cards.py title --title T --subtitle S [--footer F] --out card.png
  render-demo-cards.py preamble --style unsafe|useless|answer [--frame F] [--chip X]
                                [--lock-text L] --out card.png
  render-demo-cards.py outro --spec spec.json --out card.png
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

ROOT = pathlib.Path(__file__).resolve().parents[1]
CARDS = ROOT / "a2ui-automotive/demo/cards"
FONTS = ["/System/Library/Fonts/Avenir Next.ttc",
         "/System/Library/Fonts/HelveticaNeue.ttc",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
FACE = {"bold": 0, "demi": 2, "medium": 5}
W, H = 1920, 1080
TEXT, MUTED, FAINT = (244, 246, 248), (157, 167, 179), (110, 118, 129)
GOLD = (227, 179, 65)
PARKED, DRIVING, ALERT = (38, 133, 84), (36, 87, 168), (205, 48, 48)  # the slim chip colours
PANEL, EDGE = (21, 27, 35), (44, 53, 64)
MUSE = (24, 119, 242)  # the text badge's accent; no logo artwork is drawn
LABELS = {"driver": ("Driver", (90, 169, 255)), "passenger": ("Passenger", (227, 179, 65))}
# The preamble frame: the driver display at 1280x720, centred with the chip above it. No caption
# is baked in: the recorder's burned-in captions carry the narration below the window.
FRAME_BOX = (320, 215, 1280, 720)


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


def centred(draw: ImageDraw.ImageDraw, cx: float, y: float, text: str, size: int, face: str,
            fill) -> None:
    draw_text(draw, (cx - text_width(draw, text, size, face) / 2, y), text, size, face, fill)


def wrap(draw: ImageDraw.ImageDraw, text: str, size: int, face: str, width: float) -> list[str]:
    lines: list[str] = []
    for word in text.split():
        if lines and text_width(draw, f"{lines[-1]} {word}", size, face) <= width:
            lines[-1] = f"{lines[-1]} {word}"
        else:
            lines.append(word)
    return lines


def label(text: str, accent: tuple[int, int, int]) -> Image.Image:
    face = font(22, "medium")
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    left, top, right, bottom = probe.textbbox((0, 0), text, font=face)
    image = Image.new("RGBA", (right - left + 22, 34), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((0, 13, 8, 21), fill=accent + (255,))
    draw.text((18 - left, (34 - (bottom - top)) // 2 - top), text, font=face,
              fill=(160, 168, 178, 255))
    return image


def disclaimer(text: str) -> Image.Image:
    """One small, low-contrast line for the canvas margin."""
    face = font(18, "medium")
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    left, top, right, bottom = probe.textbbox((0, 0), text, font=face)
    image = Image.new("RGBA", (right - left + 4, bottom - top + 8), (0, 0, 0, 0))
    ImageDraw.Draw(image).text((2 - left, 4 - top), text, font=face, fill=(104, 112, 122, 255))
    return image


def backdrop() -> Image.Image:
    image = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(image)
    top, bottom = (16, 22, 30), (8, 10, 14)
    for row in range(H):
        k = row / (H - 1)
        draw.line([(0, row), (W, row)],
                  fill=tuple(round(a + (b - a) * k) for a, b in zip(top, bottom)))
    return image


def title_card(title: str, subtitle: str, footer: str) -> Image.Image:
    image = backdrop()
    draw = ImageDraw.Draw(image)
    draw.rectangle((160, 404, 256, 410), fill=GOLD)
    size = 104
    while size > 48 and text_width(draw, title, size, "bold") > W - 320:
        size -= 2  # fit long titles within the margins
    draw_text(draw, (160, 436 + (104 - size) // 2), title, size, "bold", TEXT)
    draw_text(draw, (164, 584), subtitle, 40, "medium", MUTED)
    if footer:
        draw_text(draw, (164, 960), footer, 26, "medium", FAINT)
    return image


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def open_frame(path: str | None, size: tuple[int, int]) -> Image.Image | None:
    """The frame scaled to cover size, or None when it is missing or unreadable."""
    if not path:
        return None
    try:
        with Image.open(path) as source:
            frame = source.convert("RGB")
    except (OSError, ValueError):
        return None
    return ImageOps.fit(frame, size, Image.LANCZOS)


def placeholder(size: tuple[int, int]) -> Image.Image:
    return Image.new("RGB", size, PANEL)


def framed(image: Image.Image, tile: Image.Image, xy: tuple[int, int], radius: int,
           outline=EDGE, width: int = 2, shadow: bool = False) -> None:
    """tile pasted onto image at xy with rounded corners, an outline and an optional shadow."""
    x, y = xy
    w, h = tile.size
    if shadow:
        layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle((x + 8, y + 18, x + w - 8, y + h + 14), radius,
                                                fill=(0, 0, 0, 170))
        blurred = layer.filter(ImageFilter.GaussianBlur(22))
        image.paste(Image.alpha_composite(image.convert("RGBA"), blurred).convert("RGB"))
    image.paste(tile, xy, rounded_mask(tile.size, radius))
    ImageDraw.Draw(image).rounded_rectangle((x, y, x + w - 1, y + h - 1), radius,
                                            outline=outline, width=width)


def chip(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str, fill, size: int = 26) -> None:
    """A status pill: a dot and the text, as the car's slim Parked/Driving chip draws it."""
    x, y = xy
    face = font(size, "demi")
    height, pad, dot = size + 22, 22, 10
    width = pad + dot + 12 + draw.textlength(text, font=face) + pad
    draw.rounded_rectangle((x, y, x + width, y + height), height // 2, fill=fill)
    mid = y + height / 2
    draw.ellipse((x + pad, mid - dot / 2, x + pad + dot, mid + dot / 2), fill=TEXT)
    draw.text((x + pad + dot + 12, mid), text, font=face, fill=TEXT, anchor="lm")


def lock(draw: ImageDraw.ImageDraw, cx: float, cy: float, fill=(236, 239, 242)) -> None:
    """A padlock drawn from primitives, centred on (cx, cy)."""
    body = (cx - 62, cy - 26, cx + 62, cy + 70)
    radius, stroke = 40, 16
    top = body[1] - 6
    draw.arc((cx - radius, top - radius - 30, cx + radius, top + radius - 30), 180, 360,
             fill=fill, width=stroke)
    for side in (-1, 1):
        x = cx + side * (radius - stroke / 2)
        draw.line([(x, top - 30), (x, body[1] + 4)], fill=fill, width=stroke)
    draw.rounded_rectangle(body, 14, fill=fill)
    draw.ellipse((cx - 11, cy + 6, cx + 11, cy + 28), fill=(40, 44, 50))
    draw.rectangle((cx - 4, cy + 24, cx + 4, cy + 46), fill=(40, 44, 50))


def preamble_card(style: str, frame_path: str | None, chip_text: str,
                  lock_text: str) -> Image.Image:
    """One problem-statement card around a real driver-display frame.

    unsafe   the parked full screen, tinted red under a red driving chip;
    useless  the same frame greyed out under a lock and lock_text;
    answer   the real pruned driving frame, untouched.
    """
    image = backdrop()
    x, y, w, h = FRAME_BOX
    frame = open_frame(frame_path, (w, h)) or placeholder((w, h))
    outline, chip_fill = EDGE, DRIVING
    if style == "unsafe":
        frame = Image.blend(frame, Image.new("RGB", frame.size, (190, 28, 28)), 0.17)
        outline, chip_fill = ALERT, ALERT
    elif style == "useless":
        frame = ImageEnhance.Brightness(ImageOps.grayscale(frame).convert("RGB")).enhance(0.24)
        frame = frame.filter(ImageFilter.GaussianBlur(3))
        chip_fill = (72, 80, 92)
    elif style != "answer":
        raise SystemExit(f"unknown preamble style {style!r}")
    framed(image, frame, (x, y), 22, outline=outline, width=3, shadow=True)
    draw = ImageDraw.Draw(image)
    if style == "useless":
        lock(draw, x + w / 2, y + h / 2 - 60)
        if lock_text:
            centred(draw, x + w / 2, y + h / 2 + 52, lock_text, 40, "medium", TEXT)
    if chip_text:
        chip(draw, (x, y - 70), chip_text, chip_fill)
    return image


def outro_card(spec: dict) -> Image.Image:
    """The closing card: the journey as vehicle-state segments with real thumbnails of each beat
    (driver above, passenger below the driving segments), the architecture, and the footer."""
    image = backdrop()
    draw = ImageDraw.Draw(image)
    left, right = 96, W - 96
    segments = spec["segments"]
    slots = sum(max(1, int(s.get("slots", 1))) for s in segments)
    slot = (right - left) / slots
    spans, cursor = [], float(left)
    for segment in segments:
        width = slot * max(1, int(segment.get("slots", 1)))
        spans.append((cursor, cursor + width))
        cursor += width

    draw_text(draw, (left, 70), spec.get("journeyTitle", "THE DRIVE"), 20, "demi", GOLD)
    thumb_w = min(232, int(slot) - 24)
    thumb_h = round(thumb_w * 9 / 16)
    thumbs_top, strip_top = 112, 296
    by_segment: dict[int, list[dict]] = {}
    for beat in spec.get("driver", []):
        by_segment.setdefault(int(beat["segment"]), []).append(beat)
    for index, beats in by_segment.items():
        start, end = spans[index]
        step = (end - start) / len(beats)
        for n, beat in enumerate(beats):
            cx = start + step * (n + 0.5)
            tile = open_frame(beat.get("image"), (thumb_w, thumb_h)) or placeholder(
                (thumb_w, thumb_h))
            framed(image, tile, (round(cx - thumb_w / 2), thumbs_top), 10)
            centred(draw, cx, thumbs_top + thumb_h + 12, beat["label"], 20, "medium", MUTED)
    for (start, end), segment in zip(spans, segments):
        fill = PARKED if segment["state"] == "park" else DRIVING
        draw.rounded_rectangle((start + 3, strip_top, end - 3, strip_top + 44), 22, fill=fill)
        centred(draw, (start + end) / 2, strip_top + 9, segment["label"], 20, "demi", TEXT)
    pas_w, pas_h = 72, 108
    for beat in spec.get("passenger", []):
        start, end = spans[int(beat["segment"])]
        text = beat["label"]
        total = pas_w + 14 + text_width(draw, text, 20, "medium")
        px = round((start + end) / 2 - total / 2)
        py = strip_top + 44 + 18
        tile = open_frame(beat.get("image"), (pas_w, pas_h)) or placeholder((pas_w, pas_h))
        framed(image, tile, (px, py), 8)
        draw_text(draw, (px + pas_w + 14, py + pas_h / 2 - 14), text, 20, "medium", MUTED)

    draw_text(draw, (left, 540), spec.get("architectureTitle", "THE ARCHITECTURE"), 20, "demi",
              GOLD)
    boxes = spec.get("architecture", [])
    gap = 64
    box_w = ((right - left) - gap * (len(boxes) - 1)) / max(1, len(boxes))
    box_top, box_h, pad = 580, 214, 22
    icon = None
    if spec.get("icon") and pathlib.Path(spec["icon"]).is_file():
        with Image.open(spec["icon"]) as source:
            icon = source.convert("RGBA").resize((24, 24), Image.LANCZOS)
    for n, box in enumerate(boxes):
        bx = left + n * (box_w + gap)
        draw.rounded_rectangle((bx, box_top, bx + box_w, box_top + box_h), 16, fill=PANEL,
                               outline=EDGE, width=2)
        ty = box_top + pad
        if box.get("badge"):
            face = font(22, "demi")
            inner = (34 if icon else 0) + draw.textlength(box["badge"], font=face)
            draw.rounded_rectangle((bx + pad, ty, bx + pad + inner + 28, ty + 40), 20, fill=MUSE)
            tx = bx + pad + 14
            if icon:
                image.paste(icon, (round(tx), ty + 8), icon)
                tx += 34
            draw.text((tx, ty + 20), box["badge"], font=face, fill=TEXT, anchor="lm")
            if box.get("tag"):
                draw_text(draw, (bx + pad + inner + 40, ty + 6), box["tag"], 20, "medium",
                          MUTED)
            ty += 56
        if box.get("title"):
            for line in wrap(draw, box["title"], 25, "demi", box_w - 2 * pad):
                draw_text(draw, (bx + pad, ty), line, 25, "demi", TEXT)
                ty += 32
            ty += 8
        for item in box.get("lines", []):
            for line in wrap(draw, item, 20, "medium", box_w - 2 * pad):
                draw_text(draw, (bx + pad, ty), line, 20, "medium", MUTED)
                ty += 27
        if n < len(boxes) - 1:
            ay = box_top + box_h / 2
            ax0, ax1 = bx + box_w + 12, bx + box_w + gap - 12
            draw.line([(ax0, ay), (ax1 - 10, ay)], fill=FAINT, width=3)
            draw.polygon([(ax1, ay), (ax1 - 14, ay - 8), (ax1 - 14, ay + 8)], fill=FAINT)

    footer = spec.get("footer", [])
    if footer:
        draw_text(draw, (left, 872), footer[0], 46, "bold", TEXT)
    if len(footer) > 1:
        draw_text(draw, (left + 2, 944), footer[1], 26, "medium", MUTED)
    return image


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("labels")
    note = sub.add_parser("disclaimer")
    note.add_argument("--text", required=True)
    note.add_argument("--out", type=pathlib.Path, required=True)
    title = sub.add_parser("title")
    title.add_argument("--title", required=True)
    title.add_argument("--subtitle", required=True)
    title.add_argument("--footer", default="")
    title.add_argument("--out", type=pathlib.Path, required=True)
    pre = sub.add_parser("preamble")
    pre.add_argument("--style", required=True, choices=["unsafe", "useless", "answer"])
    pre.add_argument("--frame", help="driver-display frame; a dark panel stands in if unreadable")
    pre.add_argument("--chip", default="")
    pre.add_argument("--lock-text", default="")
    pre.add_argument("--out", type=pathlib.Path, required=True)
    outro = sub.add_parser("outro")
    outro.add_argument("--spec", type=pathlib.Path, required=True)
    outro.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == "labels":
        CARDS.mkdir(parents=True, exist_ok=True)
        for name, (text, accent) in LABELS.items():
            path = CARDS / f"label-{name}.png"
            label(text, accent).save(path, format="PNG", optimize=True)
            print(f"wrote {path}")
        return 0
    if args.command == "disclaimer":
        image = disclaimer(args.text)
    elif args.command == "title":
        image = title_card(args.title, args.subtitle, args.footer)
    elif args.command == "preamble":
        image = preamble_card(args.style, args.frame, args.chip, args.lock_text)
    else:
        image = outro_card(json.loads(args.spec.read_text(encoding="utf-8")))
    image.save(args.out, format="PNG", optimize=True)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
