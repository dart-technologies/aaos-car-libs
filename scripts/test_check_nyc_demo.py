#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Tests for check-nyc-demo.py's think-gap rule: the UI answers a voice turn only after
the guest finishes speaking plus a beat, never under the still-playing utterance.

  python3 scripts/test_check_nyc_demo.py
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
_spec = importlib.util.spec_from_file_location("check_nyc_demo", HERE / "check-nyc-demo.py")
nyc = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = nyc
_spec.loader.exec_module(nyc)


def scenario(actions: list[dict], text: str = "Reserve entry for two at three") -> dict:
    return {"id": "synthetic", "durationSeconds": 60,
            "clips": [{"id": "u1", "role": "user_utterance", "section": "timeline",
                       "atMs": 14000, "text": text, "expectedTranscript": text}],
            "actions": [{"type": "speak", "atMs": 15000, "clipId": "u1"}, *actions]}


class ThinkGapTest(unittest.TestCase):
    def test_committed_scenarios_all_leave_a_think_gap(self):
        manifest = json.loads((ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
                               ).read_text(encoding="utf-8"))
        for item in manifest["scenarios"]:
            with self.subTest(scenario=item["id"]):
                nyc.check_think_gap(item)  # raises SystemExit on a violation

    def test_ui_under_the_utterance_fails(self):
        # "Reserve entry for two at three" is 30 chars, ~2 s at the estimate rate; a tap
        # 800 ms after the speech ends reads as the UI answering mid-sentence.
        with self.assertRaises(SystemExit):
            nyc.check_think_gap(scenario([{"type": "tapText", "atMs": 16800,
                                           "text": "Confirm"}]))

    def test_exactly_the_gap_passes(self):
        end_ms = 14000 + len("Reserve entry for two at three") / 15.0 * 1000
        at = int(end_ms + nyc.THINK_GAP_MS)
        nyc.check_think_gap(scenario([{"type": "tapText", "atMs": at, "text": "Confirm"}]))

    def test_ambient_inputs_are_not_ui_answers(self):
        # Driving off or the story clock moving under speech is the world, not the answer.
        for kind, extra in [("carState", {"state": "drive"}),
                            ("wallClock", {"local": "2026-11-09T15:03"}),
                            ("vehicleSignal", {"signal": "battery", "percent": 80,
                                               "via": ["broadcast"]})]:
            with self.subTest(action=kind):
                nyc.check_think_gap(scenario([{"type": kind, "atMs": 16000, **extra},
                                              {"type": "tapText", "atMs": 30000,
                                               "text": "Confirm"}]))

    def test_agent_ask_is_not_a_ui_answer(self):
        # The ask starts the turn; its UI arrives later over the gateway, paced by 4c.
        nyc.check_think_gap(scenario([{"type": "agent", "atMs": 16000,
                                      "provider": "muse", "ask": "Go"},
                                     {"type": "tapText", "atMs": 30000,
                                      "text": "Confirm"}]))


if __name__ == "__main__":
    unittest.main()
