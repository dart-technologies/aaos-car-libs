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
"""Gate an instrumented run on executed count, not exit status.

`ignoreFailures = true` (upstream's) and `INSTRUMENTATION_CODE: 0` on a crashed process mean the
status carries no information; truncated runs have reported BUILD SUCCESSFUL. This exits non-zero
unless at least the expected number of tests executed.

    python3 scripts/check-instrumented-run.py <results-dir> --expect 497

`--expect` is the recorded baseline for the suite. Raise it deliberately; never lower it to pass.
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def parse(results_dir):
    """Returns (executed, failures, errors, skipped, files_seen).

    Counts <testcase> elements, not tests="" attributes: AGP 9's aggregate <testsuites> wrapper
    doubled the sum. `executed` excludes skipped tests, since an @Ignore'd test still emits a
    <testcase>.
    """
    executed = failures = errors = skipped = 0
    files = sorted(glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True))
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # Malformed is unreadable, not zero tests; left to the caller's "no results" arm.
            continue
        for case in root.iter("testcase"):
            tags = {child.tag for child in case}
            if "skipped" in tags:
                # Counted, and NOT credited as executed. See the docstring.
                skipped += 1
                continue
            executed += 1
            if "failure" in tags:
                failures += 1
            if "error" in tags:
                errors += 1
    return executed, failures, errors, skipped, files


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir")
    parser.add_argument("--expect", type=int, required=True,
                        help="minimum tests that must have EXECUTED")
    parser.add_argument("--max-failures", type=int, default=None,
                        help="optional ceiling; omit when a known-failing baseline is expected")
    parser.add_argument("--max-skipped", type=int, default=0,
                        help="ceiling on SKIPPED tests, default 0. Unlike --max-failures this "
                             "defaults to enforcing, because there is no known-skipping baseline "
                             "in this repo and a skip is silent where a failure is loud.")
    args = parser.parse_args()

    if not os.path.isdir(args.results_dir):
        print(f"FAIL: no results directory at {args.results_dir}", file=sys.stderr)
        print("      A run that produced no XML did not reach reporting.", file=sys.stderr)
        return 2

    executed, failures, errors, skipped, files = parse(args.results_dir)

    if not files:
        print(f"FAIL: no result XML under {args.results_dir}", file=sys.stderr)
        return 2

    # One device, one result file: Gradle runs connected tests on every attached device and merges
    # them, so a second emulator doubles the count. Only connected results are named per device
    # (JVM results are one file per class), hence the path check.
    is_connected = "androidTest-results" in args.results_dir.replace(os.sep, "/")
    if is_connected and len(files) > 1:
        print(f"FAIL: {len(files)} result files under {args.results_dir}, expected 1.",
              file=sys.stderr)
        print("      Connected-test results are named per DEVICE, so more than one file means the",
              file=sys.stderr)
        print("      suite ran on more than one device and the counts are merged. `executed >=",
              file=sys.stderr)
        print("      expected` cannot tell that apart from a complete run. Files seen:",
              file=sys.stderr)
        for f in files:
            print(f"        {os.path.basename(f)}", file=sys.stderr)
        return 2

    print(f"  executed={executed} failures={failures} errors={errors} skipped={skipped} "
          f"({len(files)} result file(s))")

    ok = True
    # Before the truncation arm: an all-@Ignore'd suite is not a crash.
    if skipped > args.max_skipped:
        print(f"FAIL: {skipped} test(s) were SKIPPED, exceeding the ceiling of "
              f"{args.max_skipped}.", file=sys.stderr)
        print("      A skipped test is not a passing test. Raise --max-skipped deliberately if",
              file=sys.stderr)
        print("      some suite legitimately skips, and say why where the ceiling is set.",
              file=sys.stderr)
        ok = False
    if executed < args.expect:
        print(f"FAIL: only {executed} of an expected {args.expect} tests executed.",
              file=sys.stderr)
        print("      The run was TRUNCATED — a crash, an aborted process, or a suite that never",
              file=sys.stderr)
        print("      started. Do not read the failure count: it describes a partial run.",
              file=sys.stderr)
        ok = False

    if args.max_failures is not None and failures > args.max_failures:
        print(f"FAIL: {failures} failures exceeds the ceiling of {args.max_failures}.",
              file=sys.stderr)
        ok = False

    if ok:
        note = "" if skipped == 0 else f", {skipped} skipped within the ceiling"
        print(f"  OK: run is complete ({executed} >= {args.expect}{note}).")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
