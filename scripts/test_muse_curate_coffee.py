#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Network-free tests for muse-curate-coffee.py: walking minutes, citations, the open-hours gate,
the walk cap and a deterministic ranking, replayed from a recorded sample
(testdata/muse-curate-coffee-sample.json).

  python3 scripts/test_muse_curate_coffee.py
"""

from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import random
import sys
import tempfile
import unittest
import urllib.request
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("muse_curate_coffee", HERE / "muse-curate-coffee.py")
coffee = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = coffee
_spec.loader.exec_module(coffee)
SAMPLE = json.loads((HERE / "testdata/muse-curate-coffee-sample.json").read_text(encoding="utf-8"))


def offline(*_args, **_kwargs):
    raise AssertionError("scoring must not touch the network")


class Scoring(unittest.TestCase):

    def setUp(self) -> None:
        self.saved = (urllib.request.urlopen, coffee.adapters._post)
        urllib.request.urlopen = offline
        coffee.adapters._post = offline
        self.charger = SAMPLE["charger"]
        self.cafes = coffee.candidates(SAMPLE["overpass"], self.charger)
        self.found = SAMPLE["found"]
        self.shops = copy.deepcopy(SAMPLE["shops"])

    def tearDown(self) -> None:
        urllib.request.urlopen, coffee.adapters._post = self.saved

    def rank(self, shops=None, cafes=None):
        return coffee.rank(self.cafes if cafes is None else cafes,
                           self.shops if shops is None else shops, self.found)

    def shop(self, osm_id: str, shops=None) -> dict:
        return next(s for s in (self.shops if shops is None else shops) if s["id"] == osm_id)

    def out(self, osm_id: str, shops=None) -> dict | None:
        return next((d for d in self.rank(shops)[1] if d["osmId"] == osm_id), None)

    def test_walk_minutes_come_from_coordinates_not_the_model(self):
        self.assertEqual(coffee.walk_minutes(20), 1)
        self.assertEqual(coffee.walk_minutes(132), 3)    # 172 m on the grid: 2.1 min, rounded up
        self.assertEqual(coffee.walk_minutes(369), 6)    # 480 m: the cap
        self.assertEqual(coffee.walk_minutes(370), 7)
        origin = (self.charger["lat"], self.charger["lon"])
        for cafe in self.cafes:
            self.assertEqual(cafe["walkMinutes"],
                             coffee.walk_minutes(coffee.metres(origin, (cafe["lat"], cafe["lon"]))))
        self.assertNotIn("walk", json.dumps(coffee.scoring_schema(["node/1"])))

    def test_the_recorded_sample_picks_what_the_fixture_picked(self):
        ranked, out = self.rank()
        self.assertEqual(ranked[0]["osmId"], SAMPLE["pick"])
        self.assertEqual([r["rank"] for r in ranked], list(range(1, len(ranked) + 1)))
        totals = [r["total"] for r in ranked]
        self.assertEqual(totals, sorted(totals, reverse=True))
        # The total, by hand: 100 x sum(weight x score / 5), walk and open computed here.
        top = ranked[0]
        s = top["scores"]
        by_hand = 100 * (0.10 * top["openScore"] / 5 + 0.30 * s["oatMilkLatte"]["score"] / 5
                         + 0.25 * s["reputation"]["score"] / 5 + 0.15 * s["pickup"]["score"] / 5
                         + 0.20 * coffee.WALK_SCORE[top["walkMinutes"]] / 5)
        self.assertAlmostEqual(top["total"], round(by_hand, 1))
        self.assertTrue(all(d["reason"] for d in out))

    def test_a_fenced_or_wrapped_scoring_answer_parses_like_the_gateway(self):
        cafes = [c for c in self.cafes if c["osmId"] in {s["id"] for s in self.shops}]
        answer = json.dumps({"shops": self.shops})

        def reply(text):
            return {"status": "completed", "model": coffee.MODEL, "output": [
                {"type": "message", "content": [{"type": "output_text", "text": text}]}]}

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(coffee, "CACHE", pathlib.Path(tmp)):
            for text in (answer, f"```json\n{answer}\n```", f"Scores, as asked:\n{answer}"):
                with mock.patch.object(coffee, "call", return_value=reply(text)):
                    self.assertEqual(coffee.score("k", cafes, self.found, "low")[0], self.shops)
            with mock.patch.object(coffee, "call", return_value=reply("No scores today.")), \
                    self.assertRaises(coffee.adapters.AdapterError):
                coffee.score("k", cafes, self.found, "low")

    def test_no_cited_hours_means_out_not_guessed(self):
        pick = SAMPLE["pick"]
        for change, reason in (
                ({"sources": []}, "no cited evidence it is open Thursday 5-6 pm"),
                ({"sources": [99]}, "no cited evidence it is open Thursday 5-6 pm"),
                ({"verdict": "unknown"}, "no cited evidence it is open Thursday 5-6 pm"),
                ({"verdict": "closed"}, "not open Thursday 5-6 pm"),
                ({"closes": "17:30"}, "closes at 17:30 on Thursday")):
            shops = copy.deepcopy(self.shops)
            self.shop(pick, shops)["open"].update(change)
            dropped = self.out(pick, shops)
            self.assertIsNotNone(dropped, change)
            self.assertTrue(dropped["reason"].startswith(reason), dropped["reason"])
            self.assertNotIn(pick, [r["osmId"] for r in self.rank(shops)[0]])
        shops = copy.deepcopy(self.shops)
        self.shop(pick, shops)["operating"].update({"status": "closed", "sources": [1]})
        self.assertTrue(self.out(pick, shops)["reason"].startswith("closed (cited)"))

    def test_a_cafe_past_the_walk_cap_is_out_whatever_it_scores(self):
        far = [c for c in self.cafes if c["walkMinutes"] > coffee.WALK_CAP_MIN]
        self.assertTrue(far, "the sample keeps one café past the cap")
        perfect = copy.deepcopy(self.shop(SAMPLE["pick"]))
        perfect["id"] = far[0]["osmId"]
        found = dict(self.found, **{far[0]["osmId"]: self.found[SAMPLE["pick"]]})
        ranked, out = coffee.rank(self.cafes, self.shops + [perfect], found)
        self.assertNotIn(far[0]["osmId"], [r["osmId"] for r in ranked])
        reason = next(d["reason"] for d in out if d["osmId"] == far[0]["osmId"])
        self.assertEqual(reason, f"{far[0]['walkMinutes']} min walk is over the 6 min cap")

    def test_a_criterion_without_a_valid_citation_scores_zero(self):
        pick = SAMPLE["pick"]
        before = next(r for r in self.rank()[0] if r["osmId"] == pick)
        shops = copy.deepcopy(self.shops)
        self.shop(pick, shops)["reputation"].update({"score": 5, "sources": [0, 42]})
        after = next(r for r in self.rank(shops)[0] if r["osmId"] == pick)
        self.assertEqual(after["scores"]["reputation"]["score"], 0)
        self.assertTrue(after["scores"]["reputation"]["uncited"])
        self.assertAlmostEqual(
            before["total"] - after["total"],
            round(100 * 0.25 * before["scores"]["reputation"]["score"] / 5, 1), places=1)

    def test_the_ranking_is_deterministic_and_ties_go_to_the_shorter_walk(self):
        expected = [r["osmId"] for r in self.rank()[0]]
        rng = random.Random(5)
        for _ in range(20):
            cafes, shops = self.cafes[:], copy.deepcopy(self.shops)
            rng.shuffle(cafes)
            rng.shuffle(shops)
            self.assertEqual([r["osmId"] for r in self.rank(shops, cafes)[0]], expected)
        # Two cafés with the same scores: the nearer one leads, then the lower OSM id.
        ranked = self.rank()[0]
        a, b = ranked[0], ranked[1]
        shops = copy.deepcopy(self.shops)
        twin = copy.deepcopy(self.shop(a["osmId"]))
        twin["id"] = b["osmId"]
        shops = [twin if s["id"] == b["osmId"] else s for s in shops]
        found = dict(self.found, **{b["osmId"]: self.found[a["osmId"]]})
        tied = coffee.rank(self.cafes, shops, found)[0]
        first, second = [r for r in tied if r["osmId"] in (a["osmId"], b["osmId"])]
        if first["total"] == second["total"]:
            self.assertLessEqual((first["walkMinutes"], first["straightMetres"], first["osmId"]),
                                 (second["walkMinutes"], second["straightMetres"],
                                  second["osmId"]))

    def test_a_chain_is_not_marked_down_by_name(self):
        ranked = self.rank()[0]
        cafes = copy.deepcopy(self.cafes)
        for cafe in cafes:
            cafe["name"], cafe["brand"] = "Starbucks", "Starbucks"
        renamed = coffee.rank(cafes, self.shops, self.found)[0]
        self.assertEqual([(r["osmId"], r["total"]) for r in renamed],
                         [(r["osmId"], r["total"]) for r in ranked])

    def test_open_score_follows_the_cited_closing_time(self):
        def score(closes, model=5):
            return coffee.open_score({"closes": closes, "score": model})
        self.assertEqual(score("21:00"), 5)
        self.assertEqual(score("19:00"), 5)
        self.assertEqual(score("18:45"), 4)
        self.assertEqual(score("18:00"), 3)
        self.assertEqual(score(""), 3)            # no closing time: at most 3
        self.assertEqual(score("", model=2), 2)
        self.assertEqual(coffee.minutes_of("00:30"), 24 * 60 + 30)   # after midnight
        self.assertEqual([coffee.clock(t) for t in ("19:00", "19:30", "21:00", "00:00")],
                         ["7", "7:30", "9", "midnight"])

    def show(self, pick, cafes=None, compared=5, runner_up=None):
        return coffee.display(pick, self.charger, self.cafes if cafes is None else cafes,
                              compared=compared, runner_up=runner_up)

    def test_display_says_only_what_is_cited(self):
        ranked = self.rank()[0]
        top = copy.deepcopy(ranked[0])
        shown = self.show(top, compared=len(ranked), runner_up=ranked[1])
        self.assertEqual(shown["name"], top["name"])     # no namesake in the sample
        self.assertTrue(shown["why"].startswith(f"Muse compared {len(ranked)} cafés · "))
        self.assertTrue(shown["why"].endswith(f" · {top['walkMinutes']} min walk"))
        if top["scores"]["open"]["closes"]:
            self.assertIn(f"open till {coffee.clock(top['scores']['open']['closes'])}",
                          shown["why"])
        self.assertEqual(shown["source"], f"Runner-up: {ranked[1]['name']}")
        self.assertEqual(shown["sourceUrl"], top["scores"]["open"]["sources"][0]["url"])
        self.assertEqual(shown["charger"], "ChargePoint")
        self.assertIsNone(shown["price"])
        # Alone, the pick keeps its citation's host on that row instead.
        self.assertEqual(self.show(top)["source"], "Source · "
                         + coffee.host(top["scores"]["open"]["sources"][0]["url"]))
        self.assertTrue(self.show(top, compared=1)["why"].startswith("Muse compared 1 café · "))
        top["scores"]["oatMilkLatte"]["score"] = 0
        self.assertNotIn("oat milk", self.show(top)["why"])
        shops = copy.deepcopy(self.shops)
        self.shop(SAMPLE["pick"], shops)["price"] = {"price": "$6.25", "sources": [1]}
        priced = next(r for r in self.rank(shops)[0] if r["osmId"] == SAMPLE["pick"])
        self.assertEqual(self.show(priced)["price"], "$6.25")
        for price in ({"price": "$6.25", "sources": []}, {"price": "about six", "sources": [1]}):
            self.shop(SAMPLE["pick"], shops)["price"] = price
            unpriced = next(r for r in self.rank(shops)[0] if r["osmId"] == SAMPLE["pick"])
            self.assertIsNone(self.show(unpriced)["price"])

    def test_a_namesake_in_range_adds_the_osm_address(self):
        top = self.rank()[0][0]
        twin = dict(next(c for c in self.cafes if c["osmId"] != top["osmId"]),
                    name=top["name"], osmId="node/1")
        cafes = self.cafes + [twin]
        where = " ".join(coffee.STREET_WORDS.get(w, w) for w in top["address"].split())
        self.assertEqual(coffee.label(top, cafes), f"{top['name']}, {where}")
        self.assertEqual(self.show(top, cafes)["name"], f"{top['name']}, {where}")
        # A runner-up with a namesake in range is told apart the same way.
        self.assertEqual(self.show(self.rank()[0][1], cafes, runner_up=top)["source"],
                         f"Runner-up: {top['name']}, {where}")
        self.assertEqual(coffee.label(dict(top, address="226 East 83rd Street"), cafes),
                         f"{top['name']}, 226 E 83rd St")
        self.assertEqual(coffee.label(dict(top, address=None), cafes), top["name"])
        self.assertEqual(coffee.label(top, self.cafes), top["name"])

    def test_the_charger_is_the_first_row_host_b_draws(self):
        charger = coffee.first_charger(coffee.FIXTURES / "chargers/chargers.json")
        self.assertEqual((charger["hostLabel"], charger["osmId"]),
                         ("ChargePoint", "node/5620136933"))
        self.assertEqual(charger["osmId"], self.charger["osmId"])


if __name__ == "__main__":
    unittest.main()
