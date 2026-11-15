#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Tests for prime-demo-cache.py: the committed cache replays every scenario ask, the
turn derivation follows the scripted car state, and the check bites when it should.

  python3 scripts/test_prime_demo_cache.py
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("prime_demo_cache",
                                               HERE / "prime-demo-cache.py")
prime = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = prime
_spec.loader.exec_module(prime)


def scenario(actions: list[dict]) -> dict:
    return {"id": "synthetic", "durationSeconds": 60, "actions": actions}


def ask(at_ms: int) -> dict:
    return {"type": "agent", "atMs": at_ms, "provider": "muse", "ask": "Go"}


class PrimeTest(unittest.TestCase):
    def test_committed_cache_replays_every_scenario_ask(self):
        self.assertEqual(prime.main(["--check"]), 0)

    def test_key_is_stable_for_the_met_ask(self):
        gw = prime.gateway_for_keys()
        met = [t for t in prime.turns() if t["scenario"] == "nyc_met"]
        self.assertEqual(len(met), 1)
        key = prime.cache_key(gw, met[0])
        self.assertTrue(prime.entry_path("muse", "quality_cached", key).is_file())

    def test_parked_ask_takes_the_cached_lane(self):
        (turn,) = prime.turns([scenario([ask(5500)])])
        self.assertEqual(turn["profile"], "quality_cached")
        self.assertEqual(turn["display"],
                         {"id": 0, "activeRestrictions": 0, "trustworthy": True})

    def test_moving_ask_takes_the_low_latency_lane(self):
        (turn,) = prime.turns([scenario([{"type": "carState", "atMs": 1000,
                                          "state": "drive"},
                                         ask(5500)])])
        self.assertEqual(turn["profile"], "realtime_low_latency")
        self.assertEqual(turn["display"]["activeRestrictions"], 0x1FF)

    def test_an_empty_cache_fails_the_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            real = prime.COMMITTED_CACHE
            prime.COMMITTED_CACHE = pathlib.Path(tmp)
            try:
                self.assertEqual(prime.main(["--check"]), 1)
            finally:
                prime.COMMITTED_CACHE = real


if __name__ == "__main__":
    unittest.main()
