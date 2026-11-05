#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Records JVM line coverage per module, and refuses to let 0% mean two different things.

A repo-wide percentage would average real measurements with blind spots, so none is computed.
Each module is recorded as MEASURED, BLIND or EMPTY, and every transition fails for a human:

  * a MEASURED module losing covered lines is a coverage regression;
  * a MEASURED module going to zero is almost certainly the instrument breaking, not the tests
    vanishing -- docs/gate-counts.tsv is what asserts the tests still run;
  * a BLIND module gaining coverage still fails, so the baseline is re-recorded deliberately.

Status is a recorded fact compared at check time, never re-derived each run; otherwise a
regression to zero would relabel itself as expected blindness.

Usage
-----
  check-coverage.py            compare against the baseline; rc=1 on a regression or a transition
  check-coverage.py --record   rewrite the baseline (refused if any suite has failures)
  check-coverage.py --print    emit `module status covered missed` for the shell
"""
import argparse
import datetime
import glob
import subprocess
import sys
# stdlib ElementTree, deliberately: this repo's own build output. See dump-failing-set.py.
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'aaos-apps-gradle-project' / 'build'
BASELINE = ROOT / 'docs' / 'coverage-baseline.tsv'
MODULES_FILE = ROOT / 'scripts' / 'gate-modules.tsv'

HEADER = """# Machine-written by scripts/check-coverage.py --record. Do not hand-edit.
#
# STATUS is the load-bearing column, not the counts.
#
#   MEASURED  JaCoCo attributes execution to this module's classes. `covered` may not drop.
#   BLIND     The suite runs under Robolectric, whose instrumenting classloader rewrites the
#             bytecode so JaCoCo cannot match its execution data to the analysed class files.
#             `covered` is 0 and that is a fact about the INSTRUMENT, not about the tests --
#             docs/gate-counts.tsv is what asserts the tests still run and pass.
#   EMPTY     Nothing to measure: 0 covered AND 0 missed, i.e. the module publishes no classes of
#             its own. Kept distinct from BLIND deliberately -- ':oem-apis' takes its contract from
#             the published plugin-apis artifact and has no main sources, which is not the same
#             statement as "instrumented away". Collapsing the two would let a module that lost
#             all of its classes pass as a known limitation.
#
# A module changing status in any direction is an error, so that "0% because blind" can never
# quietly become the alibi for "0% because the tests stopped running".
#
# module\tstatus\tcovered\tmissed\trecorded\tcommit
"""


def gate_modules() -> list:
    """The build-directory names of the suites the gate runs, from the shared list."""
    out = []
    for line in MODULES_FILE.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = line.split('\t')
        if len(parts) >= 2:
            out.append(parts[1].strip())
    return out


def classify(covered: int, missed: int) -> str:
    """MEASURED / BLIND / EMPTY — see the baseline header for what each asserts."""
    if covered > 0:
        return 'MEASURED'
    return 'EMPTY' if missed == 0 else 'BLIND'


def report_path(module: str) -> Path:
    return BUILD / module / 'reports' / 'coverage' / 'test' / 'debug' / 'report.xml'


def exec_files(module: str) -> list:
    """The JaCoCo execution data the report is supposed to be derived from."""
    return glob.glob(str(BUILD / module / 'outputs' / 'unit_test_code_coverage' / '**' / '*.exec'),
                     recursive=True)


class StaleReport(Exception):
    """report.xml is older than the .exec it claims to summarise."""


def measure(module: str):
    """(covered, missed) LINE counters for one module, or None if there is no report.

    Raises StaleReport when report.xml predates the execution data.
    """
    p = report_path(module)
    if not p.is_file():
        return None
    execs = exec_files(module)
    if execs:
        newest_exec = max(Path(e).stat().st_mtime for e in execs)
        if p.stat().st_mtime < newest_exec:
            raise StaleReport(module)
    root = ET.parse(p).getroot()
    for counter in root.findall('counter'):
        if counter.get('type') == 'LINE':
            return int(counter.get('covered')), int(counter.get('missed'))
    return 0, 0


def suite_is_red(module: str) -> bool:
    """True if the module's last JVM run had any failure; never record from a red run."""
    pattern = str(BUILD / module / 'test-results' / 'testDebugUnitTest' / '*.xml')
    for f in glob.glob(pattern):
        root = ET.parse(f).getroot()
        for tc in root.iter('testcase'):
            if tc.findall('failure') or tc.findall('error'):
                return True
    return False


def read_baseline() -> dict:
    if not BASELINE.is_file():
        return {}
    rows = {}
    for line in BASELINE.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = line.split('\t')
        if len(parts) >= 4:
            rows[parts[0]] = (parts[1], int(parts[2]), int(parts[3]))
    return rows


def commit_stamp() -> str:
    sha = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT,
                         capture_output=True, text=True).stdout.strip() or 'unknown'
    dirty = subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT,
                           capture_output=True, text=True).stdout.strip()
    return sha + ('-dirty' if dirty else '')


def collect():
    """(rows, missing) where rows is module -> (status, covered, missed)."""
    rows, missing, stale = {}, [], []
    for module in gate_modules():
        try:
            m = measure(module)
        except StaleReport:
            stale.append(module)
            continue
        if m is None:
            missing.append(module)
            continue
        covered, missed = m
        # Status is not inferred on --check (see the module docstring); --record freezes it.
        rows[module] = (covered, missed)
    return rows, missing, stale


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--record', action='store_true')
    ap.add_argument('--print', dest='do_print', action='store_true')
    ap.add_argument('--allow-loss', metavar='REASON',
                    help='accept a MEASURED module losing covered lines, for the stated reason. '
                         'The reason is written into the baseline, so an accepted loss is a '
                         'recorded decision rather than a silent one.')
    args = ap.parse_args()

    rows, missing, stale = collect()
    if stale:
        print('INDETERMINATE: report.xml is older than the .exec it summarises for '
              + ', '.join(stale), file=sys.stderr)
        print('  These numbers describe an earlier build. Regenerate before reading them:',
              file=sys.stderr)
        print('  ./gradlew ' + ' '.join(f':{m}:createDebugUnitTestCoverageReport' for m in stale)
              + ' --rerun-tasks', file=sys.stderr)
        return 2
    if missing:
        print('INDETERMINATE: no coverage report for ' + ', '.join(missing), file=sys.stderr)
        print('  Run: ./gradlew <module>:createDebugUnitTestCoverageReport', file=sys.stderr)
        return 2
    if not rows:
        # Never report success on an empty set.
        print('INDETERMINATE: no modules were measured, which is not a pass', file=sys.stderr)
        return 2

    if args.do_print:
        base = read_baseline()
        for m in sorted(rows):
            status = base.get(m, ('?',))[0]
            print(f'{m} {status} {rows[m][0]} {rows[m][1]}')
        return 0

    if args.record:
        red = [m for m in rows if suite_is_red(m)]
        if red:
            print('REFUSED: these suites have failures, so their coverage is not a baseline: '
                  + ', '.join(sorted(red)), file=sys.stderr)
            return 1

        # Diff against the outgoing baseline before overwriting it, or the following --check
        # passes by construction. A loss is refused, not warned about.
        prev = read_baseline()
        losses, deltas = [], []
        for m in sorted(rows):
            covered, missed = rows[m]
            now = classify(covered, missed)
            if m not in prev:
                deltas.append(f'  NEW      {m}: {now} {covered} covered, {missed} missed')
                continue
            was_status, was_cov, was_missed = prev[m]
            if (was_status, was_cov, was_missed) == (now, covered, missed):
                continue
            arrow = f'{was_cov} -> {covered}'
            if was_status != now:
                deltas.append(f'  STATUS   {m}: {was_status} -> {now} ({arrow} covered)')
            elif covered > was_cov:
                deltas.append(f'  GAINED   {m}: {arrow} (+{covered - was_cov})')
            elif covered < was_cov:
                deltas.append(f'  LOST     {m}: {arrow} (-{was_cov - covered})')
                if now == 'MEASURED' or was_status == 'MEASURED':
                    losses.append((m, was_cov, covered))
            else:
                # Same covered, more missed: new unreached lines. Dilution, not a loss.
                delta_missed = missed - was_missed
                if delta_missed:
                    deltas.append(f'  DILUTED  {m}: {covered} covered held, missed '
                                  f'{was_missed} -> {missed} (+{delta_missed})')

        if deltas:
            print('changes against the outgoing baseline:')
            for d in deltas:
                print(d)
        else:
            print('no change against the outgoing baseline.')
        # Flushed so the table is above the refusal that points at it.
        sys.stdout.flush()

        if losses and not args.allow_loss:
            print('', file=sys.stderr)
            print('REFUSED: recording would absorb a coverage loss:', file=sys.stderr)
            for m, was, now_c in losses:
                print(f'  {m}: {was} -> {now_c} covered line(s) (-{was - now_c})', file=sys.stderr)
            print('', file=sys.stderr)
            print('  A loss is not always a regression -- moving code between modules subtracts '
                  'from one\n  and adds to another -- but relocation and regression are '
                  'indistinguishable in a\n  single module\'s number, so it has to be said out '
                  'loud. Check the other modules\n  above for a matching gain, then re-run with:'
                  , file=sys.stderr)
            print('    check-coverage.py --record --allow-loss "why this loss is not a regression"',
                  file=sys.stderr)
            return 1

        stamp = commit_stamp()
        today = datetime.date.today().isoformat()
        lines = [HEADER]
        if args.allow_loss and losses:
            # The reason lives in the file, not just in a shell history nobody kept.
            detail = ', '.join(f'{m} {was}->{now_c}' for m, was, now_c in losses)
            lines.append(f'# {today} accepted loss ({detail}): {args.allow_loss}')
        for m in sorted(rows):
            covered, missed = rows[m]
            status = classify(covered, missed)
            lines.append(f'{m}\t{status}\t{covered}\t{missed}\t{today}\t{stamp}')
        BASELINE.write_text('\n'.join(lines) + '\n')
        print(f'recorded {len(rows)} module(s) to {BASELINE.relative_to(ROOT)}')
        return 0

    base = read_baseline()
    if not base:
        print(f'INDETERMINATE: no baseline at {BASELINE.relative_to(ROOT)}; run --record',
              file=sys.stderr)
        return 2

    problems = []
    for m in sorted(rows):
        covered, missed = rows[m]
        if m not in base:
            problems.append(f'{m}: not in the baseline — run --record')
            continue
        status, was_cov, _was_missed = base[m]
        now = classify(covered, missed)
        if now != status:
            if status == 'BLIND' and now == 'MEASURED':
                problems.append(
                    f'{m}: recorded BLIND but now reports {covered} covered line(s). That is an '
                    'improvement and still fails: re-record so the baseline stops understating it.')
            elif status == 'EMPTY' or now == 'EMPTY':
                problems.append(
                    f'{m}: recorded {status} and now classifies {now} ({covered} covered, '
                    f'{missed} missed). A module gaining or losing all of its classes is a build '
                    'change, not a coverage change — check what it publishes.')
            else:
                problems.append(
                    f'{m}: recorded MEASURED with {was_cov} covered line(s) and now reports 0. '
                    'Coverage does not vanish; the instrument or the suite did. Check '
                    'docs/gate-counts.tsv first — it asserts the tests still run.')
        elif status == 'MEASURED' and covered < was_cov:
            problems.append(f'{m}: covered lines fell {was_cov} -> {covered}')
    for m in base:
        if m not in rows:
            problems.append(f'{m}: in the baseline but not measured in this run')

    if problems:
        print('FAIL: coverage baseline disagrees with this run:', file=sys.stderr)
        for p in problems:
            print(f'  {p}', file=sys.stderr)
        return 1

    measured = sum(1 for m in rows if base[m][0] == 'MEASURED')
    blind = sum(1 for m in rows if base[m][0] == 'BLIND')
    empty = sum(1 for m in rows if base[m][0] == 'EMPTY')
    total_cov = sum(rows[m][0] for m in rows if base[m][0] == 'MEASURED')
    print(f'OK: {measured} module(s) measured ({total_cov} covered line(s), none lost), '
          f'{blind} BLIND, {empty} EMPTY. No repo-wide percentage is computed, deliberately.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
