#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Tests for check-muse-claims.py: the committed fixtures pass, every mutation of a copy fails,
and CI and the local gate both run it.

  python3 scripts/test_check_muse_claims.py
"""

from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "check-muse-claims.py"
_spec = importlib.util.spec_from_file_location("check_muse_claims", SCRIPT)
claims = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = claims
_spec.loader.exec_module(claims)
BRIEF, COFFEE, GUIDE = "met/BRIEF.json", "coffee/COFFEE.json", "met/AUDIO_GUIDE.json"
SUMMARY = re.compile(r"^citation coverage: (\d+)/(\d+) claims cited \(.+\)\n$")


def components(brief: dict) -> list[dict]:
    return [c for m in brief["output"]["messages"]
            for c in m.get("updateComponents", {}).get("components", [])]


def component(brief: dict, cid: str) -> dict:
    return next(c for c in components(brief) if c["id"] == cid)


class MuseClaimsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.data = {name: json.loads((claims.FIXTURES / name).read_text(encoding="utf-8"))
                     for name in (BRIEF, COFFEE, GUIDE)}

    def write(self, mutate=None) -> pathlib.Path:
        data = copy.deepcopy(self.data)
        if mutate:
            mutate(data)
        for name, value in data.items():
            path = self.tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return self.tmp

    def assertCaught(self, mutate, *expected: str):
        failures = claims.check(self.write(mutate)).failures
        for text in expected:
            self.assertTrue(any(text in f for f in failures), f"{text!r} not in {failures}")

    def test_the_committed_fixtures_pass_and_every_fixture_has_claims(self):
        tally = claims.check(claims.FIXTURES)
        self.assertEqual(tally.failures, [])
        self.assertEqual(set(tally.claims), {BRIEF, COFFEE, GUIDE})
        for name, (backed, total) in tally.claims.items():
            self.assertGreater(total, 0, name)
            self.assertEqual(backed, total, name)
        # An unmutated copy passes, so each failure below is its mutation's.
        self.assertEqual(claims.check(self.write()).failures, [])

    def test_the_cli_prints_one_coverage_line_and_its_exit_code_is_the_verdict(self):
        good = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual((good.returncode, good.stderr), (0, ""))
        match = SUMMARY.match(good.stdout)
        self.assertIsNotNone(match, good.stdout)
        self.assertEqual(match.group(1), match.group(2))
        bad = subprocess.run([sys.executable, str(SCRIPT), "--fixtures",
                              str(self.write(lambda d: d[BRIEF].update(citations=[])))],
                             capture_output=True, text=True)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("FAIL: met/BRIEF.json: no url citation", bad.stderr)
        match = SUMMARY.match(bad.stdout)
        self.assertLess(int(match.group(1)), int(match.group(2)))

    def test_a_missing_fixture_fails(self):
        (self.write() / GUIDE).unlink()
        self.assertTrue(any(f.startswith(GUIDE) for f in claims.check(self.tmp).failures))

    # BRIEF.json

    def test_brief_without_citations_fails(self):
        self.assertCaught(lambda d: d[BRIEF].update(citations=[]),
                          "no url citation", "is none of its citations",
                          "row hours has no cited page")

    def test_brief_source_row_that_is_no_citation_fails(self):
        self.assertCaught(lambda d: component(d[BRIEF], "source_2").update(
            title="Source · nytimes.com"), "'Source · nytimes.com' is none of its citations")
        # Host B reads "Source" off any titled node, not only Text.
        self.assertCaught(lambda d: component(d[BRIEF], "nav_btn").update(
            title="Source · example.com"), "'Source · example.com' is none of its citations")

    def test_brief_showing_no_source_row_fails(self):
        def drop(d):
            for m in d[BRIEF]["output"]["messages"]:
                update = m.get("updateComponents")
                if update:
                    update["components"] = [c for c in update["components"]
                                            if not c["id"].startswith("source_")]
        self.assertCaught(drop, "shows no Source row")

    # COFFEE.json

    def test_a_scored_criterion_without_a_citation_fails(self):
        self.assertCaught(lambda d: d[COFFEE]["ranked"][0]["scores"]["reputation"].update(
            sources=[]), "reputation scores 5 with no evidence or cited URL")

    def test_a_scored_criterion_without_evidence_fails(self):
        self.assertCaught(lambda d: d[COFFEE]["ranked"][3]["scores"]["pickup"].update(
            evidence=" "), "pickup scores 5 with no evidence or cited URL")

    def test_an_uncited_price_fails(self):
        def drop(d):
            next(r for r in d[COFFEE]["ranked"] if r["scores"]["price"])["scores"]["price"][
                "sources"] = []
        self.assertCaught(drop, "is uncited")

    def test_a_disqualified_cafe_without_a_reason_fails(self):
        self.assertCaught(lambda d: d[COFFEE]["disqualified"][0].update(reason=""),
                          "has no reason")

    def test_renaming_the_pick_fails(self):
        self.assertCaught(lambda d: d[COFFEE].update(pick=d[COFFEE]["ranked"][1]["osmId"]),
                          "is not rank 1")
        self.assertCaught(lambda d: d[COFFEE]["display"].update(
            name=d[COFFEE]["ranked"][1]["name"]), "displayed name")
        self.assertCaught(lambda d: d[COFFEE]["display"].update(name="Starbucks"),
                          "displayed name")  # it has namesakes in range: the address is shown

    def test_a_why_the_ranking_does_not_back_fails(self):
        def why(old, new):
            return lambda d: d[COFFEE]["display"].update(
                why=d[COFFEE]["display"]["why"].replace(old, new))
        self.assertCaught(why("open till 7", "open till 9"), "'open till 9'")
        self.assertCaught(why("compared 15", "compared 16"), "15 cafés were scored")
        self.assertCaught(why("3 min walk", "2 min walk"), "'2 min walk'")
        self.assertCaught(why(" · oat milk", " · oat milk · free refills"), "'free refills'")

        def no_oat(d):
            d[COFFEE]["ranked"][0]["scores"]["oatMilkLatte"]["score"] = 2
        self.assertCaught(no_oat, "'oat milk'")

    def test_a_wrong_runner_up_or_source_url_fails(self):
        self.assertCaught(lambda d: d[COFFEE]["display"].update(
            source="Runner-up: " + d[COFFEE]["ranked"][2]["name"]), "ranking says")
        self.assertCaught(lambda d: d[COFFEE]["display"].update(
            sourceUrl="https://example.com/made-up"), "displayed source URL")

    def test_a_ranking_the_scores_do_not_give_fails(self):
        self.assertCaught(lambda d: d[COFFEE]["ranked"][2].update(total=99.0),
                          "is not its scores'", "not in rank order")

        def swap(d):
            ranked = d[COFFEE]["ranked"]
            ranked[0], ranked[1] = ranked[1], ranked[0]
        self.assertCaught(swap, "not in rank order", "is not rank 1")

    # AUDIO_GUIDE.json

    def test_a_met_source_for_another_object_fails(self):
        self.assertCaught(lambda d: d[GUIDE]["guide"][0].update(objectID=437881),
                          "cites another Met page", "has no valid source")
        self.assertCaught(lambda d: d[GUIDE]["guide"][1]["sources"][0].update(
            url="https://www.metmuseum.org/art/collection/search/436535"),
            "cites another Met page")
        self.assertCaught(lambda d: d[GUIDE]["guide"][3]["sources"][0].update(
            url="https://www.metmuseum.org/essays/bruegel"), "cites another Met page")

    def test_a_line_without_a_source_fails(self):
        self.assertCaught(lambda d: d[GUIDE]["guide"][2].update(sources=[]),
                          "has no valid source")

    def test_a_line_over_eleven_words_fails(self):
        self.assertCaught(lambda d: d[GUIDE]["guide"][4].update(
            text="Socrates reaches calmly for the bitter hemlock cup in Jacques-Louis David's "
                 "story."),
            "is 12 words")

    # Wiring: a gate nothing runs guards nothing.

    def test_ci_and_the_local_gate_run_it(self):
        root = HERE.parent
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertRegex(ci, r"(?m)^\s+(run: )?python3 scripts/check-muse-claims\.py$")
        self.assertRegex(ci, r"(?m)^\s+(run: )?python3 scripts/test_check_muse_claims\.py$")
        gate = (root / "scripts/run-bump-gate.sh").read_text(encoding="utf-8")
        self.assertRegex(gate, r"(?m)^out=\$\(python3 \$REPO/scripts/check-muse-claims\.py ")


if __name__ == "__main__":
    unittest.main()
