#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Writes docs/gate-counts.tsv from a real test run, and verifies it hasn't drifted.

A hand-edited expected count is indistinguishable in a diff from a legitimate one, so counts are
data written from an actual run, stamped with its date and commit. --record refuses a run with
failures: a ledger that absorbs a red run is a ratchet pointing the wrong way.

Usage:
  record-gate-counts.py --record     # after a green run; rewrites the ledger
  record-gate-counts.py --check      # what the gate calls; compares ledger to the run
  record-gate-counts.py --print      # emit `module count` pairs for the shell to read

Exit codes: 0 agreement, 1 drift (or a refused record), 2 could not be determined.
"""

import argparse
import glob
import subprocess
import sys
# stdlib ElementTree, deliberately: this repo's own build output. See dump-failing-set.py.
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'aaos-apps-gradle-project' / 'build'
LEDGER = ROOT / 'docs' / 'gate-counts.tsv'

HEADER = """# Machine-written by scripts/record-gate-counts.py --record. Do not hand-edit.
#
# A count here is an assertion that the module's JVM suite still runs that many tests. It is
# recorded only from a run with zero failures; see the script's docstring for why that matters.
#
# module\tcount\trecorded\tcommit   (a -dirty suffix means the tree had uncommitted changes,
#                                    so the counts are NOT reproducible from that commit alone)
"""


MODULES_FILE = ROOT / 'scripts' / 'gate-modules.tsv'


def gate_modules() -> list:
    """The build-directory names of the suites the gate runs, from the shared list."""
    out = []
    for line in MODULES_FILE.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        out.append(line.split('\t')[1].strip())
    return out


def suites() -> dict:
    """{module: (executed, failed)} for the gate's suites (scripts/gate-modules.tsv).

    Not whatever has results on disk. A listed module with no results is an error, not a zero.
    """
    found = {}
    missing = []
    for module in gate_modules():
        d = BUILD / module / 'test-results' / 'testDebugUnitTest'
        executed = failed = 0
        files = glob.glob(str(d / 'TEST-*.xml'))
        if not files:
            missing.append(module)
            continue
        for path in files:
            r = ET.parse(path).getroot()
            executed += int(r.get('tests', 0)) - int(r.get('skipped', 0))
            failed += int(r.get('failures', 0)) + int(r.get('errors', 0))
        found[module] = (executed, failed)
    if missing:
        raise NoResults(missing)
    return found


class NoResults(Exception):
    """A gate module produced no results. Not a zero -- an unrun suite."""

    def __init__(self, modules):
        super().__init__('no test results for: ' + ', '.join(modules)
                         + ' (listed in scripts/gate-modules.tsv but not run)')


def read_ledger() -> dict:
    if not LEDGER.is_file():
        return {}
    out = {}
    for line in LEDGER.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = line.split('\t')
        out[parts[0]] = int(parts[1])
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--record', action='store_true')
    g.add_argument('--check', action='store_true')
    g.add_argument('--print', dest='dump', action='store_true')
    args = ap.parse_args()

    if args.dump:
        for m, n in sorted(read_ledger().items()):
            print(f'{m} {n}')
        return 0

    try:
        found = suites()
    except NoResults as e:
        # Indeterminate: nothing was measured.
        print('INDETERMINATE: ' + str(e), file=sys.stderr)
        return 2

    if args.record:
        if not found:
            print('INDETERMINATE: no JVM results on disk; run the suites first', file=sys.stderr)
            return 2
        red = {m: f for m, (_, f) in found.items() if f}
        if red:
            print('REFUSED: will not record counts from a run with failures.', file=sys.stderr)
            for m, f in sorted(red.items()):
                print(f'  {m}: {f} failure(s)', file=sys.stderr)
            print('\nFix or explicitly baseline them first. Recording a red run would install '
                  'the breakage as the expected state.', file=sys.stderr)
            return 1
        # Date and commit come from the machine, not from me typing them.
        def sh(*c):
            try:
                return subprocess.run(c, cwd=ROOT, capture_output=True, text=True,
                                      check=True).stdout.strip()
            except Exception:
                return 'unknown'
        stamp, commit = sh('date', '-u', '+%Y-%m-%d'), sh('git', 'rev-parse', '--short', 'HEAD')
        # A dirty tree is not that commit; never stamp its counts with a clean SHA.
        if sh('git', 'status', '--porcelain'):
            commit += '-dirty'
        rows = ''.join(f'{m}\t{n}\t{stamp}\t{commit}\n'
                       for m, (n, _) in sorted(found.items()))
        LEDGER.parent.mkdir(parents=True, exist_ok=True)
        LEDGER.write_text(HEADER + rows)
        print(f'recorded {len(found)} suite(s), {sum(n for n, _ in found.values())} tests '
              f'-> {LEDGER.relative_to(ROOT)}')
        return 0

    # --check
    ledger = read_ledger()
    if not ledger:
        print('INDETERMINATE: no ledger; run --record after a green run', file=sys.stderr)
        return 2
    if not found:
        print('INDETERMINATE: no JVM results on disk', file=sys.stderr)
        return 2

    drift = []
    for m, expected in sorted(ledger.items()):
        if m not in found:
            drift.append(f'{m}: ledger expects {expected}, but the suite produced NO results')
        elif found[m][0] != expected:
            drift.append(f'{m}: ledger {expected}, ran {found[m][0]}')
    for m in sorted(set(found) - set(ledger)):
        drift.append(f'{m}: ran {found[m][0]} tests but is not in the ledger')

    if drift:
        print('FAIL: gate-count drift', file=sys.stderr)
        for d in drift:
            print(f'  {d}', file=sys.stderr)
        print('\nIf the change is intended, re-record with --record from a green run.',
              file=sys.stderr)
        return 1
    print(f'  ledger: {len(ledger)} suite(s), {sum(ledger.values())} tests, all agree')
    return 0


if __name__ == '__main__':
    sys.exit(main())
