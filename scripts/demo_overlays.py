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
"""Pre-rendered overlays for the NYC demo video: cockpit frame, captions, SRT and story rail.

This build's ffmpeg has no drawtext, so every glyph is drawn here with Pillow and handed to the
recorder as PNGs plus the window each one is shown in. Every PNG is the size of its band, so it
is overlaid at that band's top-left corner from layout().

  layout()                         cockpit screen boxes, caption band and rail band
  captions(clips, out_dir)         caption-band PNGs        -> [(png_path, start_s, end_s)]
  write_srt(clips, path)           the same cues as SubRip, for YouTube closed captions
  story_rail(beats, out_dir)       rail-band PNGs           -> [(png_path, start_s, end_s)]
  scenario_beats(scenario)         chapter beats anchored on the scenario's own actions
  vehicle_states(scenario)         [(start_s, "Parked" | "Driving")] from its carState actions

Clip timing comes from the real speech: a clip's "durationMs" if it carries one, else the length
of <audio_dir>/<clip id>.mp3, read from its MPEG frame headers (no ffprobe needed).

  demo_overlays.py [srt] [SCENARIO] [--audio-dir D] [--out build/demo/<scenario>.srt]
  demo_overlays.py cockpit         re-render cockpit-frame.png and cockpit-layout.json
  demo_overlays.py preview SCENARIO --driver RAW --passenger RAW --at S --out PNG
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import math
import pathlib
import random
import re
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
CARDS = ROOT / "a2ui-automotive/demo/cards"
MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
AUDIO_DIR = ROOT / "a2ui-automotive/demo/audio/elevenlabs"
COCKPIT_FRAME = CARDS / "cockpit-frame.png"
COCKPIT_LAYOUT = CARDS / "cockpit-layout.json"
COCKPIT_GLOW = CARDS / "cockpit-glow.png"
INTRO_DRIVER = CARDS / "nyc_met-intro-driver.png"        # scripts/render-brand-assets.py intro
INTRO_PASSENGER = CARDS / "nyc_met-intro-passenger.png"

CANVAS = (1920, 1080)
RAIL_BAND = (0, 0, 1920, 56)
CAPTION_BAND = (0, 944, 1920, 136)
# Both recordings are 792 px tall (driver 1408x792, passenger 528x792): one scale keeps their
# pixels equal. 82/88 is the largest that leaves room for bezels; it is 97.6% of the old
# side-by-side canvas, and the screens span 1876 of 1920 px.
SCREEN_SCALE = 82 / 88
DRIVER_BOX = (34, 82, 1312, 738)
PASSENGER_BOX = (1394, 82, 492, 738)
BEZEL = 12

FONTS = [("/System/Library/Fonts/Avenir Next.ttc", {"bold": 0, "demi": 2, "medium": 5}),
         ("/System/Library/Fonts/HelveticaNeue.ttc", {"bold": 1, "demi": 1, "medium": 0}),
         ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", {"bold": 0, "demi": 0}),
         ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", {"medium": 0})]
INK = (244, 246, 248)
PASSENGER_ACCENT = (227, 179, 65)   # the passenger panel label's amber
GUIDE_ACCENT = (150, 136, 255)
RAIL_ACCENT = (124, 108, 255)
STATE_COLOURS = {"Parked": (92, 196, 122), "Driving": (90, 169, 255)}

CAPTION_SIZE = 38
CAPTION_LINE = 48
CAPTION_PAD = (28, 14)
CAPTION_MAX_W = 1560          # text width a caption line may use
CAPTION_BOTTOM = 10           # pill bottom margin inside the band
LINGER_S = 0.3                # a caption stays this long after its speech ends
MIN_SHOWN_S = 1.2
MAX_CPS = 20.0                # never faster than 20 characters a second, whatever the audio
GAP_S = 0.08                  # captions never touch: the next one starts after this gap
SPEAKERS = {"user_utterance": "Passenger"}
LANES = {"audio_guide": "Audio guide"}

# Beats anchor on the scenario's own actions: each is the first action after the previous beat
# whose fields match. A scenario may carry "storyBeats" in the same shape to override this.
DEFAULT_BEATS = {
    "nyc_met": [
        {"title": "Plan at Hudson Yards"},
        {"title": "Drive uptown", "anchor": {"type": "carState", "state": "drive"}},
        {"title": "Arrive at The Met", "anchor": {"type": "carState", "state": "park"}},
        {"title": "Low battery", "anchor": {"type": "carState", "state": "drive"}},
        {"title": "Charge & coffee", "anchor": {"type": "carState", "state": "park"}},
    ],
}


# ---------------------------------------------------------------------------------------------
# Layout

def layout(path: pathlib.Path = COCKPIT_LAYOUT) -> dict:
    """Canvas, the cockpit's driver/passenger boxes and the two overlay bands, all [x, y, w, h].

    The boxes come from cockpit-layout.json, which the cockpit renderer writes with the frame.
    """
    data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    return {"canvas": list(data["canvas"]), "driver": list(data["driver"]),
            "passenger": list(data["passenger"]), "captionBand": list(data["captionBand"]),
            "railBand": list(data["railBand"]), "frame": data["frame"], "glow": data["glow"],
            "intro": {k: data["intro"][k] for k in ("driver", "passenger")}}


# ---------------------------------------------------------------------------------------------
# Text

def over(image: Image.Image, paint) -> None:
    """Draw on a clear layer and composite it: ImageDraw writes shape pixels, it does not blend."""
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    paint(ImageDraw.Draw(layer))
    image.alpha_composite(layer)


def font(size: int, face: str = "medium") -> ImageFont.FreeTypeFont:
    for path, faces in FONTS:
        if face in faces and pathlib.Path(path).exists():
            return ImageFont.truetype(path, size, index=faces[face])
    return ImageFont.load_default(size)


def width(text: str, face: ImageFont.FreeTypeFont) -> float:
    return face.getlength(text)


def wrap(text: str, face: ImageFont.FreeTypeFont, max_w: float) -> list[str]:
    """Greedy word wrap, then a two-line result rebalanced so neither line is much longer."""
    words = text.split()
    lines: list[str] = []
    for word in words:
        if lines and width(lines[-1] + " " + word, face) <= max_w:
            lines[-1] += " " + word
        else:
            lines.append(word)
    if len(lines) == 2:
        best = None
        for cut in range(1, len(words)):
            a, b = " ".join(words[:cut]), " ".join(words[cut:])
            widest = max(width(a, face), width(b, face))
            if widest <= max_w and (best is None or widest < best[0]):
                best = (widest, [a, b])
        lines = best[1] if best else lines
    return lines


def chunks(text: str, face: ImageFont.FreeTypeFont, max_w: float,
           max_lines: int = 2) -> list[list[str]]:
    """Split text into captions of at most max_lines, at sentence, then clause, then line breaks."""
    lines = wrap(text, face, max_w)
    if len(lines) <= max_lines:
        return [lines]
    for pattern in (r"(?<=[.!?])\s+", r"(?<=[,;:])\s+"):
        parts = re.split(pattern, text.strip())
        if len(parts) < 2:
            continue
        out: list[list[str]] = []
        current = ""
        for part in parts:
            joined = f"{current} {part}".strip()
            if current and len(wrap(joined, face, max_w)) > max_lines:
                out.extend(chunks(current, face, max_w, max_lines))
                current = part
            else:
                current = joined
        out.extend(chunks(current, face, max_w, max_lines))
        return out
    return [lines[i:i + max_lines] for i in range(0, len(lines), max_lines)]


# ---------------------------------------------------------------------------------------------
# Timing

_BITRATES = {1: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
             2: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]}
_RATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


def _xing(data: bytes, pos: int, version: int, mono: bool) -> tuple[int, int] | None:
    """(audio frames, encoder delay + padding samples) from a Xing/Info + LAME header frame."""
    at = pos + 4 + ((17 if mono else 32) if version == 3 else (9 if mono else 17))
    if data[at:at + 4] not in (b"Xing", b"Info"):
        return None
    flags = int.from_bytes(data[at + 4:at + 8], "big")
    if not flags & 1:
        return None
    frames = int.from_bytes(data[at + 8:at + 12], "big")
    lame = at + 8 + 4 + (4 if flags & 2 else 0) + (100 if flags & 4 else 0) + \
        (4 if flags & 8 else 0)
    trim = 0  # a LAME-format tag, whoever wrote it ("LAME", ffmpeg's "Lavf"/"Lavc")
    if data[lame:lame + 4].isalpha() and len(data) >= lame + 24:
        b0, b1, b2 = data[lame + 21:lame + 24]
        trim = ((b0 << 4) | (b1 >> 4)) + (((b1 & 0x0F) << 8) | b2)
    return frames, trim


def mp3_seconds(path: pathlib.Path) -> float:
    """Duration of an MPEG Layer III file as a decoder plays it.

    A Xing/Info header's frame count, less the LAME encoder delay and padding (what ffprobe
    reports); without one, every frame header is counted (frames x samples / rate)."""
    data = pathlib.Path(path).read_bytes()
    pos = 0
    if data[:3] == b"ID3" and len(data) >= 10:
        size = (data[6] & 0x7F) << 21 | (data[7] & 0x7F) << 14 | (data[8] & 0x7F) << 7 \
            | (data[9] & 0x7F)
        pos = 10 + size + (10 if data[5] & 0x10 else 0)
    samples = rate = 0
    while pos + 4 <= len(data):
        b1, b2 = data[pos + 1], data[pos + 2]
        version, layer = (b1 >> 3) & 3, (b1 >> 1) & 3
        bitrate_i, rate_i, padding = b2 >> 4, (b2 >> 2) & 3, (b2 >> 1) & 1
        if (data[pos] != 0xFF or b1 & 0xE0 != 0xE0 or version == 1 or layer != 1
                or bitrate_i in (0, 15) or rate_i == 3):
            pos += 1  # not a frame header: resync
            continue
        per_frame = 1152 if version == 3 else 576
        frame_rate = _RATES[version][rate_i]
        if not rate:
            xing = _xing(data, pos, version, data[pos + 3] >> 6 == 3)
            if xing:
                return (xing[0] * per_frame - xing[1]) / frame_rate
        bitrate = _BITRATES[1 if version == 3 else 2][bitrate_i] * 1000
        samples += per_frame
        rate = frame_rate
        pos += per_frame // 8 * bitrate // frame_rate + padding
    if not rate:
        raise ValueError(f"{path}: no MPEG Layer III frames")
    return samples / rate


def speech_seconds(clip: dict, audio_dir: pathlib.Path | None) -> float:
    if clip.get("durationMs") is not None:
        return float(clip["durationMs"]) / 1000
    if audio_dir is None:
        raise ValueError(f"clip {clip.get('id')} has no durationMs and no audio_dir was given")
    return mp3_seconds(pathlib.Path(audio_dir) / f"{clip['id']}.mp3")


def speaker(clip: dict) -> str | None:
    return SPEAKERS.get(clip.get("role", "")) or LANES.get(clip.get("lane", ""))


@dataclasses.dataclass(frozen=True)
class Cue:
    start_s: float
    end_s: float
    lines: tuple[str, ...]
    speaker: str | None


def caption_cues(clips: list[dict], *, audio_dir: pathlib.Path | None = None,
                 offset_s: float = 0.0) -> list[Cue]:
    """Timed, at-most-two-line cues: each clip from its start for its speech plus LINGER_S
    (never under MIN_SHOWN_S or faster than MAX_CPS), cut GAP_S before the next clip, and split
    into consecutive cues, timed by length, when it needs more than two lines."""
    ordered = sorted((c for c in clips if str(c.get("text", "")).strip()),
                     key=lambda c: int(c["atMs"]))
    face = font(CAPTION_SIZE)
    cues: list[Cue] = []
    for index, clip in enumerate(ordered):
        text = " ".join(str(clip["text"]).split())
        start = int(clip["atMs"]) / 1000
        shown = max(speech_seconds(clip, audio_dir) + LINGER_S, len(text) / MAX_CPS,
                    MIN_SHOWN_S)
        end = start + shown
        if index + 1 < len(ordered):
            end = min(end, int(ordered[index + 1]["atMs"]) / 1000 - GAP_S)
        if end <= start:
            continue
        who = speaker(clip)
        parts = chunks(text, face, CAPTION_MAX_W - (tag_width(who) if who else 0))
        total = sum(len(" ".join(p)) for p in parts)
        at = start
        for part in parts:
            span = (end - start) * len(" ".join(part)) / total
            cues.append(Cue(round(at + offset_s, 3), round(at + span + offset_s, 3),
                            tuple(part), who))
            at += span
    return cues


# ---------------------------------------------------------------------------------------------
# Captions

TAG_SIZE = 20


def tag_width(label: str) -> float:
    return width(label.upper(), font(TAG_SIZE, "demi")) + 26 + 18  # chip padding + gap


def caption_image(cue: Cue) -> Image.Image:
    """The caption band with one bottom-centred, semi-opaque pill."""
    _, _, band_w, band_h = CAPTION_BAND
    image = Image.new("RGBA", (band_w, band_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    face = font(CAPTION_SIZE)
    text_w = max(width(line, face) for line in cue.lines)
    tag = tag_width(cue.speaker) if cue.speaker else 0
    pad_x, pad_y = CAPTION_PAD
    pill_w = round(text_w + tag + 2 * pad_x)
    pill_h = CAPTION_LINE * len(cue.lines) + 2 * pad_y
    left = (band_w - pill_w) // 2
    top = band_h - CAPTION_BOTTOM - pill_h
    accent = {"Passenger": PASSENGER_ACCENT}.get(cue.speaker or "", GUIDE_ACCENT)
    pill = (left, top, left + pill_w, top + pill_h)
    draw.rounded_rectangle(pill, radius=20, fill=(8, 10, 14, 200))
    x = left + pad_x
    if cue.speaker:
        label = cue.speaker.upper()
        tag_face = font(TAG_SIZE, "demi")
        chip_w = width(label, tag_face) + 26
        chip_top = top + (pill_h - 30) // 2
        chip = (x, chip_top, x + chip_w, chip_top + 30)
        over(image, lambda d: d.rounded_rectangle(pill, radius=20, outline=accent + (150,),
                                                  width=2))
        over(image, lambda d: d.rounded_rectangle(chip, radius=15, fill=accent + (40,),
                                                  outline=accent + (210,), width=1))
        draw.text((x + 13, chip_top + 15), label, font=tag_face, fill=accent + (255,),
                  anchor="lm")
        x += tag
    for row, line in enumerate(cue.lines):
        draw.text((x, top + pad_y + CAPTION_LINE * row + CAPTION_LINE // 2), line, font=face,
                  fill=INK + (255,), anchor="lm")
    return image


def captions(clips: list[dict], out_dir: pathlib.Path, *, audio_dir: pathlib.Path | None = None,
             offset_s: float = 0.0) -> list[tuple[pathlib.Path, float, float]]:
    """Burned-in captions: one caption-band PNG per cue, with the window it is shown in."""
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = []
    for index, cue in enumerate(caption_cues(clips, audio_dir=audio_dir, offset_s=offset_s)):
        path = out_dir / f"caption-{index:03d}.png"
        caption_image(cue).save(path, format="PNG", optimize=True)
        out.append((path, cue.start_s, cue.end_s))
    return out


def srt_time(seconds: float) -> str:
    millis = max(0, round(seconds * 1000))
    hours, rest = divmod(millis, 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    secs, millis = divmod(rest, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def srt_text(clips: list[dict], *, audio_dir: pathlib.Path | None = None,
             offset_s: float = 0.0) -> str:
    blocks = []
    for index, cue in enumerate(caption_cues(clips, audio_dir=audio_dir, offset_s=offset_s), 1):
        lines = list(cue.lines)
        if cue.speaker:
            lines[0] = f"[{cue.speaker}] {lines[0]}"
        blocks.append(f"{index}\n{srt_time(cue.start_s)} --> {srt_time(cue.end_s)}\n"
                      + "\n".join(lines) + "\n")
    return "\n".join(blocks)


def write_srt(clips: list[dict], path: pathlib.Path, *, audio_dir: pathlib.Path | None = None,
              offset_s: float = 0.0) -> pathlib.Path:
    """SubRip closed captions with exactly the burned-in cues' text and timing."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(srt_text(clips, audio_dir=audio_dir, offset_s=offset_s), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------------------------
# Story rail

def scenario_beats(scenario: dict) -> list[dict]:
    """[{chapter, title, start_s, end_s}], each beat starting at its anchor action."""
    spec = scenario.get("storyBeats") or DEFAULT_BEATS.get(scenario["id"])
    if not spec:
        raise ValueError(f"no story beats for {scenario['id']}")
    actions = sorted(scenario["actions"], key=lambda a: int(a["atMs"]))
    starts: list[float] = []
    for beat in spec:
        anchor = beat.get("anchor")
        if not anchor:
            starts.append(0.0 if not starts else starts[-1])
            continue
        after = starts[-1] * 1000 if starts else -1
        match = next((a for a in actions if int(a["atMs"]) > after
                      and all(a.get(k) == v for k, v in anchor.items())), None)
        if match is None:
            raise ValueError(f"beat {beat['title']!r} has no action matching {anchor}")
        starts.append(int(match["atMs"]) / 1000)
    end = float(scenario["durationSeconds"])
    return [{"chapter": i + 1, "title": beat["title"], "start_s": start,
             "end_s": starts[i + 1] if i + 1 < len(starts) else end}
            for i, (beat, start) in enumerate(zip(spec, starts))]


def vehicle_states(scenario: dict) -> list[tuple[float, str]]:
    """The recorder parks before it starts; every carState action changes the state."""
    states = [(0.0, "Parked")]
    for action in sorted(scenario["actions"], key=lambda a: int(a["atMs"])):
        if action["type"] == "carState":
            state = "Driving" if action["state"] == "drive" else "Parked"
            if state != states[-1][1]:
                states.append((int(action["atMs"]) / 1000, state))
    return states


def state_at(states: list[tuple[float, str]], t: float) -> str:
    current = states[0][1]
    for start, state in states:
        if start <= t + 1e-9:
            current = state
    return current


def rail_image(beat: dict, total: int, state: str) -> Image.Image:
    _, _, band_w, band_h = RAIL_BAND
    image = Image.new("RGBA", (band_w, band_h), (6, 8, 12, 210))
    draw = ImageDraw.Draw(image)
    over(image, lambda d: d.line((0, band_h - 1, band_w, band_h - 1), fill=(255, 255, 255, 30)))
    mid = band_h // 2
    title_face, number_face = font(26, "demi"), font(26, "bold")
    number = f"{beat['chapter']}"
    draw.text((32, mid), number, font=number_face, fill=RAIL_ACCENT + (255,), anchor="lm")
    x = 32 + width(number, number_face)
    draw.text((x, mid), f" · {beat['title']}", font=title_face, fill=INK + (255,), anchor="lm")
    # State pill, right-aligned.
    state_face = font(22, "demi")
    colour = STATE_COLOURS[state]
    pill_w = round(width(state, state_face)) + 52
    right = band_w - 28
    over(image, lambda d: d.rounded_rectangle((right - pill_w, mid - 17, right, mid + 17),
                                              radius=17, fill=colour + (38,),
                                              outline=colour + (200,), width=1))
    draw.ellipse((right - pill_w + 16, mid - 5, right - pill_w + 26, mid + 5),
                 fill=colour + (255,))
    draw.text((right - pill_w + 34, mid), state, font=state_face, fill=INK + (255,),
              anchor="lm")
    # Progress: one segment per beat, done / current / to come.
    seg_w, seg_gap = 56, 8
    seg_right = right - pill_w - 32
    seg_left = seg_right - total * seg_w - (total - 1) * seg_gap

    def segments(d: ImageDraw.ImageDraw) -> None:
        for i in range(total):
            x0 = seg_left + i * (seg_w + seg_gap)
            fill = (INK + (200,) if i + 1 < beat["chapter"] else
                    RAIL_ACCENT + (255,) if i + 1 == beat["chapter"] else (255, 255, 255, 46))
            d.rounded_rectangle((x0, mid - 3, x0 + seg_w, mid + 3), radius=3, fill=fill)
    over(image, segments)
    return image


def story_rail(beats: list[dict], out_dir: pathlib.Path, *,
               states: list[tuple[float, str]] | None = None,
               offset_s: float = 0.0) -> list[tuple[pathlib.Path, float, float]]:
    """One rail-band PNG per beat and vehicle state, with the window it is shown in."""
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    states = states or [(0.0, "Parked")]
    out = []
    for beat in sorted(beats, key=lambda b: b["start_s"]):
        cuts = sorted({beat["start_s"], *(t for t, _ in states
                                         if beat["start_s"] < t < beat["end_s"])})
        for start, end in zip(cuts, cuts[1:] + [beat["end_s"]]):
            state = state_at(states, start)
            path = out_dir / f"rail-{beat['chapter']:02d}-{state.lower()}-{len(out):02d}.png"
            rail_image(beat, len(beats), state).save(path, format="PNG", optimize=True)
            out.append((path, round(start + offset_s, 3), round(end + offset_s, 3)))
    return out


# ---------------------------------------------------------------------------------------------
# Cockpit frame (procedural: see cockpit-layout.json "approach" for why not a generated plate)

def _lerp(a, b, k):
    return tuple(round(x + (y - x) * k) for x, y in zip(a, b))


def cockpit_frame() -> Image.Image:
    """A night cockpit: dark dash curve, ambient strip, glowing bezels, steering-wheel arc.

    Drawn at 2x and reduced, then the screen windows are cleared to alpha 0 at 1x so they match
    DRIVER_BOX and PASSENGER_BOX to the pixel."""
    s = 2
    w, h = CANVAS[0] * s, CANVAS[1] * s
    base = Image.new("RGBA", (w, h))
    draw = ImageDraw.Draw(base)
    for row in range(h):  # windshield-to-footwell gradient
        k = row / (h - 1)
        top, mid, bottom = (7, 9, 15), (12, 13, 22), (3, 4, 6)
        colour = _lerp(top, mid, k / 0.55) if k < 0.55 else _lerp(mid, bottom, (k - 0.55) / 0.45)
        draw.line([(0, row), (w, row)], fill=colour + (255,))
    # Faint city bokeh through the windshield, only seen around the screens.
    bokeh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(bokeh)
    rng = random.Random(1105)
    for _ in range(70):
        x, y = rng.uniform(0, w), rng.uniform(0, 0.62 * h)
        r = rng.uniform(6, 22) * s
        colour = rng.choice([(255, 196, 120), (120, 160, 255), (190, 150, 255), (255, 230, 190)])
        bdraw.ellipse((x - r, y - r, x + r, y + r), fill=colour + (rng.randint(18, 46),))
    base.alpha_composite(bokeh.filter(ImageFilter.GaussianBlur(8 * s)))

    def dash_y(x: float) -> float:  # the dashboard's top edge, highest in the middle
        return (888 - 24 * math.sin(math.pi * x / w)) * s

    dash = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ddraw = ImageDraw.Draw(dash)
    edge = [(x, dash_y(x)) for x in range(0, w + 1, 8)]
    ddraw.polygon(edge + [(w, h), (0, h)], fill=(15, 16, 22, 255))
    shade = Image.new("L", (1, h))
    for row in range(h):
        shade.putpixel((0, row), max(0, min(255, round(255 * (row - 840 * s) / (240 * s)))))
    darker = Image.new("RGBA", (w, h), (4, 5, 7, 255))
    dash = Image.composite(darker, dash, Image.composite(
        shade.resize((w, h)), Image.new("L", (w, h), 0), dash.getchannel("A")))
    ddraw = ImageDraw.Draw(dash)
    ddraw.line(edge, fill=(70, 74, 92, 255), width=2 * s)
    base.alpha_composite(dash)
    # Ambient light strip along the dash, with a soft and a wide glow.
    strip = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(strip)
    line = [(x, dash_y(x) + 26 * s) for x in range(0, w + 1, 8)]
    sdraw.line(line, fill=(122, 108, 255, 255), width=3 * s)
    wide = strip.filter(ImageFilter.GaussianBlur(26 * s))
    soft = strip.filter(ImageFilter.GaussianBlur(7 * s))
    base.alpha_composite(wide)
    base.alpha_composite(wide)
    base.alpha_composite(soft)
    base.alpha_composite(strip)
    # Screen housings: drop shadow on the dash, outer glow, bezel, edge highlight, slim mount.
    for box in (DRIVER_BOX, PASSENGER_BOX):
        x, y, bw, bh = (v * s for v in box)
        b = BEZEL * s
        outer = (x - b, y - b, x + bw + b, y + bh + b)
        cx = x + bw // 2
        mount = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(mount).polygon(
            [(cx - 70 * s, outer[3] - 4 * s), (cx + 70 * s, outer[3] - 4 * s),
             (cx + 96 * s, dash_y(cx) + 6 * s), (cx - 96 * s, dash_y(cx) + 6 * s)],
            fill=(11, 12, 16, 255))
        base.alpha_composite(mount)
        shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle(
            (outer[0] + 20 * s, outer[3] - 6 * s, outer[2] - 20 * s, outer[3] + 26 * s),
            radius=16 * s, fill=(0, 0, 0, 200))
        base.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14 * s)))
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(glow).rounded_rectangle(
            (outer[0] - 4 * s, outer[1] - 4 * s, outer[2] + 4 * s, outer[3] + 4 * s),
            radius=22 * s, fill=(96, 112, 255, 70))
        base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(16 * s)))
        hdraw = ImageDraw.Draw(base)
        hdraw.rounded_rectangle(outer, radius=18 * s, fill=(13, 14, 18, 255),
                                outline=(58, 62, 78, 255), width=s)
        hdraw.line((outer[0] + 18 * s, outer[1] + s, outer[2] - 18 * s, outer[1] + s),
                   fill=(96, 100, 120, 255), width=s)
        hdraw.rectangle((x - s, y - s, x + bw + s - 1, y + bh + s - 1), fill=(0, 0, 0, 255))
    # Steering-wheel rim rising from the lower left, lit from above by the ambient strip.
    wheel = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    wdraw = ImageDraw.Draw(wheel)
    cx, cy, outer_r, rim = 300 * s, 1372 * s, 500 * s, 58 * s
    for step in range(rim // s):  # rim shaded from a lit top edge to a dark inner edge
        r = outer_r - step * s
        tone = _lerp((58, 60, 74), (16, 17, 22), min(1, step / (0.7 * rim / s)))
        wdraw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=tone + (255,))
    inner = outer_r - rim
    wdraw.ellipse((cx - inner, cy - inner, cx + inner, cy + inner), fill=(0, 0, 0, 0))
    wdraw.arc((cx - outer_r, cy - outer_r, cx + outer_r, cy + outer_r), 205, 335,
              fill=(112, 114, 134, 255), width=2 * s)
    base.alpha_composite(wheel)
    over(base, lambda d: d.arc((cx - outer_r + 5 * s, cy - outer_r + 5 * s,
                                cx + outer_r - 5 * s, cy + outer_r - 5 * s), 228, 312,
                               fill=(122, 108, 255, 110), width=4 * s))
    frame = base.resize(CANVAS, Image.LANCZOS)
    clear = Image.new("RGBA", (DRIVER_BOX[2], DRIVER_BOX[3]), (0, 0, 0, 0))
    frame.paste(clear, DRIVER_BOX[:2])
    frame.paste(Image.new("RGBA", PASSENGER_BOX[2:], (0, 0, 0, 0)), PASSENGER_BOX[:2])
    return frame


def screen_glow() -> Image.Image:
    """Extra light spilling from lit screens onto bezels and dash, for the intro: overlay it
    above the frame (fading it is optional); its windows are clear, so it never tints a screen."""
    glow = Image.new("RGBA", CANVAS, (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    for box, colour in ((DRIVER_BOX, (126, 104, 255)), (PASSENGER_BOX, (168, 112, 255))):
        x, y, w, h = box
        pad = BEZEL + 10
        draw.rounded_rectangle((x - pad, y - pad, x + w + pad, y + h + pad), radius=28,
                               fill=colour + (120,))
    glow = glow.filter(ImageFilter.GaussianBlur(26))
    for box in (DRIVER_BOX, PASSENGER_BOX):
        glow.paste(Image.new("RGBA", box[2:], (0, 0, 0, 0)), box[:2])
    return glow


def ease(p: float) -> float:
    """Smoothstep: zero velocity at both ends."""
    p = min(1.0, max(0.0, p))
    return p * p * (3 - 2 * p)


def push_box(size: tuple[int, int], zoom: float) -> tuple[float, float, float, float]:
    """The centred source region a push-in of `zoom` (1.0 = none) shows; fractional on purpose."""
    w, h = size
    half_w, half_h = w / (2 * zoom), h / (2 * zoom)
    return (w / 2 - half_w, h / 2 - half_h, w / 2 + half_w, h / 2 + half_h)


def intro_alpha(t: float, hold_s: float, fade_s: float) -> float:
    return 1.0 if t < hold_s else 1.0 - ease((t - hold_s) / fade_s)


def intro_frames(out_dir: pathlib.Path, *, hold_s: float = 2.0, fade_s: float = 1.1,
                 zoom: float = 0.025, fps: int = 30) -> dict:
    """The intro screens as box-sized RGBA frame sequences, push-in and crossfade baked in.

    Frame i is video time i/fps: the graffiti and passenger card push in by `zoom` (eased) over
    hold_s + fade_s, and over the last fade_s their alpha eases from 1 to 0, so the recording
    underneath "turns on". Overlay each sequence at its box, above the recording and below the
    frame, with `-framerate {fps} -i <pattern>` and overlay's eof_action=pass: frame-exact, no
    xfade timeline shift, and the cockpit frame never moves. Sub-pixel crops keep the push-in
    free of the integer-step jitter of ffmpeg's zoompan."""
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    total = hold_s + fade_s
    count = round(total * fps)
    boxes = {"driver": DRIVER_BOX, "passenger": PASSENGER_BOX}
    sources = {"driver": INTRO_DRIVER, "passenger": INTRO_PASSENGER}
    for name, box in boxes.items():
        with Image.open(sources[name]) as opened:
            source = opened.convert("RGB")
        for i in range(count):
            t = i / fps
            frame = source.resize(box[2:], Image.LANCZOS,
                                  box=push_box(source.size, 1 + zoom * ease(t / total)))
            frame.putalpha(round(255 * intro_alpha(t, hold_s, fade_s)))
            frame.save(out_dir / f"{name}-{i:04d}.png", format="PNG", compress_level=3)
    return {"driver": str(out_dir / "driver-%04d.png"),
            "passenger": str(out_dir / "passenger-%04d.png"),
            "frames": count, "fps": fps, "fadeStartS": hold_s, "endS": count / fps}


def cockpit_layout_record(approach: dict) -> dict:
    return {
        "schemaVersion": 1,
        "canvas": list(CANVAS),
        "driver": list(DRIVER_BOX),
        "passenger": list(PASSENGER_BOX),
        "railBand": list(RAIL_BAND),
        "captionBand": list(CAPTION_BAND),
        "bezelPx": BEZEL,
        "screenScale": round(SCREEN_SCALE, 6),
        "sources": {"driver": [1408, 792], "passenger": [528, 792]},
        "frame": str(COCKPIT_FRAME.relative_to(ROOT)),
        "glow": str(COCKPIT_GLOW.relative_to(ROOT)),
        "intro": {"driver": str(INTRO_DRIVER.relative_to(ROOT)),
                  "passenger": str(INTRO_PASSENGER.relative_to(ROOT)),
                  "note": "screen images at the recordings' sizes: scale them into the boxes "
                          "like the recordings, then crossfade into the live UI in place"},
        "windows": "alpha 0 exactly at driver and passenger; opaque everywhere else",
        "composite": "background, recordings scaled into the boxes, then the frame on top "
                     "(or the frame first and the recordings over it: same pixels)",
        "renderer": "scripts/demo_overlays.py cockpit",
        "approach": approach,
    }


# ---------------------------------------------------------------------------------------------
# CLI

def load_scenario(scenario_id: str) -> dict:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return next(s for s in manifest["scenarios"] if s["id"] == scenario_id)


def grab(video: pathlib.Path, at: float, size: tuple[int, int]) -> Image.Image:
    with tempfile.TemporaryDirectory() as tmp:
        out = pathlib.Path(tmp) / "frame.png"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", str(video),
                        "-frames:v", "1", str(out)], check=True, timeout=120)
        return Image.open(out).convert("RGB").resize(size, Image.LANCZOS)


def preview(scenario: dict, driver: pathlib.Path, passenger: pathlib.Path | None, at: float,
            out: pathlib.Path, audio_dir: pathlib.Path | None,
            passenger_at: float | None = None) -> pathlib.Path:
    """One still of the composition at scenario time `at`, for eyeballing the frame."""
    boxes = layout()
    canvas = Image.new("RGBA", CANVAS, (11, 15, 20, 255))
    canvas.paste(grab(driver, at, tuple(boxes["driver"][2:])), tuple(boxes["driver"][:2]))
    if passenger is not None:
        canvas.paste(grab(passenger, passenger_at if passenger_at is not None else at,
                          tuple(boxes["passenger"][2:])), tuple(boxes["passenger"][:2]))
    canvas.alpha_composite(Image.open(ROOT / boxes["frame"]).convert("RGBA"))
    with tempfile.TemporaryDirectory() as tmp:
        for path, start, end in story_rail(scenario_beats(scenario), pathlib.Path(tmp) / "r",
                                           states=vehicle_states(scenario)):
            if start <= at < end:
                canvas.alpha_composite(Image.open(path), tuple(boxes["railBand"][:2]))
        if audio_dir is not None:
            for path, start, end in captions(scenario["clips"], pathlib.Path(tmp) / "c",
                                             audio_dir=audio_dir):
                if start <= at < end:
                    canvas.alpha_composite(Image.open(path), tuple(boxes["captionBand"][:2]))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(out)
    return out


APPROACH = {
    "chosen": "procedural",
    "why": "Muse Image 1.0 drew convincing straight-on cockpits, but neither plate gave a clean "
           "16:9 driver window: cockpit-1's screens cover 62% of the width (driver 16:9 fit "
           "~1040 px wide on the canvas, 80% of this layout) and cockpit-2's wide screen is "
           "1.47:1 with the steering wheel crossing its lower-left corner. Legibility first, "
           "so the frame is drawn here around the largest boxes that fit.",
    "museAttempts": "a2ui-automotive/demo/cards/brand/muse-image-attempts.json",
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")
    srt = sub.add_parser("srt", help="write the SubRip captions (the default command)")
    srt.add_argument("scenario", nargs="?", default="nyc_met")
    srt.add_argument("--audio-dir", type=pathlib.Path, default=AUDIO_DIR)
    srt.add_argument("--offset", type=float, default=0.0)
    srt.add_argument("--out", type=pathlib.Path)
    sub.add_parser("cockpit", help="re-render cockpit-frame.png and cockpit-layout.json")
    frames = sub.add_parser("intro-frames", help="write the intro screens' frame sequences")
    frames.add_argument("--out", type=pathlib.Path, required=True)
    frames.add_argument("--hold", type=float, default=2.0)
    frames.add_argument("--fade", type=float, default=1.1)
    frames.add_argument("--zoom", type=float, default=0.025)
    prev = sub.add_parser("preview", help="compose one still from raw recordings")
    prev.add_argument("scenario")
    prev.add_argument("--driver", type=pathlib.Path, required=True)
    prev.add_argument("--passenger", type=pathlib.Path)
    prev.add_argument("--at", type=float, required=True)
    prev.add_argument("--passenger-at", type=float)
    prev.add_argument("--audio-dir", type=pathlib.Path)
    prev.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "cockpit":
        cockpit_frame().save(COCKPIT_FRAME, format="PNG", optimize=True)
        screen_glow().save(COCKPIT_GLOW, format="PNG", optimize=True)
        COCKPIT_LAYOUT.write_text(json.dumps(cockpit_layout_record(APPROACH), indent=2) + "\n",
                                  encoding="utf-8")
        for path in (COCKPIT_FRAME, COCKPIT_GLOW, COCKPIT_LAYOUT):
            print(f"wrote {path.relative_to(ROOT)}")
    elif args.command == "intro-frames":
        print(json.dumps(intro_frames(args.out, hold_s=args.hold, fade_s=args.fade,
                                      zoom=args.zoom), indent=2))
    elif args.command == "preview":
        out = preview(load_scenario(args.scenario), args.driver, args.passenger, args.at,
                      args.out, args.audio_dir, args.passenger_at)
        print(f"wrote {out}")
    else:
        scenario_id = getattr(args, "scenario", None) or "nyc_met"
        audio_dir = getattr(args, "audio_dir", None) or AUDIO_DIR
        out = getattr(args, "out", None) or ROOT / "build/demo" / f"{scenario_id}.srt"
        path = write_srt(load_scenario(scenario_id)["clips"], out, audio_dir=audio_dir,
                         offset_s=getattr(args, "offset", 0.0))
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
