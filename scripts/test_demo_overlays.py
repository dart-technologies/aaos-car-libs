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
"""Device-free tests for demo_overlays.py: layout, cockpit windows, captions, SRT, story rail.

  python3 scripts/test_demo_overlays.py      (Pillow only; no ffmpeg, no device, no audio cache)

Speech durations come from synthetic MPEG frames, so the audio path is exercised for real.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import re
import sys
import tempfile
import unittest

from PIL import Image, ImageChops

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("demo_overlays", HERE / "demo_overlays.py")
ov = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = ov
_spec.loader.exec_module(ov)
MANIFEST = json.loads(ov.MANIFEST.read_text(encoding="utf-8"))
MET = next(s for s in MANIFEST["scenarios"] if s["id"] == "nyc_met")


def mp3_bytes(frames: int, id3: int = 0) -> bytes:
    """frames x MPEG-1 Layer III, 128 kbps, 44.1 kHz, unpadded (417-byte frames)."""
    tag = b""
    if id3:
        size = bytes([(id3 >> 21) & 0x7F, (id3 >> 14) & 0x7F, (id3 >> 7) & 0x7F, id3 & 0x7F])
        tag = b"ID3\x04\x00\x00" + size + b"\x00" * id3
    return tag + (b"\xFF\xFB\x90\x00" + b"\x00" * 413) * frames


def overlaps(a, b) -> bool:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


def clip(clip_id, at_ms, text, role="narration", duration_ms=2000, **extra):
    return {"id": clip_id, "atMs": at_ms, "text": text, "role": role,
            "durationMs": duration_ms, **extra}


class LayoutTest(unittest.TestCase):
    def test_boxes_inside_canvas_clear_of_bands_and_each_other(self):
        lay = ov.layout()
        width, height = lay["canvas"]
        self.assertEqual((width, height), (1920, 1080))
        for name in ("driver", "passenger", "captionBand", "railBand"):
            x, y, w, h = lay[name]
            self.assertTrue(x >= 0 and y >= 0 and x + w <= width and y + h <= height, name)
        for screen in ("driver", "passenger"):
            for band in ("captionBand", "railBand"):
                self.assertFalse(overlaps(lay[screen], lay[band]), (screen, band))
        self.assertFalse(overlaps(lay["driver"], lay["passenger"]))
        self.assertFalse(overlaps(lay["captionBand"], lay["railBand"]))
        _, _, dw, dh = lay["driver"]
        _, _, pw, ph = lay["passenger"]
        self.assertEqual(dw * 9, dh * 16, "driver box is not 16:9")
        self.assertEqual(pw * 3, ph * 2, "passenger box is not 2:3")
        self.assertEqual(dh, ph, "one scale for both 792-px recordings")
        self.assertGreaterEqual(dw, 1300, "legibility first: the driver screen stays large")

    def test_committed_layout_is_what_the_renderer_writes(self):
        written = json.loads(ov.COCKPIT_LAYOUT.read_text(encoding="utf-8"))
        self.assertEqual(written, ov.cockpit_layout_record(ov.APPROACH))

    def test_frame_windows_are_exactly_the_boxes(self):
        lay = ov.layout()
        for key in ("frame", "glow"):
            image = Image.open(ov.ROOT / lay[key])
            self.assertEqual((image.size, image.mode), ((1920, 1080), "RGBA"), key)
            alpha = image.getchannel("A")
            for name in ("driver", "passenger"):
                x, y, w, h = lay[name]
                self.assertEqual(alpha.crop((x, y, x + w, y + h)).getextrema(), (0, 0),
                                 f"{key} window {name} is not fully clear")
            if key == "frame":  # opaque everywhere else, right up to the window edge
                solid = alpha.copy()
                for name in ("driver", "passenger"):
                    x, y, w, h = lay[name]
                    solid.paste(255, (x, y, x + w, y + h))
                self.assertEqual(solid.getextrema(), (255, 255))
                for name in ("driver", "passenger"):
                    x, y, w, h = lay[name]
                    ring = [(x - 1, y - 1), (x + w, y - 1), (x - 1, y + h), (x + w, y + h),
                            (x + w // 2, y - 1), (x - 1, y + h // 2)]
                    self.assertTrue(all(alpha.getpixel(p) == 255 for p in ring), name)

    def test_intro_screens_match_the_recordings(self):
        lay = json.loads(ov.COCKPIT_LAYOUT.read_text(encoding="utf-8"))
        for name in ("driver", "passenger"):
            with Image.open(ov.ROOT / lay["intro"][name]) as image:
                self.assertEqual(list(image.size), lay["sources"][name], name)


class TimingTest(unittest.TestCase):
    def test_mp3_duration_counts_frames_after_an_id3_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "x.mp3"
            path.write_bytes(mp3_bytes(100, id3=37))
            self.assertAlmostEqual(ov.mp3_seconds(path), 100 * 1152 / 44100, places=9)
            path.write_bytes(b"not audio")
            with self.assertRaises(ValueError):
                ov.mp3_seconds(path)

    def test_captions_use_the_audio_file_when_the_clip_has_no_duration(self):
        with tempfile.TemporaryDirectory() as tmp:
            audio = pathlib.Path(tmp)
            (audio / "a.mp3").write_bytes(mp3_bytes(200))      # 5.224 s
            cues = ov.caption_cues([{"id": "a", "atMs": 1000, "role": "narration",
                                     "text": "Short."}], audio_dir=audio)
            self.assertAlmostEqual(cues[0].end_s, 1 + 200 * 1152 / 44100 + ov.LINGER_S,
                                   places=3)
            with self.assertRaises(ValueError):
                ov.caption_cues([{"id": "a", "atMs": 0, "role": "narration", "text": "x"}])

    def test_cues_follow_speech_never_overlap_and_respect_reading_speed(self):
        clips = [clip("a", 1000, "First line.", duration_ms=1500),
                 clip("b", 4000, "Second, which runs long into the next one.", duration_ms=9000),
                 clip("c", 6000, "A long sentence spoken far too fast for anyone to read it "
                                 "comfortably in time.", duration_ms=500)]
        cues = ov.caption_cues(clips)
        self.assertEqual(cues[0].start_s, 1.0)
        self.assertAlmostEqual(cues[0].end_s, 1.0 + 1.5 + ov.LINGER_S, places=3)
        self.assertAlmostEqual(cues[1].end_s, 6.0 - ov.GAP_S, places=3)   # cut before c
        self.assertGreaterEqual(cues[-1].end_s - 6.0, len(clips[2]["text"]) / ov.MAX_CPS - 1e-3)
        for a, b in zip(cues, cues[1:]):
            self.assertLessEqual(a.end_s, b.start_s)
            self.assertLess(a.start_s, a.end_s)

    def test_offset_shifts_every_cue(self):
        clips = [clip("a", 1000, "One."), clip("b", 5000, "Two.")]
        plain, shifted = ov.caption_cues(clips), ov.caption_cues(clips, offset_s=4.5)
        for a, b in zip(plain, shifted):
            self.assertAlmostEqual(b.start_s - a.start_s, 4.5, places=3)
            self.assertAlmostEqual(b.end_s - a.end_s, 4.5, places=3)


class CaptionTest(unittest.TestCase):
    LONG = ("AI can now design the screen in your car. Here, Meta's Muse plans our afternoon, "
            "and the car decides what the driver may see. Bookings wait until you're parked "
            "and you confirm, so nothing is booked or paid while the car is moving.")

    def test_at_most_two_lines_and_no_word_lost(self):
        cues = ov.caption_cues([clip("a", 0, self.LONG, duration_ms=12000)])
        self.assertGreater(len(cues), 1)
        self.assertTrue(all(1 <= len(cue.lines) <= 2 for cue in cues))
        self.assertEqual(" ".join(" ".join(cue.lines) for cue in cues).split(), self.LONG.split())
        face = ov.font(ov.CAPTION_SIZE)
        for cue in cues:
            self.assertTrue(all(ov.width(line, face) <= ov.CAPTION_MAX_W for line in cue.lines))

    def test_pngs_sit_bottom_centred_inside_the_caption_band(self):
        clips = [clip("n", 0, self.LONG, duration_ms=12000),
                 clip("u", 20000, "Take us to The Met this afternoon.", role="user_utterance"),
                 clip("g", 25000, "In Van Gogh's wheat field, wind rushes through cypresses.",
                      lane="audio_guide")]
        band_w, band_h = ov.layout()["captionBand"][2:]
        with tempfile.TemporaryDirectory() as tmp:
            out = ov.captions(clips, tmp)
            self.assertEqual([round(s, 3) for _, s, _ in out],
                             [c.start_s for c in ov.caption_cues(clips)])
            for path, start, end in out:
                with Image.open(path) as image:
                    self.assertEqual(image.size, (band_w, band_h))
                    left, top, right, bottom = image.getchannel("A").getbbox()
                    self.assertGreaterEqual(top, 0)
                    self.assertLessEqual(bottom, band_h - ov.CAPTION_BOTTOM + 1)
                    self.assertGreaterEqual(bottom, band_h - ov.CAPTION_BOTTOM - 2)
                    self.assertLessEqual(abs(left - (band_w - right)), 2, "not centred")
                    self.assertLess(start, end)

    def test_passenger_and_guide_lines_are_tagged_and_styled_apart(self):
        text = "Take us to The Met this afternoon."
        narration = ov.caption_cues([clip("n", 0, text)])[0]
        passenger = ov.caption_cues([clip("u", 0, text, role="user_utterance")])[0]
        guide = ov.caption_cues([clip("g", 0, text, lane="audio_guide")])[0]
        self.assertEqual((narration.speaker, passenger.speaker, guide.speaker),
                         (None, "Passenger", "Audio guide"))

        def accent_pixels(cue, accent):
            image = ov.caption_image(cue).convert("RGB")
            near = [band.point(lambda v: 255 if v < 40 else 0) for band in
                    ImageChops.difference(image, Image.new("RGB", image.size, accent)).split()]
            return ImageChops.multiply(ImageChops.multiply(near[0], near[1]),
                                       near[2]).histogram()[255]
        self.assertGreater(accent_pixels(passenger, ov.PASSENGER_ACCENT), 50)
        self.assertEqual(accent_pixels(narration, ov.PASSENGER_ACCENT), 0)
        self.assertGreater(accent_pixels(guide, ov.GUIDE_ACCENT), 50)


class SrtTest(unittest.TestCase):
    def test_srt_time(self):
        self.assertEqual(ov.srt_time(0), "00:00:00,000")
        self.assertEqual(ov.srt_time(3661.2346), "01:01:01,235")
        self.assertEqual(ov.srt_time(59.9996), "00:01:00,000")

    def test_srt_matches_the_burned_in_cues(self):
        clips = [clip("n", 1000, CaptionTest.LONG, duration_ms=12000),
                 clip("u", 20000, "Reserve entry for two at three.", role="user_utterance")]
        cues = ov.caption_cues(clips, offset_s=2.0)
        with tempfile.TemporaryDirectory() as tmp:
            path = ov.write_srt(clips, pathlib.Path(tmp) / "sub" / "x.srt", offset_s=2.0)
            blocks = path.read_text(encoding="utf-8").strip().split("\n\n")
        self.assertEqual(len(blocks), len(cues))
        stamp = r"(\d\d):(\d\d):(\d\d),(\d\d\d)"
        previous_end = -1.0
        for index, (block, cue) in enumerate(zip(blocks, cues), 1):
            lines = block.split("\n")
            self.assertEqual(lines[0], str(index))
            match = re.fullmatch(stamp + " --> " + stamp, lines[1])
            self.assertIsNotNone(match, lines[1])
            g = [int(v) for v in match.groups()]
            start = g[0] * 3600 + g[1] * 60 + g[2] + g[3] / 1000
            end = g[4] * 3600 + g[5] * 60 + g[6] + g[7] / 1000
            self.assertAlmostEqual(start, cue.start_s, places=3)
            self.assertAlmostEqual(end, cue.end_s, places=3)
            self.assertGreaterEqual(start, previous_end)
            previous_end = end
            self.assertLessEqual(len(lines) - 2, 2)
        self.assertTrue(blocks[-1].split("\n")[2].startswith("[Passenger] Reserve entry"))
        self.assertAlmostEqual(cues[0].start_s, 3.0, places=3)

    def test_cli_writes_the_scenario_srt_from_audio_durations(self):
        with tempfile.TemporaryDirectory() as tmp:
            audio = pathlib.Path(tmp) / "audio"
            audio.mkdir()
            for c in MET["clips"]:
                (audio / f"{c['id']}.mp3").write_bytes(mp3_bytes(120))   # 3.13 s each
            out = pathlib.Path(tmp) / "nyc_met.srt"
            self.assertEqual(ov.main(["srt", "nyc_met", "--audio-dir", str(audio),
                                      "--out", str(out)]), 0)
            text = out.read_text(encoding="utf-8")
            expected = ov.srt_text(MET["clips"], audio_dir=audio)
        self.assertEqual(text, expected)
        first, second = sorted(MET["clips"], key=lambda c: c["atMs"])[:2]
        end = min(first["atMs"] / 1000 + max(120 * 1152 / 44100 + ov.LINGER_S,
                                             len(first["text"]) / ov.MAX_CPS),
                  second["atMs"] / 1000 - ov.GAP_S)
        self.assertTrue(text.startswith("1\n" + ov.srt_time(first["atMs"] / 1000) + " --> "))
        self.assertIn(" --> " + ov.srt_time(end) + "\n", text)
        self.assertGreaterEqual(text.count(" --> "), len(MET["clips"]))


class RailTest(unittest.TestCase):
    def test_beats_are_ordered_and_anchor_on_the_scenarios_actions(self):
        beats = ov.scenario_beats(MET)
        self.assertEqual([b["title"] for b in beats],
                         ["Plan at Hudson Yards", "Drive uptown", "Arrive at The Met",
                          "Low battery", "Charge & coffee"])
        car = [int(a["atMs"]) / 1000 for a in sorted(MET["actions"], key=lambda a: a["atMs"])
               if a["type"] == "carState"]
        self.assertEqual([b["start_s"] for b in beats], [0.0] + car[:4])
        self.assertEqual([b["chapter"] for b in beats], [1, 2, 3, 4, 5])
        for a, b in zip(beats, beats[1:]):
            self.assertLess(a["start_s"], b["start_s"])
            self.assertEqual(a["end_s"], b["start_s"])
        self.assertEqual(beats[-1]["end_s"], float(MET["durationSeconds"]))

    def test_vehicle_states_follow_car_state_actions(self):
        states = ov.vehicle_states(MET)
        self.assertEqual(states[0], (0.0, "Parked"))
        self.assertEqual([s for _, s in states],
                         ["Parked", "Driving", "Parked", "Driving", "Parked"])

    def test_rail_windows_tile_the_timeline_and_split_on_state_changes(self):
        scenario = {"id": "t", "durationSeconds": 60,
                    "storyBeats": [{"title": "Start"},
                                   {"title": "Go", "anchor": {"type": "selectCase"}}],
                    "actions": [{"atMs": 10000, "type": "carState", "state": "drive"},
                                {"atMs": 20000, "type": "selectCase", "caseId": "x"},
                                {"atMs": 30000, "type": "carState", "state": "park"}]}
        beats = ov.scenario_beats(scenario)
        self.assertEqual([b["start_s"] for b in beats], [0.0, 20.0])
        rail_w, rail_h = ov.layout()["railBand"][2:]
        with tempfile.TemporaryDirectory() as tmp:
            out = ov.story_rail(beats, tmp, states=ov.vehicle_states(scenario), offset_s=1.0)
            self.assertEqual([(s, e) for _, s, e in out],
                             [(1.0, 11.0), (11.0, 21.0), (21.0, 31.0), (31.0, 61.0)])
            self.assertEqual([p.name.split("-")[2] for p, _, _ in out],
                             ["parked", "driving", "driving", "parked"])
            for path, _, _ in out:
                with Image.open(path) as image:
                    self.assertEqual(image.size, (rail_w, rail_h))

    def test_met_rail_covers_the_whole_video(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = ov.story_rail(ov.scenario_beats(MET), tmp, states=ov.vehicle_states(MET))
        self.assertEqual(out[0][1], 0.0)
        self.assertEqual(out[-1][2], float(MET["durationSeconds"]))
        for (_, _, end), (_, start, _) in zip(out, out[1:]):
            self.assertEqual(end, start)

    def test_unmatched_anchor_fails_loudly(self):
        scenario = {"id": "t", "durationSeconds": 60, "actions": [],
                    "storyBeats": [{"title": "A"}, {"title": "B", "anchor": {"type": "x"}}]}
        with self.assertRaises(ValueError):
            ov.scenario_beats(scenario)


if __name__ == "__main__":
    unittest.main()
