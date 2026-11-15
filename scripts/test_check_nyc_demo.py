#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Tests for check-nyc-demo.py's voice pacing: the device reacts to a turn only after
the guest finishes speaking plus a think beat, the reaction lands instantly, and the
next change waits out a readable dwell.

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

# "Reserve entry for two at three" is 30 chars: the speech ends at 16000 by estimate.
TEXT = "Reserve entry for two at three"
END_MS = 16000


def scenario(speak_ms: int, actions: list[dict] | None = None) -> dict:
    return {"id": "synthetic", "durationSeconds": 60,
            "clips": [{"id": "u1", "role": "user_utterance", "section": "timeline",
                       "atMs": 14000, "text": TEXT, "expectedTranscript": TEXT}],
            "actions": [{"type": "speak", "atMs": speak_ms, "clipId": "u1"},
                        *(actions or [])]}


class ThinkGapTest(unittest.TestCase):
    def test_committed_scenarios_all_leave_think_and_dwell(self):
        manifest = json.loads((ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
                               ).read_text(encoding="utf-8"))
        for item in manifest["scenarios"]:
            with self.subTest(scenario=item["id"]):
                nyc.check_think_gap(item)  # raises SystemExit on a violation
                nyc.check_dwell(item)

    def test_reaction_mid_utterance_fails(self):
        with self.assertRaises(SystemExit):
            nyc.check_think_gap(scenario(END_MS + 800))

    def test_exactly_the_think_gap_passes(self):
        nyc.check_think_gap(scenario(END_MS + nyc.THINK_GAP_MS))

    def test_next_change_inside_the_dwell_fails(self):
        with self.assertRaises(SystemExit):
            nyc.check_dwell(scenario(END_MS + nyc.THINK_GAP_MS,
                                     [{"type": "tapText", "atMs": END_MS + nyc.THINK_GAP_MS
                                       + nyc.DWELL_MS - 400, "text": "Confirm"}]))

    def test_exactly_the_dwell_passes(self):
        at = END_MS + nyc.THINK_GAP_MS
        nyc.check_dwell(scenario(at, [{"type": "tapText", "atMs": at + nyc.DWELL_MS,
                                      "text": "Confirm"}]))

    def test_ambient_inputs_are_not_ui_answers(self):
        # Driving off or the story clock moving after the reaction is the world, not a
        # change that needs dwell.
        at = END_MS + nyc.THINK_GAP_MS
        for kind, extra in [("carState", {"state": "drive"}),
                            ("wallClock", {"local": "2026-11-09T15:03"}),
                            ("vehicleSignal", {"signal": "battery", "percent": 80,
                                               "via": ["broadcast"]})]:
            with self.subTest(action=kind):
                nyc.check_dwell(scenario(at, [{"type": kind, "atMs": at + 100, **extra},
                                              {"type": "tapText", "atMs": at + 10000,
                                               "text": "Confirm"}]))

    def test_agent_ask_is_not_a_ui_answer(self):
        # The ask starts the turn; its UI arrives later over the gateway, paced by 4c.
        at = END_MS + nyc.THINK_GAP_MS
        nyc.check_dwell(scenario(at, [{"type": "agent", "atMs": at + 100,
                                      "provider": "muse", "ask": "Go"},
                                     {"type": "tapText", "atMs": at + 10000,
                                      "text": "Confirm"}]))


if __name__ == "__main__":
    unittest.main()
