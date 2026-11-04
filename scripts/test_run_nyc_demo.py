#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Device-free tests for run-nyc-demo.py: scheduling, timeline wiring and ffmpeg composition.

  python3 scripts/test_run_nyc_demo.py     (composition tests need ffmpeg and ffprobe)

The timeline runs against a fake device and clock; composition renders synthetic recordings
(solid colours, sparse frame rates like screenrecord's) and reads pixels back from the output.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("run_nyc_demo", HERE / "run-nyc-demo.py")
demo = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = demo
_spec.loader.exec_module(demo)
MANIFEST = json.loads(demo.MANIFEST.read_text(encoding="utf-8"))
FFMPEG = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
DRIVER, PASSENGER = "4619827259835644672", "4619827551948147201"

DUAL = {
    "id": "t_dual", "corpusCase": "met_visit", "durationSeconds": 70,
    "recording": {"layout": "dual"},
    "clips": [{"id": "u1", "role": "user_utterance", "atMs": 5000, "text": "Go.",
               "expectedTranscript": "go"}],
    "actions": [
        {"atMs": 5000, "type": "speak", "clipId": "u1"},
        {"atMs": 20000, "type": "carState", "state": "drive"},
        {"atMs": 20000, "type": "passenger", "media": "artwork"},
        {"atMs": 30000, "type": "agent", "provider": "muse", "ask": "What should we see first?"},
        {"atMs": 50000, "type": "carState", "state": "park"},
        {"atMs": 51000, "type": "passenger", "media": "stop"},
        {"atMs": 60000, "type": "endCard"},
    ],
}


class FakeClock:
    def __init__(self) -> None:
        self.t = 1000.0

    def now(self) -> float:
        return self.t

    def sleep(self, seconds: float) -> None:
        self.t += max(0.0, seconds)


class FakeProc:
    def __init__(self, alive: bool = True) -> None:
        self.alive = alive
        self.stderr = None

    def poll(self):
        return None if self.alive else 1


class FakeDevice:
    """Records every call with the fake clock's time since the driver recorder started."""

    def __init__(self, clock: FakeClock, *, screencap_ok: bool = True, dead: set = frozenset()):
        self.clock, self.screencap_ok, self.dead = clock, screencap_ok, dead
        self.calls: list[tuple] = []
        self.origin: float | None = None

    def _log(self, *call) -> None:
        if self.origin is None:
            self.origin = self.clock.now()
        self.calls.append((round(self.clock.now() - self.origin, 3),) + call)

    def broadcast(self, action, *extras):
        self._log("broadcast", action, extras)

    def tap_text(self, text):
        self._log("tap", text)

    def set_car_state(self, state):
        self._log("car", state)

    def key_back(self):
        self._log("back")

    def start_recording(self, display, remote, limit):
        self._log("record", display, remote, limit)
        return FakeProc(alive=display not in self.dead)

    def require_alive(self, proc, what):
        pass

    def alive(self, proc):
        return proc.poll() is None

    def stop_recordings(self, procs):
        self._log("stop", len(procs))

    def screencap(self, display, path):
        self._log("screencap", display)
        if self.screencap_ok:
            path.write_bytes(b"\x89PNG\r\n\x1a\n")
        return self.screencap_ok

    def shell(self, *args):
        self._log("shell", args)
        return subprocess.CompletedProcess(args, 0, "", "")


def timeline(scenario: dict, device: FakeDevice, tmp: pathlib.Path, passenger=PASSENGER):
    return demo.run_timeline(scenario, device, FakeClock() if device is None else device.clock,
                             driver_display=DRIVER, passenger_display=passenger,
                             audio_dir=tmp / "no-audio", allow_scripted=True,
                             still_path=tmp / "idle.png")


class ScheduleTest(unittest.TestCase):
    def test_single_display_schedule_is_the_actions_in_order(self):
        scenario = next(s for s in MANIFEST["scenarios"] if s["id"] == "nyc_poi_order")
        steps = demo.schedule(scenario, dual=False)
        self.assertEqual([(s.at_ms, s.kind) for s in steps],
                         [(a["atMs"], a["type"]) for a in scenario["actions"]])

    def test_dual_schedule_starts_the_passenger_stream_right_before_its_first_action(self):
        steps = [(s.at_ms, s.kind) for s in demo.schedule(DUAL, dual=True)]
        self.assertIn((17000, "passengerStill"), steps)
        record = steps.index((20000, "passengerRecord"))
        self.assertEqual(steps[record + 1], (20000, "passenger"))
        self.assertEqual(steps[record - 1], (20000, "carState"))
        self.assertLess(steps.index((17000, "passengerStill")), record)

    def test_dual_needs_a_passenger_action(self):
        scenario = dict(DUAL, actions=[a for a in DUAL["actions"] if a["type"] != "passenger"])
        with self.assertRaises(demo.DemoFailure):
            demo.schedule(scenario, dual=True)

    def test_every_manifest_scenario_schedules(self):
        for scenario in MANIFEST["scenarios"]:
            demo.schedule(scenario, demo.layout_of(scenario) == "dual")

    def test_layout_must_match_the_passenger_flag(self):
        single = next(s for s in MANIFEST["scenarios"] if s["id"] == "nyc_concierge")
        self.assertEqual(demo.resolve_layout(single, None), "single")
        with self.assertRaises(demo.DemoFailure):
            demo.resolve_layout(single, PASSENGER)
        with self.assertRaises(demo.DemoFailure):
            demo.resolve_layout(DUAL, None)
        self.assertEqual(demo.resolve_layout(DUAL, PASSENGER), "dual")

    def test_duration_is_exact_and_bounded(self):
        demo.check_duration(178.02, 178)
        demo.check_duration(60.0, 60)
        for actual, expected in ((177.85, 178), (59.9, 60), (181.0, 181), (90.5, 90)):
            with self.assertRaises(demo.DemoFailure, msg=(actual, expected)):
                demo.check_duration(actual, expected)


class TimelineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_dual_timeline_wiring(self):
        device = FakeDevice(FakeClock())
        evidence = timeline(DUAL, device, self.tmp)
        calls = device.calls
        self.assertEqual(calls[0], (0.0, "record", DRIVER, demo.DEVICE_VIDEO, 75))
        self.assertEqual(calls[1], (0.0, "broadcast", demo.ACTION_CASE,
                                    ("--es", demo.EXTRA_CASE, "met_visit")))
        self.assertIn((17.0, "screencap", PASSENGER), calls)
        record = calls.index((20.0, "record", PASSENGER, demo.DEVICE_PASSENGER_VIDEO, 55))
        self.assertEqual(calls[record + 1], (20.0, "broadcast", demo.ACTION_PASSENGER,
                                             ("--es", demo.EXTRA_MEDIA, "artwork")))
        self.assertIn((5.0, "broadcast", demo.ACTION_SPEAK, ("--es", demo.EXTRA_PHRASE, "go")),
                      calls)
        self.assertIn((30.0, "broadcast", demo.ACTION_AGENT,
                       ("--es", demo.EXTRA_PROVIDER, "muse", "--es", demo.EXTRA_ASK,
                        "What should we see first?")), calls)
        self.assertIn((60.0, "broadcast", demo.ACTION_END_CARD, ()), calls)
        self.assertEqual(calls[-3:], [
            (70.0, "stop", 2), (70.0, "car", "park"),
            (70.0, "broadcast", demo.ACTION_PASSENGER, ("--es", demo.EXTRA_MEDIA, "stop"))])
        self.assertEqual(evidence["passengerStartSeconds"], 20.0)
        self.assertEqual(evidence["passengerStill"], self.tmp / "idle.png")
        self.assertEqual(evidence["transcripts"][0]["source"], "scripted-preflight")

    def test_single_timeline_records_one_display_and_never_touches_the_passenger(self):
        scenario = next(s for s in MANIFEST["scenarios"] if s["id"] == "nyc_poi_order")
        device = FakeDevice(FakeClock())
        evidence = timeline(scenario, device, self.tmp, passenger=None)
        records = [c for c in device.calls if c[1] == "record"]
        self.assertEqual(records, [(0.0, "record", DRIVER, demo.DEVICE_VIDEO, 83)])
        self.assertFalse([c for c in device.calls if c[1] == "screencap"
                          or (c[1] == "broadcast" and c[2] == demo.ACTION_PASSENGER)])
        self.assertEqual([c[2] for c in device.calls if c[1] == "tap"],
                         ["Preview pickup route", "Confirm", "Confirm"])
        self.assertEqual(device.calls[-2:], [(78.0, "stop", 1), (78.0, "car", "park")])
        self.assertIsNone(evidence["passengerStartSeconds"])

    def test_a_dead_passenger_stream_fails_after_cleanup(self):
        device = FakeDevice(FakeClock(), dead={PASSENGER})
        with self.assertRaisesRegex(demo.DemoFailure, "passenger screenrecord exited"):
            timeline(DUAL, device, self.tmp)
        self.assertIn((70.0, "stop", 2), device.calls)
        self.assertIn((70.0, "car", "park"), device.calls)

    def test_missing_idle_frame_is_reported_not_fatal(self):
        device = FakeDevice(FakeClock(), screencap_ok=False)
        evidence = timeline(DUAL, device, self.tmp)
        self.assertIsNone(evidence["passengerStill"])
        self.assertEqual(evidence["passengerStartSeconds"], 20.0)


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def solid(path: pathlib.Path, segments: list[tuple[str, float]], size="1408x792", rate=10):
    """A synthetic screen recording: solid-colour segments at a sparse frame rate."""
    inputs, labels = [], ""
    for index, (colour, seconds) in enumerate(segments):
        inputs += ["-f", "lavfi", "-i", f"color=c={colour}:s={size}:r={rate}:d={seconds}"]
        labels += f"[{index}:v]"
    ffmpeg(*inputs, "-filter_complex", f"{labels}concat=n={len(segments)}:v=1:a=0[v]",
           "-map", "[v]", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
           str(path))


def still(path: pathlib.Path, colour: str, size: str) -> None:
    ffmpeg("-f", "lavfi", "-i", f"color=c={colour}:s={size}", "-frames:v", "1", str(path))


def tone(path: pathlib.Path, seconds: float) -> None:
    ffmpeg("-f", "lavfi", "-i", f"sine=frequency=660:sample_rate=44100:duration={seconds}",
           "-c:a", "libmp3lame", "-q:a", "4", str(path))


def pixel(path: pathlib.Path, t: float, x: int, y: int) -> tuple[int, int, int]:
    probe = json.loads(subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "json", str(path)], check=True, capture_output=True,
        text=True).stdout)["streams"][0]
    frame = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t}", "-i", str(path),
                            "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           check=True, capture_output=True).stdout
    offset = (y * probe["width"] + x) * 3
    return tuple(frame[offset:offset + 3])


def max_volume(path: pathlib.Path, start: float, seconds: float) -> float:
    result = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-ss", f"{start}", "-t",
                             f"{seconds}", "-i", str(path), "-map", "0:a:0", "-af",
                             "volumedetect", "-f", "null", "-"], capture_output=True, text=True)
    line = next(l for l in result.stderr.splitlines() if "max_volume" in l)
    return float(line.split("max_volume:")[1].split("dB")[0])


def near(actual, expected, tolerance=48) -> bool:
    return all(abs(a - e) <= tolerance for a, e in zip(actual, expected))


RED, BLUE, MAGENTA, GREEN, YELLOW, WHITE = ((255, 0, 0), (0, 0, 255), (255, 0, 255),
                                            (0, 128, 0), (255, 255, 0), (255, 255, 255))
BG = (11, 15, 20)


@unittest.skipUnless(FFMPEG, "ffmpeg/ffprobe not installed")
class CompositionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = pathlib.Path(tempfile.mkdtemp())
        tmp = cls.tmp
        solid(tmp / "driver.mp4", [("red", 4), ("blue", 1.5), ("magenta", 1)])
        solid(tmp / "passenger.mp4", [("green", 3)], rate=2)
        still(tmp / "idle.png", "yellow", "1408x792")
        still(tmp / "title.png", "white", "1920x1080")
        (tmp / "audio" / "sfx").mkdir(parents=True)
        tone(tmp / "audio" / "n1.mp3", 0.8)
        tone(tmp / "audio" / "n2.mp3", 0.8)
        tone(tmp / "audio" / "sfx" / "chime.mp3", 0.5)
        cls.plan = demo.RenderPlan(
            duration=8, layout="dual", driver_raw=tmp / "driver.mp4",
            passenger_raw=tmp / "passenger.mp4", passenger_start=3.0,
            passenger_still=tmp / "idle.png", title=tmp / "title.png", title_seconds=2.0,
            hold_from=5.0, audio_dir=tmp / "audio",
            clips=[{"id": "n1", "atMs": 500}, {"id": "n2", "atMs": 3000}],
            cues=[{"effectId": "chime", "atMs": 6000, "gainDb": -12}])
        cls.out = tmp / "dual.mp4"
        demo.render(cls.plan, cls.out)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def test_canvas_streams_and_exact_duration(self):
        info = json.loads(subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "format=duration:stream=codec_type,codec_name,width,height", "-of", "json",
             str(self.out)], check=True, capture_output=True, text=True).stdout)
        video = [s for s in info["streams"] if s["codec_type"] == "video"]
        audio = [s for s in info["streams"] if s["codec_type"] == "audio"]
        self.assertEqual((video[0]["codec_name"], video[0]["width"], video[0]["height"]),
                         ("h264", 1920, 1080))
        self.assertEqual([s["codec_name"] for s in audio], ["aac"])
        self.assertAlmostEqual(float(info["format"]["duration"]), 8.0, delta=0.1)

    def test_title_card_covers_the_opening_then_leaves(self):
        self.assertTrue(near(pixel(self.out, 1.0, 960, 540), WHITE))
        self.assertTrue(near(pixel(self.out, 2.5, 960, 540), RED))

    def test_panels_background_and_labels(self):
        driver, passenger = (656, 540), (1600, 351)
        self.assertTrue(near(pixel(self.out, 2.5, *driver), RED))
        self.assertTrue(near(pixel(self.out, 2.5, *passenger), YELLOW), "idle frame first")
        self.assertTrue(near(pixel(self.out, 4.5, *driver), BLUE))
        self.assertTrue(near(pixel(self.out, 4.5, *passenger), GREEN), "stream at its start")
        self.assertTrue(near(pixel(self.out, 4.5, 10, 10), BG, 12))
        label = [pixel(self.out, 4.5, x, 160) for x in range(40, 200, 4)]
        self.assertGreater(max(max(p) for p in label), 150, "driver label is drawn")
        label = [pixel(self.out, 4.5, x, 160) for x in range(1320, 1530, 4)]
        self.assertGreater(max(max(p) for p in label), 150, "passenger label is drawn")

    def test_end_card_hold_freezes_both_panels(self):
        # Without the hold the driver panel would show the magenta tail at 7 s.
        self.assertTrue(near(pixel(self.out, 7.0, 656, 540), BLUE))
        self.assertTrue(near(pixel(self.out, 7.5, 1600, 351), GREEN))

    def test_audio_is_placed_on_the_manifest_timeline(self):
        self.assertLess(max_volume(self.out, 0.0, 0.4), -60)
        self.assertGreater(max_volume(self.out, 0.6, 0.6), -30)
        self.assertLess(max_volume(self.out, 1.5, 1.3), -60)
        self.assertGreater(max_volume(self.out, 3.1, 0.6), -30)

    def test_single_display_title_card(self):
        plan = demo.RenderPlan(duration=6, driver_raw=self.tmp / "driver.mp4",
                               title=self.tmp / "title.png", title_seconds=2.0)
        out = self.tmp / "single.mp4"
        demo.render(plan, out)
        self.assertEqual(plan.driver_size, (1408, 792))
        self.assertTrue(near(pixel(out, 1.0, 700, 400), WHITE))
        self.assertTrue(near(pixel(out, 3.0, 700, 400), RED))
        self.assertAlmostEqual(demo.probe_duration(out), 6.0, delta=0.1)


def legacy_command(audio_dir, raw_video, final_video, clips, cues, target_seconds):
    """run-nyc-demo.py's mux_audio() before dual-display support, verbatim, as the oracle."""
    speech_inputs = [str(audio_dir / f"{clip['id']}.mp3") for clip in clips]
    effect_inputs = [str(audio_dir / "sfx" / f"{cue['effectId']}.mp3") for cue in cues]
    inputs = speech_inputs + effect_inputs
    command = ["ffmpeg", "-y", "-v", "error", "-i", str(raw_video)]
    for path in inputs:
        command.extend(["-i", path])
    filters = [f"[0:v]fps=30,tpad=stop_mode=clone:stop_duration={target_seconds},"
               f"trim=duration={target_seconds}[v]"]
    labels = []
    for index, clip in enumerate(clips, start=1):
        label = f"a{index}"
        filters.append(f"[{index}:a]adelay=delays={int(clip['atMs'])}:all=1[{label}]")
        labels.append(f"[{label}]")
    for offset, cue in enumerate(cues, start=1 + len(clips)):
        label = f"a{offset}"
        filters.append(f"[{offset}:a]volume={float(cue['gainDb']):g}dB,"
                       f"adelay=delays={int(cue['atMs'])}:all=1[{label}]")
        labels.append(f"[{label}]")
    filters.append("".join(labels) + f"amix=inputs={len(labels)}:duration=longest:normalize=0,"
                   f"atrim=duration={target_seconds}[a]")
    command.extend(["-filter_complex", ";".join(filters), "-map", "[v]", "-map", "[a]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                    "-t", str(target_seconds), "-movflags", "+faststart",
                    str(final_video)])
    return command


class LegacyTest(unittest.TestCase):
    def test_old_scenarios_render_exactly_as_before(self):
        tmp = pathlib.Path(tempfile.mkdtemp())
        try:
            for scenario in MANIFEST["scenarios"]:
                if demo.layout_of(scenario) != "single" or scenario.get("titleCard") \
                        or scenario.get("endCardHoldFromMs") is not None:
                    continue
                audio = tmp / scenario["id"]
                (audio / "sfx").mkdir(parents=True)
                for clip in scenario["clips"]:
                    (audio / f"{clip['id']}.mp3").touch()
                for cue in scenario["soundEffects"]:
                    (audio / "sfx" / f"{cue['effectId']}.mp3").touch()
                plan = demo.RenderPlan(duration=scenario["durationSeconds"],
                                       driver_raw=tmp / "raw.mp4", audio_dir=audio,
                                       clips=scenario["clips"], cues=scenario["soundEffects"])
                self.assertEqual(
                    demo.render_command(plan, tmp / "out.mp4"),
                    legacy_command(audio, tmp / "raw.mp4", tmp / "out.mp4", scenario["clips"],
                                   scenario["soundEffects"], scenario["durationSeconds"]),
                    scenario["id"])
        finally:
            shutil.rmtree(tmp)


CAPACITY = ("VehiclePropValue{timestamp: 1, areaId: 0, prop: 291504390, status: AVAILABLE, "
            "value: RawPropValues{int32Values: [], floatValues: [150000.0], int64Values: []}}")
LEVEL = CAPACITY.replace("291504390", "291504905").replace("150000.0", "120000.0")
BATTERY = {"atMs": 40000, "type": "vehicleSignal", "signal": "battery", "percent": 18,
           "via": ["vhal-set", "vhal-inject", "broadcast"]}
SET, INJECT = "0x11600309", "inject-vhal-event"


class VhalDevice(FakeDevice):
    """A fake whose shell answers by matching tokens: [((token, ...), exit, stdout), ...]."""

    def __init__(self, clock, outputs):
        super().__init__(clock)
        self.outputs = outputs

    def shell(self, *args):
        self._log("shell", args)
        for tokens, code, out in self.outputs:
            if all(token in args for token in tokens):
                return subprocess.CompletedProcess(args, code, out, "")
        return subprocess.CompletedProcess(args, 0, "", "")


class VehicleSignalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.scenario = dict(DUAL, actions=DUAL["actions"] + [BATTERY])

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_battery_level_by_every_route_then_restored(self):
        device = VhalDevice(FakeClock(), [
            (("get-property-value", "0x11600106"), 0, CAPACITY),
            (("get-property-value", SET), 0, LEVEL),
            (("--set",), 0, "Set property: ..."),
        ])
        evidence = timeline(self.scenario, device, self.tmp)
        signal = evidence["vehicleSignals"][0]
        self.assertEqual((signal["levelWh"], signal["capacityWh"], signal["capacitySource"],
                          signal["previousWh"]), (27000.0, 150000.0, "INFO_EV_BATTERY_CAPACITY",
                                                  120000.0))
        self.assertTrue(all(route["ok"] for route in signal["routes"]))
        at_40 = [c[1:] for c in device.calls if c[0] == 40.0]
        self.assertEqual(at_40, [
            ("shell", ("cmd", "car_service", "get-property-value", "0x11600106", "0")),
            ("shell", ("cmd", "car_service", "get-property-value", SET, "0")),
            ("shell", ("dumpsys", demo.VHAL_SERVICE, "--set", SET, "-f", "27000")),
            ("shell", ("cmd", "car_service", INJECT, SET, "27000")),
            ("broadcast", demo.ACTION_VEHICLE, ("--ei", demo.EXTRA_BATTERY_PCT, "18")),
        ])
        stop = device.calls.index((70.0, "stop", 2))
        self.assertEqual(device.calls[stop + 1:stop + 4], [
            (70.0, "shell", ("dumpsys", demo.VHAL_SERVICE, "--set", SET, "-f", "120000")),
            (70.0, "broadcast", demo.ACTION_VEHICLE, ("--ei", demo.EXTRA_BATTERY_PCT, "-1")),
            (70.0, "car", "park")])

    def test_unreadable_capacity_falls_back_and_broadcast_only_never_restores(self):
        scenario = dict(DUAL, actions=DUAL["actions"] + [dict(BATTERY, via=["broadcast"])])
        device = VhalDevice(FakeClock(), [(("0x11600106",), 0, "Cannot get property value")])
        signal = timeline(scenario, device, self.tmp)["vehicleSignals"][0]
        self.assertEqual((signal["levelWh"], signal["capacitySource"], signal["previousWh"]),
                         (27000.0, "fallback", None))
        self.assertFalse([c for c in device.calls if c[1] == "shell" and "--set" in c[2]])

    def test_no_working_route_fails_after_cleanup(self):
        scenario = dict(DUAL, actions=DUAL["actions"] + [
            dict(BATTERY, via=["vhal-set", "vhal-inject"])])
        device = VhalDevice(FakeClock(), [
            (("--set",), 0, "failed to set property: EV_BATTERY_LEVEL, error: NOT_AVAILABLE"),
            ((INJECT,), 255, "Invalid argument")])
        with self.assertRaisesRegex(demo.DemoFailure, "no route injected"):
            timeline(scenario, device, self.tmp)
        self.assertEqual([c[1:] for c in device.calls if c[1] in ("stop", "car")][-2:],
                         [("stop", 2), ("car", "park")])

    def test_property_value_formats(self):
        self.assertEqual(demo.property_float(CAPACITY), 150000.0)
        self.assertEqual(demo.property_float("CarPropertyValue{mValue=27000.5, mAreaId=0}"),
                         27000.5)
        self.assertIsNone(demo.property_float("Cannot get property value for 0x11600106"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
