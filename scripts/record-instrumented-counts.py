#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""The instrumented suites' expected counts, recorded rather than typed into the gate.

Same rule as record-gate-counts.py for the JVM suites: a hand-edited count is indistinguishable
in a diff from a legitimate one. The ledger also records each suite's failure ceiling (car-ui-lib
has a baselined 3; the rest must be clean), and recording is refused from a run over its ceiling.

Usage
-----
  record-instrumented-counts.py --record   rewrite the ledger from the last run
  record-instrumented-counts.py --check    compare the last run against it
  record-instrumented-counts.py --print    emit `module expected max_failures` for the shell
"""
import argparse
import datetime
import glob
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'aaos-apps-gradle-project' / 'build'
LEDGER = ROOT / 'docs' / 'instrumented-counts.tsv'

HEADER = """# Machine-written by scripts/record-instrumented-counts.py --record. Do not hand-edit.
#
# The instrumented (androidTest) suites the gate runs, and what each one must produce. The JVM
# equivalent is docs/gate-counts.tsv; this file exists because the instrumented expectations were
# shell literals inside run-bump-gate.sh, which is the arrangement that file's own comments reject.
#
# max_failures is the CEILING, not the observation. car-ui-lib's 3 are a recorded baseline whose
# membership step 3 diffs by name -- the count alone would let a new failure replace an old one.
#
# module\texpected\tmax_failures\trecorded\tcommit"""

# Listed, not globbed: a missing results directory must be reported, not skipped.
SUITES = ['car-ui-lib', 'car-telephony-common', 'car-rotary-lib', 'car-media-common']


def results_dir(module: str) -> Path:
    return BUILD / module / 'outputs' / 'androidTest-results' / 'connected' / 'debug'


def measure(module: str):
    """(executed, failures) for one module's last connected run, or None if it did not run.

    Skipped tests are excluded, as in check-instrumented-run.py.
    """
    d = results_dir(module)
    if not d.is_dir():
        return None
    executed = failures = 0
    files = sorted(glob.glob(str(d / '**' / '*.xml'), recursive=True))
    if not files:
        return None
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for case in root.iter('testcase'):
            tags = {c.tag for c in case}
            if 'skipped' in tags:
                continue
            executed += 1
            if 'failure' in tags or 'error' in tags:
                failures += 1
    return executed, failures


def read_ledger() -> dict:
    rows = {}
    if not LEDGER.is_file():
        return rows
    for line in LEDGER.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        p = line.split('\t')
        if len(p) >= 3:
            rows[p[0]] = (int(p[1]), int(p[2]))
    return rows


def commit_stamp() -> str:
    sha = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT,
                         capture_output=True, text=True).stdout.strip() or 'unknown'
    dirty = subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT,
                           capture_output=True, text=True).stdout.strip()
    return sha + ('-dirty' if dirty else '')


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--record', action='store_true')
    g.add_argument('--check', action='store_true')
    g.add_argument('--print', dest='do_print', action='store_true')
    ap.add_argument('--ceiling', action='append', default=[], metavar='MODULE=N',
                    help='max_failures to record for a module (default 0). Repeatable.')
    args = ap.parse_args()

    if args.do_print:
        rows = read_ledger()
        if not rows:
            print('INDETERMINATE: no ledger at %s' % LEDGER.relative_to(ROOT), file=sys.stderr)
            return 2
        for m in sorted(rows):
            print(f'{m} {rows[m][0]} {rows[m][1]}')
        return 0

    if args.record:
        ceilings = {}
        for spec in args.ceiling:
            k, _, v = spec.partition('=')
            ceilings[k] = int(v)
        measured, missing = {}, []
        for m in SUITES:
            got = measure(m)
            if got is None:
                missing.append(m)
            else:
                measured[m] = got
        if missing:
            print('INDETERMINATE: no connected results for ' + ', '.join(missing), file=sys.stderr)
            print('  Every suite must have run; recording a partial set would shrink the gate.',
                  file=sys.stderr)
            return 2
        over = [(m, f, ceilings.get(m, 0)) for m, (_e, f) in measured.items()
                if f > ceilings.get(m, 0)]
        if over:
            print('REFUSED: these suites exceeded the ceiling being recorded for them:',
                  file=sys.stderr)
            for m, f, c in over:
                print(f'  {m}: {f} failure(s) against a ceiling of {c}', file=sys.stderr)
            print('  Pass --ceiling MODULE=N deliberately if the failures are a known baseline.',
                  file=sys.stderr)
            return 1
        stamp, today = commit_stamp(), datetime.date.today().isoformat()
        lines = [HEADER]
        for m in sorted(measured):
            e, _f = measured[m]
            lines.append(f'{m}\t{e}\t{ceilings.get(m, 0)}\t{today}\t{stamp}')
        LEDGER.write_text('\n'.join(lines) + '\n')
        print(f'recorded {len(measured)} instrumented suite(s) -> {LEDGER.relative_to(ROOT)}')
        return 0

    # --check
    rows = read_ledger()
    if not rows:
        print('INDETERMINATE: no ledger at %s; run --record' % LEDGER.relative_to(ROOT),
              file=sys.stderr)
        return 2
    problems = []
    for m in sorted(rows):
        expected, ceiling = rows[m]
        got = measure(m)
        if got is None:
            problems.append(f'{m}: in the ledger but produced no results this run')
            continue
        executed, failures = got
        if executed < expected:
            problems.append(f'{m}: {executed} executed, ledger says {expected} — truncated')
        if failures > ceiling:
            problems.append(f'{m}: {failures} failure(s) over a ceiling of {ceiling}')
    if problems:
        print('FAIL: instrumented ledger disagrees with this run:', file=sys.stderr)
        for p in problems:
            print('  ' + p, file=sys.stderr)
        return 1
    total = sum(v[0] for v in rows.values())
    print(f'  ledger: {len(rows)} instrumented suite(s), {total} tests, all agree')
    return 0


if __name__ == '__main__':
    sys.exit(main())
