#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Build met_artwork_film.mp4 (and its portrait sibling) from the six Met Open Access (CC0) images.

About 6 s per work with a slow Ken Burns move that alternates zoom in, zoom out, pan left and
pan right; 0.8 s crossfades; a short fade in and out; a lower-third caption (title · artist ·
date) on a subtle gradient bar. Silent, 1280x720, 30 fps, yuv420p, H.264.

--orientation portrait (default 720x1080, written to met_artwork_film_portrait.mp4 and
FILM_PORTRAIT.json) fills the passenger display's 2:3 screen instead: each painting covers the
frame edge to edge with an eased pan across the wide works and a slow zoom with a drift on the
near-square ones, and the caption sits at the bottom on a soft gradient. Its timing is the
landscape film's, and the renderer refuses to finish if any work's startMs differs from
FILM.json's (the audio guide is timed to those).

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
OUT_PORTRAIT = ROOT / "a2ui-host-carui/src/main/res/raw/met_artwork_film_portrait.mp4"
RECORD_PORTRAIT = MET / "FILM_PORTRAIT.json"
W, H, FPS = 1280, 720, 30         # a portrait run reassigns W and H (default 720x1080)
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

# Portrait: every painting covers the frame (no bars). Keyed by objectID. A pan holds the cover
# scale times PAN_ZOOM and travels `travel` px (at 720 wide) centred on `u` at mid-shot; "pan
# left" means the picture slides left, as in MOVES. A zoom eases its scale from z[0] to z[1]
# while the view centre drifts from `start` to `end` (u, v as fractions of the trimmed image).
# Each centre is clamped so the frame never shows past the trimmed edge.
PORTRAIT_MOVES = {
    436535: {"move": "pan right", "u": 0.66, "travel": 210},  # cypresses, then the wheat
    437881: {"move": "zoom in", "z": (1.0, 1.10), "start": (0.45, 0.5), "end": (0.47, 0.38)},
    437394: {"move": "zoom out", "z": (1.12, 1.0), "start": (0.41, 0.40), "end": (0.42, 0.5)},
    435809: {"move": "pan left", "u": 0.50, "travel": 250},   # scythers, then the resting group
    436105: {"move": "pan right", "u": 0.50, "travel": 250},  # Socrates, then the hemlock cup
    435868: {"move": "pan left", "u": 0.54, "travel": 230},   # across the three players
}
INSET = {436535: 0.045}           # fraction trimmed from each side: photographed edge, black mat
INSET_DEFAULT = 0.025
PAN_ZOOM = 1.03                   # pans sit a little over cover, so sub-pixel motion shows no edge
MAX_SPEED = 70                    # px/s at 720 wide; the renderer fails a faster move
PORTRAIT_CODEC = {"crf": 23, "maxrate": "1300k", "bufsize": "2600k"}


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


def contact_sheet(film: pathlib.Path, works: list[dict], sheet: pathlib.Path,
                  width: int = 640) -> None:
    """One frame from the middle of each work, 3 x 2."""
    picks = "+".join(f"eq(n\\,{round((w['fullyVisibleMs'] + w['endMs']) / 2000 * FPS)})"
                     for w in works)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(film), "-vf",
                    f"select='{picks}',scale={width}:-2,tile=3x2", "-frames:v", "1",
                    "-fps_mode", "vfr", "-q:v", "3", str(sheet)], check=True)


def trimmed(art: Image.Image, object_id: int) -> Image.Image:
    inset = INSET.get(object_id, INSET_DEFAULT)
    dx, dy = round(art.width * inset), round(art.height * inset)
    return art.crop((dx, dy, art.width - dx, art.height - dy))


def portrait_shot(art: Image.Image, object_id: int) -> dict:
    """The trimmed painting upscaled once (Lanczos, then a light unsharp mask) to its largest
    size in the shot, so each frame only resamples it down a little."""
    spec = PORTRAIT_MOVES[object_id]
    art = trimmed(art, object_id)
    zooms = spec.get("z", (PAN_ZOOM, PAN_ZOOM))
    base = max(W / art.width, H / art.height) * max(zooms)
    big = art.resize((round(art.width * base), round(art.height * base)), Image.LANCZOS)
    big = big.filter(ImageFilter.UnsharpMask(radius=1.4, percent=45, threshold=2))
    return {"spec": spec, "big": big, "zooms": zooms}


def camera(shot: dict, p: float) -> tuple[float, float, float]:
    """(k, cx, cy) at linear progress p: k maps the upscaled painting into the frame and (cx, cy)
    is the view centre in its pixels, clamped so the frame stays covered."""
    spec, big = shot["spec"], shot["big"]
    e = ease(p)
    z0, z1 = shot["zooms"]
    k = (z0 + (z1 - z0) * e) / max(z0, z1)
    hu, hv = W / (2 * k * big.width), H / (2 * k * big.height)
    if spec["move"].startswith("pan"):
        half = spec["travel"] * W / 720 / 2 / (big.width * k)  # half the travel, in u
        mid = min(1 - hu - half, max(hu + half, spec["u"]))
        sign = 1 if spec["move"] == "pan left" else -1        # picture slides left: view goes right
        u, v = mid + sign * half * (2 * e - 1), 0.5
    else:
        (u0, v0), (u1, v1) = spec["start"], spec["end"]
        u, v = u0 + (u1 - u0) * e, v0 + (v1 - v0) * e
    u, v = min(1 - hu, max(hu, u)), min(1 - hv, max(hv, v))
    return k, u * big.width, v * big.height


def view(shot: dict, p: float) -> Image.Image:
    k, cx, cy = camera(shot, p)
    data = (1 / k, 0, cx - W / 2 / k, 0, 1 / k, cy - H / 2 / k)
    return shot["big"].transform((W, H), Image.AFFINE, data, resample=Image.BICUBIC)


def peak_speed(shot: dict, length: float) -> float:
    """Fastest on-screen motion of the frame's centre or corners over the shot, px/s at 720 wide."""
    n = round(length * FPS)
    points = [(0, 0), (W, 0), (0, H), (W, H), (W / 2, H / 2)]

    def under(i: int) -> tuple[float, list[tuple[float, float]]]:
        k, cx, cy = camera(shot, i / n)
        return k, [((x - W / 2) / k + cx, (y - H / 2) / k + cy) for x, y in points]

    fastest, (_, prev) = 0.0, under(0)
    for i in range(1, n + 1):
        k, cur = under(i)
        for (x0, y0), (x1, y1) in zip(prev, cur):
            fastest = max(fastest, math.hypot(x1 - x0, y1 - y0) * k * FPS)
        prev = cur
    return fastest * 720 / W


def balanced(text: str, face: ImageFont.FreeTypeFont, draw: ImageDraw.ImageDraw) -> list[str]:
    words = text.split()
    cuts = [[" ".join(words[:n]), " ".join(words[n:])] for n in range(1, len(words))]
    return min(cuts, key=lambda lines: max(draw.textlength(line, font=face) for line in lines),
               default=[text])


def caption_p(obj: dict) -> Image.Image:
    """Portrait caption: a soft bottom gradient, the title in the bold face (two balanced lines if
    one will not fit), artist · date beneath at lower contrast, and a small gold accent. The
    credit line under it belongs to the film-long vignette_p."""
    s = W / 720
    margin = round(56 * s)
    width = W - 2 * margin
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    title, lines = obj["title"], None
    for size in (36, 35, 34):
        title_face = font(round(size * s), "bold")
        if draw.textlength(title, font=title_face) <= width:
            lines = [title]
            break
    if lines is None:
        size = 36
        title_face = font(round(size * s), "bold")
        lines = balanced(title, title_face, draw)
    rest = " · ".join(part for part in (obj["artistDisplayName"], obj["objectDate"]) if part)
    rest_size = 24
    while draw.textlength(rest, font=font(round(rest_size * s), "medium")) > width:
        rest_size -= 1
    rest_face = font(round(rest_size * s), "medium")
    rest_base = H - round(48 * s) - round(44 * s)
    title_bases = [rest_base - round(46 * s) - round(44 * s) * (len(lines) - 1 - i)
                   for i in range(len(lines))]
    accent_top = title_bases[0] - round(size * 0.72 * s) - round(22 * s)
    top = accent_top - round(170 * s)
    for row in range(top, H):
        draw.line([(0, row), (W, row)],
                  fill=(4, 6, 9, round(150 * ease((row - top) / (H - top)) ** 1.2)))
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shade = ImageDraw.Draw(shadow)
    for line, base in zip(lines, title_bases):
        shade.text((margin, base + round(2 * s)), line, font=title_face, fill=(0, 0, 0, 150),
                   anchor="ls")
    shade.text((margin, rest_base + round(2 * s)), rest, font=rest_face, fill=(0, 0, 0, 120),
               anchor="ls")
    layer = Image.alpha_composite(layer, shadow.filter(ImageFilter.GaussianBlur(5 * s)))
    draw = ImageDraw.Draw(layer)
    draw.rectangle((margin, accent_top, margin + round(40 * s), accent_top + round(4 * s)),
                   fill=(227, 179, 65, 255))
    for line, base in zip(lines, title_bases):
        draw.text((margin, base), line, font=title_face, fill=(244, 246, 248, 255), anchor="ls")
    draw.text((margin, rest_base), rest, font=rest_face, fill=(190, 198, 207, 235), anchor="ls")
    return layer


def vignette_p() -> Image.Image:
    """Portrait: a faint bottom shade and the small credit, present for the whole film."""
    s = W / 720
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    top = H - round(240 * s)
    for row in range(top, H):
        draw.line([(0, row), (W, row)], fill=(4, 6, 9, round(110 * ease((row - top) / (H - top)))))
    face, spot = font(round(17 * s), "medium"), (round(56 * s), H - round(48 * s))
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((spot[0], spot[1] + round(2 * s)), CREDIT, font=face,
                                fill=(0, 0, 0, 170), anchor="ls")
    layer = Image.alpha_composite(layer, shadow.filter(ImageFilter.GaussianBlur(4 * s)))
    ImageDraw.Draw(layer).text(spot, CREDIT, font=face, fill=(198, 205, 213, 240), anchor="ls")
    return layer


def portrait_frame(shot: dict, tau: float, length: float) -> Image.Image:
    frame = Image.alpha_composite(view(shot, tau / length).convert("RGBA"), VIGNETTE)
    first, last = shot["index"] == 0, shot["index"] == shot["count"] - 1
    start = 0.7 if first else FADE + 0.2
    end = length - (FADE_OUT + 0.5 if last else FADE + 0.2)
    opacity = min(ease((tau - start) / 0.6), ease((end - tau) / 0.5))
    if opacity > 0:
        frame = Image.alpha_composite(frame, with_alpha(shot["caption"], opacity))
    return frame.convert("RGB")


def render_portrait(objects: list[dict], out: pathlib.Path) -> tuple[list[dict], float]:
    """The same timeline as render(): same shot length, crossfades and fades, so the same startMs."""
    count = len(objects)
    length = shot_seconds(count)
    step = length - FADE
    works = [{"index": i + 1, "objectID": obj["objectID"], "title": obj["title"],
              "move": PORTRAIT_MOVES[obj["objectID"]]["move"],
              "startMs": round(i * step * 1000),
              "fullyVisibleMs": round((i * step + (FADE if i else FADE_IN)) * 1000),
              "endMs": round(min(TOTAL, i * step + length) * 1000)}
             for i, obj in enumerate(objects)]
    check_timing(works)
    shots = []
    for index, obj in enumerate(objects):
        with Image.open(MET / obj["file"]) as source:
            art = source.convert("RGB")
        shots.append({**portrait_shot(art, obj["objectID"]), "index": index, "count": count,
                      "caption": caption_p(obj)})
    fastest = max(peak_speed(shot, length) for shot in shots)
    if fastest > MAX_SPEED:
        raise SystemExit(f"a portrait move reaches {fastest:.0f} px/s, over {MAX_SPEED}")
    encoder = subprocess.Popen([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
        "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-an",
        "-c:v", "libx264", "-preset", "slow", "-crf", str(PORTRAIT_CODEC["crf"]),
        "-maxrate", PORTRAIT_CODEC["maxrate"], "-bufsize", PORTRAIT_CODEC["bufsize"],
        "-profile:v", "high", "-level:v", "4.0", "-pix_fmt", "yuv420p",
        "-g", "60", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
        "-movflags", "+faststart", str(out)], stdin=subprocess.PIPE)
    for n in range(round(TOTAL * FPS)):
        t = n / FPS
        index = min(count - 1, int(t // step))
        tau = t - index * step
        frame = portrait_frame(shots[index], tau, length)
        if index > 0 and tau < FADE:  # cross-fade from the previous work's last FADE seconds
            outgoing = portrait_frame(shots[index - 1], tau + step, length)
            frame = Image.blend(outgoing, frame, ease(tau / FADE))
        level = min(ease(t / FADE_IN), ease((TOTAL - t) / FADE_OUT))
        if level < 1:
            frame = Image.blend(Image.new("RGB", (W, H)), frame, level)
        encoder.stdin.write(frame.tobytes())
    encoder.stdin.close()
    if encoder.wait():
        raise SystemExit("ffmpeg failed")
    return works, fastest


def check_timing(works: list[dict]) -> None:
    """The portrait film must start every work exactly when the landscape film (and so the audio
    guide) does."""
    film = json.loads(RECORD.read_text(encoding="utf-8"))
    want = [(w["objectID"], w["startMs"]) for w in film["works"]]
    have = [(w["objectID"], w["startMs"]) for w in works]
    if have != want:
        raise SystemExit(f"portrait startMs {have} differ from {RECORD.name}'s {want}")


def shown(path: pathlib.Path) -> str:
    path = path.resolve()
    return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)


VIGNETTE = None


def main() -> int:
    global VIGNETTE, W, H
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--orientation", choices=("landscape", "portrait"),
                        help="default: portrait when --height exceeds --width, else landscape")
    parser.add_argument("--width", type=int, help="default 1280 landscape, 720 portrait")
    parser.add_argument("--height", type=int, help="default 720 landscape, 1080 portrait")
    parser.add_argument("--out", type=pathlib.Path,
                        help=f"default {shown(OUT)}, or {OUT_PORTRAIT.name} for portrait")
    parser.add_argument("--record", type=pathlib.Path,
                        help=f"default {shown(RECORD)}, or {RECORD_PORTRAIT.name} for portrait")
    parser.add_argument("--sheet", type=pathlib.Path, help="also write a 6-frame contact sheet")
    args = parser.parse_args()
    portrait = (args.orientation == "portrait" if args.orientation else
                bool(args.width and args.height and args.height > args.width))
    W, H = args.width or (720 if portrait else 1280), args.height or (1080 if portrait else 720)
    if portrait and not (H > W and W % 2 == 0 and H % 2 == 0):
        raise SystemExit("portrait needs an even width and height, taller than wide")
    if not portrait and (W, H) != (1280, 720):
        raise SystemExit("the landscape layout is fixed at 1280x720")
    args.out = args.out or (OUT_PORTRAIT if portrait else OUT)
    args.record = args.record or (RECORD_PORTRAIT if portrait else RECORD)
    source = json.loads((MET / "SOURCE.json").read_text(encoding="utf-8"))
    objects = source["objects"]
    for obj in objects:
        if sha256(MET / obj["file"]) != obj["sha256"]:
            raise SystemExit(f"{obj['file']} does not match SOURCE.json")
    drawtext = subprocess.run(["ffmpeg", "-hide_banner", "-filters"], capture_output=True,
                              text=True, check=True).stdout
    captions = ("ffmpeg drawtext" if " drawtext " in drawtext else
                "pre-rendered with Pillow (this ffmpeg has no drawtext)")
    if portrait:
        VIGNETTE = vignette_p()
        works, fastest = render_portrait(objects, args.out)
    else:
        VIGNETTE = vignette()
        works = render(objects, args.out)
    info = probe(args.out)
    streams = info["streams"]
    if len(streams) != 1 or streams[0]["codec_type"] != "video":
        raise SystemExit("film must hold exactly one video stream and no audio")
    if (streams[0]["width"], streams[0]["height"], streams[0]["pix_fmt"]) != (W, H, "yuv420p"):
        raise SystemExit(f"film is not {W}x{H} yuv420p")
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
        "output": {"path": shown(args.out), "sha256": sha256(args.out),
                   "bytes": size, "durationSeconds": round(float(info["format"]["duration"]), 3),
                   "width": streams[0]["width"], "height": streams[0]["height"],
                   "codec": streams[0]["codec_name"], "profile": streams[0].get("profile")},
    }
    if portrait:
        check_timing(works)
        record["note"] = (
            "Portrait (2:3) cut of the same film for the passenger display: each painting fills "
            "the frame, with no bars. Built from The Met Open Access images only; no Met video "
            "is used. Silent: the audio guide (met_guide_01..06) is separate narration timed to "
            "startMs, which are FILM.json's.")
        record["parameters"] = {
            "width": W, "height": H, "fps": FPS, "pixelFormat": "yuv420p", "totalSeconds": TOTAL,
            "shotSeconds": round(shot_seconds(len(objects)), 4), "crossfadeSeconds": FADE,
            "fadeInSeconds": FADE_IN, "fadeOutSeconds": FADE_OUT,
            "fit": "cover (full-bleed); eased pan on the wide works, slow zoom and drift on the "
                   "near-square ones",
            "panZoom": PAN_ZOOM, "insetDefault": INSET_DEFAULT,
            "inset": {str(k): v for k, v in INSET.items()},
            "moves": {str(k): v for k, v in PORTRAIT_MOVES.items()},
            "peakPixelsPerSecondAt720": round(fastest, 1), "maxPixelsPerSecondAt720": MAX_SPEED,
            "codec": f"libx264 high@4.0 crf {PORTRAIT_CODEC['crf']} maxrate "
                     f"{PORTRAIT_CODEC['maxrate']}, no audio"}
        record = {"schemaVersion": record["schemaVersion"], "title": record["title"],
                  "orientation": "portrait", "timingFrom": RECORD.name,
                  **{k: v for k, v in record.items() if k not in ("schemaVersion", "title")}}
    args.record.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n",
                           encoding="utf-8")
    if args.sheet:
        if portrait:
            contact_sheet(args.out, works, args.sheet, width=480)
        else:
            contact_sheet(args.out, works, args.sheet)
    print(f"wrote {args.out} ({size} bytes, {record['output']['durationSeconds']} s), "
          f"{args.record}" + (f", {args.sheet}" if args.sheet else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
