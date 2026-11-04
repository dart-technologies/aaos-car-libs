#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Build met_artwork_film.mp4 from the six Met Open Access (CC0) images.

About 6 s per work with a slow Ken Burns move that alternates zoom in, zoom out, pan left and
pan right; 0.8 s crossfades; a short fade in and out; a lower-third caption (title · artist ·
date) on a subtle gradient bar. Silent, 1280x720, 30 fps, yuv420p, H.264.

This ffmpeg has no drawtext (built without freetype), so captions are pre-rendered with Pillow,
and the moves and crossfades are rendered per frame with sub-pixel transforms (zoompan steps in
whole pixels and judders at this speed); ffmpeg encodes. FILM.json records every input's SHA-256,
the output's, and each work's start time so the audio guide and "Now playing (n/6)" can follow.
Run with `python3 -E -P` (Pillow lives in the user site, which -I would hide).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
MET = ROOT / "a2ui-automotive/demo/fixtures/met"
OUT = ROOT / "a2ui-host-carui/src/main/res/raw/met_artwork_film.mp4"
RECORD = MET / "FILM.json"
W, H, FPS = 1280, 720, 30
TOTAL, FADE = 36.0, 0.8           # film length; crossfade between works
FADE_IN, FADE_OUT = 0.6, 0.8      # from and to black
MOVES = ["zoom in", "zoom out", "pan left", "pan right", "zoom in", "zoom out"]
ZOOM, PAN = 0.08, 36              # scale gain per shot; pan travel in px each side of centre
BOX = (1040, 520)                 # the artwork fits inside this, above the caption
CENTER = (W / 2, 296)
BAR_Y, BAR_H, TEXT_X = 598, 62, 80
FONTS = ["/System/Library/Fonts/Avenir Next.ttc",
         "/System/Library/Fonts/HelveticaNeue.ttc",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
FACE = {"bold": 2, "medium": 5}
CREDIT = "The Met · Open Access (CC0)"
MAX_BYTES = 6_000_000


def font(size: int, face: str) -> ImageFont.FreeTypeFont:
    for path in FONTS:
        if pathlib.Path(path).exists():
            index = FACE[face] if path.endswith("Avenir Next.ttc") else 0
            return ImageFont.truetype(path, size, index=index)
    raise SystemExit("no usable font")


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ease(x: float) -> float:
    return 0.5 - 0.5 * math.cos(math.pi * min(1.0, max(0.0, x)))


def shot_seconds(count: int) -> float:
    return (TOTAL + (count - 1) * FADE) / count


def cover(art: Image.Image) -> Image.Image:
    """Blurred, dimmed, oversized backdrop so it can move without exposing an edge."""
    scale = max(W / art.width, H / art.height) * 1.2
    big = art.resize((round(art.width * scale), round(art.height * scale)), Image.BICUBIC)
    blurred = big.filter(ImageFilter.GaussianBlur(40))
    return Image.blend(Image.new("RGB", big.size, (8, 10, 14)), blurred, 0.34).convert("RGBA")


def framed(art: Image.Image) -> Image.Image:
    """The artwork at its base size with a soft shadow, padded so the shadow is not clipped."""
    scale = min(BOX[0] / art.width, BOX[1] / art.height)
    w, h = round(art.width * scale), round(art.height * scale)
    pad = 48
    shadow = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rectangle((pad + 6, pad + 16, pad + w - 6, pad + h + 10),
                                     fill=(0, 0, 0, 150))
    layer = shadow.filter(ImageFilter.GaussianBlur(20))
    layer.paste(art.resize((w, h), Image.LANCZOS).convert("RGBA"), (pad, pad))
    return layer


def caption(obj: dict) -> Image.Image:
    """Title · artist · date on one line, over a bar that fades out to the right."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    rest = " · ".join(part for part in (obj["artistDisplayName"], obj["objectDate"]) if part)
    size = 30
    while True:
        title_face, rest_face = font(size, "bold"), font(size - 4, "medium")
        title_w = draw.textlength(obj["title"], font=title_face)
        rest_w = draw.textlength("  ·  " + rest, font=rest_face)
        if title_w + rest_w <= W - 2 * TEXT_X - 40 or size <= 22:
            break
        size -= 1
    bar_w = min(W, TEXT_X + title_w + rest_w + 220)
    for x in range(round(bar_w)):
        alpha = 165 if x < bar_w - 260 else round(165 * (bar_w - x) / 260)
        draw.line([(x, BAR_Y), (x, BAR_Y + BAR_H)], fill=(6, 8, 12, alpha))
    draw.rectangle((0, BAR_Y, 6, BAR_Y + BAR_H), fill=(227, 179, 65, 255))
    baseline = BAR_Y + BAR_H / 2 + size * 0.36
    draw.text((TEXT_X, baseline), obj["title"], font=title_face, fill=(244, 246, 248, 255),
              anchor="ls")
    draw.text((TEXT_X + title_w, baseline), "  ·  " + rest, font=rest_face,
              fill=(201, 209, 217, 255), anchor="ls")
    return layer


def vignette() -> Image.Image:
    """A faint bottom shade and the small credit; present for the whole film."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for row in range(560, H):
        draw.line([(0, row), (W, row)], fill=(5, 7, 10, round(110 * (row - 560) / (H - 560))))
    face = font(15, "medium")
    draw.text((W - 40, H - 26), CREDIT, font=face, fill=(170, 178, 188, 255), anchor="rs")
    return layer


def with_alpha(layer: Image.Image, opacity: float) -> Image.Image:
    if opacity >= 1:
        return layer
    faded = layer.copy()
    faded.putalpha(layer.getchannel("A").point(lambda v: round(v * opacity)))
    return faded


def place(layer: Image.Image, scale: float, cx: float, cy: float) -> Image.Image:
    """layer, scaled about its centre and centred at (cx, cy), on a W x H canvas, sub-pixel."""
    data = (1 / scale, 0, layer.width / 2 - cx / scale, 0, 1 / scale, layer.height / 2 - cy / scale)
    return layer.transform((W, H), Image.AFFINE, data, resample=Image.BICUBIC,
                           fillcolor=(0, 0, 0, 0))


def motion(move: str, p: float) -> tuple[float, float]:
    """(scale, horizontal offset) at linear progress p in [0, 1]."""
    if move == "zoom in":
        return 1 + ZOOM * p, 0.0
    if move == "zoom out":
        return 1 + ZOOM * (1 - p), 0.0
    if move == "pan left":
        return 1 + ZOOM / 2, PAN * (1 - 2 * p)
    return 1 + ZOOM / 2, PAN * (2 * p - 1)


def shot_frame(shot: dict, tau: float, length: float) -> Image.Image:
    scale, dx = motion(shot["move"], tau / length)
    # The backdrop moves at a fraction of the artwork's speed: a little parallax.
    frame = place(shot["cover"], 1 + (scale - 1) * 0.4, W / 2 + dx * 0.4, H / 2)
    frame = Image.alpha_composite(frame, place(shot["framed"], scale, CENTER[0] + dx, CENTER[1]))
    frame = Image.alpha_composite(frame, VIGNETTE)
    first, last = shot["index"] == 0, shot["index"] == shot["count"] - 1
    start = 0.7 if first else FADE + 0.2
    end = length - (FADE_OUT + 0.5 if last else FADE + 0.2)
    opacity = min(ease((tau - start) / 0.6), ease((end - tau) / 0.5))
    if opacity > 0:
        frame = Image.alpha_composite(frame, with_alpha(shot["caption"], opacity))
    return frame.convert("RGB")


def render(objects: list[dict], out: pathlib.Path) -> list[dict]:
    count = len(objects)
    length = shot_seconds(count)
    step = length - FADE
    shots = []
    for index, obj in enumerate(objects):
        with Image.open(MET / obj["file"]) as source:
            art = source.convert("RGB")
        shots.append({"index": index, "count": count, "move": MOVES[index % len(MOVES)],
                      "cover": cover(art), "framed": framed(art), "caption": caption(obj)})
    encoder = subprocess.Popen([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
        "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-an",
        "-c:v", "libx264", "-preset", "slow", "-crf", "23", "-maxrate", "2000k",
        "-bufsize", "4000k", "-profile:v", "high", "-level:v", "4.0", "-pix_fmt", "yuv420p",
        "-g", "60", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
        "-movflags", "+faststart", str(out)], stdin=subprocess.PIPE)
    for n in range(round(TOTAL * FPS)):
        t = n / FPS
        index = min(count - 1, int(t // step))
        tau = t - index * step
        frame = shot_frame(shots[index], tau, length)
        if index > 0 and tau < FADE:  # cross-fade from the previous work's last FADE seconds
            outgoing = shot_frame(shots[index - 1], tau + step, length)
            frame = Image.blend(outgoing, frame, ease(tau / FADE))
        level = min(ease(t / FADE_IN), ease((TOTAL - t) / FADE_OUT))
        if level < 1:
            frame = Image.blend(Image.new("RGB", (W, H)), frame, level)
        encoder.stdin.write(frame.tobytes())
    encoder.stdin.close()
    if encoder.wait():
        raise SystemExit("ffmpeg failed")
    return [{"index": i + 1, "objectID": obj["objectID"], "title": obj["title"],
             "move": MOVES[i % len(MOVES)],
             "startMs": round(i * step * 1000),
             "fullyVisibleMs": round((i * step + (FADE if i else FADE_IN)) * 1000),
             "endMs": round(min(TOTAL, i * step + length) * 1000)}
            for i, obj in enumerate(objects)]


def probe(path: pathlib.Path) -> dict:
    result = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                             "format=duration:stream=codec_type,codec_name,profile,width,height,"
                             "pix_fmt,r_frame_rate", "-of", "json", str(path)],
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def contact_sheet(film: pathlib.Path, works: list[dict], sheet: pathlib.Path) -> None:
    """One frame from the middle of each work, 3 x 2."""
    picks = "+".join(f"eq(n\\,{round((w['fullyVisibleMs'] + w['endMs']) / 2000 * FPS)})"
                     for w in works)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(film), "-vf",
                    f"select='{picks}',scale=640:-2,tile=3x2", "-frames:v", "1",
                    "-fps_mode", "vfr", "-q:v", "3", str(sheet)], check=True)


VIGNETTE = None


def main() -> int:
    global VIGNETTE
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=pathlib.Path, default=OUT)
    parser.add_argument("--record", type=pathlib.Path, default=RECORD)
    parser.add_argument("--sheet", type=pathlib.Path, help="also write a 6-frame contact sheet")
    args = parser.parse_args()
    source = json.loads((MET / "SOURCE.json").read_text(encoding="utf-8"))
    objects = source["objects"]
    for obj in objects:
        if sha256(MET / obj["file"]) != obj["sha256"]:
            raise SystemExit(f"{obj['file']} does not match SOURCE.json")
    drawtext = subprocess.run(["ffmpeg", "-hide_banner", "-filters"], capture_output=True,
                              text=True, check=True).stdout
    captions = ("ffmpeg drawtext" if " drawtext " in drawtext else
                "pre-rendered with Pillow (this ffmpeg has no drawtext)")
    VIGNETTE = vignette()
    works = render(objects, args.out)
    info = probe(args.out)
    streams = info["streams"]
    if len(streams) != 1 or streams[0]["codec_type"] != "video":
        raise SystemExit("film must hold exactly one video stream and no audio")
    if (streams[0]["width"], streams[0]["height"], streams[0]["pix_fmt"]) != (W, H, "yuv420p"):
        raise SystemExit("film is not 1280x720 yuv420p")
    size = args.out.stat().st_size
    if size > MAX_BYTES:
        raise SystemExit(f"film is {size} bytes, over the {MAX_BYTES} budget")
    version = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True,
                             check=True).stdout.split("\n", 1)[0]
    record = {
        "schemaVersion": 1,
        "title": "Highlights from The Met's open collection",
        "license": "CC0 1.0 (inputs); the film adds only motion, layout and captions",
        "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        "attribution": CREDIT,
        "note": "Built from The Met Open Access images only; no Met video is used. Silent: the "
                "audio guide (met_guide_01..06) is separate narration timed to startMs.",
        "renderer": "scripts/render-met-artwork-film.py",
        "rendererSha256": sha256(pathlib.Path(__file__).resolve()),
        "encoder": version,
        "captions": captions,
        "font": " ".join(font(15, "medium").getname()),
        "parameters": {"width": W, "height": H, "fps": FPS, "pixelFormat": "yuv420p",
                       "totalSeconds": TOTAL, "shotSeconds": round(shot_seconds(len(objects)), 4),
                       "crossfadeSeconds": FADE, "fadeInSeconds": FADE_IN,
                       "fadeOutSeconds": FADE_OUT, "zoom": ZOOM, "panPixels": PAN,
                       "codec": "libx264 high@4.0 crf 23 maxrate 2000k, no audio"},
        "works": works,
        "inputs": [{"objectID": o["objectID"], "file": o["file"], "sha256": o["sha256"],
                    "title": o["title"], "artistDisplayName": o["artistDisplayName"],
                    "objectDate": o["objectDate"], "objectURL": o["objectURL"]}
                   for o in objects],
        "output": {"path": str(args.out.resolve().relative_to(ROOT)), "sha256": sha256(args.out),
                   "bytes": size, "durationSeconds": round(float(info["format"]["duration"]), 3),
                   "width": streams[0]["width"], "height": streams[0]["height"],
                   "codec": streams[0]["codec_name"], "profile": streams[0].get("profile")},
    }
    args.record.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n",
                           encoding="utf-8")
    if args.sheet:
        contact_sheet(args.out, works, args.sheet)
    print(f"wrote {args.out} ({size} bytes, {record['output']['durationSeconds']} s), "
          f"{args.record}" + (f", {args.sheet}" if args.sheet else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
