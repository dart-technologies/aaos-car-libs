#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Checks the files the local instrumented gate depends on, and that CI agrees with it.

CI cannot run the instrumented suites, so they stay local, and what a local gate depends on rots
unnoticed. Four checks:

  1. run-bump-gate.sh and .github/workflows/ci.yml read the same JVM expectations.
  2. docs/baseline-failset.txt is sorted and unique (the gate diffs it with comm(1)).
  3. docs/intermittent-tests.txt entries carry evidence (an entry downgrades FAIL).
  4. No test appears in both files.

Exit codes: 0 pass, 1 a check failed, 2 an input was missing (which is not a pass).
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GATE = ROOT / 'scripts' / 'run-bump-gate.sh'
CI = ROOT / '.github' / 'workflows' / 'ci.yml'
BASELINE = ROOT / 'docs' / 'baseline-failset.txt'
INTERMITTENT = ROOT / 'docs' / 'intermittent-tests.txt'
EXCEPTIONS = ROOT / 'docs' / 'resolution-exceptions.tsv'
LEDGER_PATH = ROOT / 'docs' / 'gate-counts.tsv'

failures: list[str] = []


def require(path: Path) -> str:
    if not path.is_file():
        print(f'MISSING: {path.relative_to(ROOT)}', file=sys.stderr)
        sys.exit(2)
    return path.read_text()


def code_lines(text: str) -> str:
    """The text with comment lines removed; every content assertion runs against this.

    Prose about a property must not satisfy a check for the property. Both inputs use '#'.
    """
    return '\n'.join(ln for ln in text.splitlines() if not ln.lstrip().startswith('#'))


def check_counts_agree() -> None:
    gate = code_lines(require(GATE))
    ci = code_lines(require(CI))

    # Both read docs/gate-counts.tsv; verify neither carries its own numbers again.
    ledger = LEDGER_PATH
    if not ledger.is_file():
        failures.append('docs/gate-counts.tsv is missing — nothing supplies the expected counts')
        return
    entries = [ln for ln in ledger.read_text().splitlines()
               if ln.strip() and not ln.startswith('#')]
    if not entries:
        failures.append('docs/gate-counts.tsv has no entries')
        return

    for name, text in (('run-bump-gate.sh', gate), ('ci.yml', ci)):
        if 'record-gate-counts.py --print' not in text:
            failures.append(f'{name} does not read docs/gate-counts.tsv; it must not carry its own '
                            'copy of the expected counts')
        # A literal list would look like `"car-ui-lib 35"` or `check car-ui-lib 35`.
        stray = re.findall(r'^\s*check ([\w.-]+) (\d+)\s*$', text, re.M)
        if stray:
            failures.append(f'{name} hardcodes module counts again: '
                            + ', '.join(f'{m}={n}' for m, n in stray))

    # The overlayable check must be invoked, not merely present.
    for label, text in (('run-bump-gate.sh', gate), ('ci.yml', ci)):
        if 'check-overlayable-packaged.py' not in text:
            failures.append(f'{label} does not invoke check-overlayable-packaged.py — declared '
                            'OEM overlayable surfaces would go unverified')

    # The failure ceiling is checked per invocation line, presence first: "every invocation has
    # the flag" is vacuously true when there are none.
    ci_calls = [ln for ln in ci.splitlines() if 'check-instrumented-run.py' in ln]
    if not ci_calls:
        failures.append('ci.yml no longer invokes check-instrumented-run.py at all — the '
                        'executed-count gate has been removed, not merely weakened')
    for line in ci_calls:
        if '--max-failures' not in line:
            failures.append('ci.yml invokes check-instrumented-run.py without --max-failures: '
                            + line.strip())

    # Not every gate invocation: car-ui-lib diffs its failing set instead. The JVM loop must carry
    # the ceiling, identified by the variable it reads, since the gate has two such loops.
    for label, marker in (('JVM per-module', '$d --expect $n'),
                          ('clean instrumented', '$T --expect $n')):
        calls = [ln for ln in gate.splitlines()
                 if 'check-instrumented-run.py' in ln and marker in ln]
        if not calls:
            failures.append(f'run-bump-gate.sh: the {label} check is gone (looked for {marker!r})')
        for line in calls:
            if '--max-failures 0' not in line:
                failures.append(f'run-bump-gate.sh {label} check has no --max-failures 0: '
                                + line.strip())

    if not failures:
        print(f'OK: {len(entries)} ledger count(s); gate and CI both read them, neither hardcodes.')


def split_entry(line: str) -> tuple[str, str]:
    """Splits `Class#method<TAB># evidence` into (id, evidence). Never on '#': ids contain one."""
    body, _, comment = line.partition('\t')
    if not comment:
        # Tolerate spaces instead of a tab, but only where the comment marker is unambiguous.
        body, sep, comment = line.partition(' #')
        comment = ('#' + comment) if sep else ''
    return body.strip(), comment.strip().lstrip('#').strip()


def check_baseline() -> set[str]:
    text = require(BASELINE)
    # Stripped exactly as the gate strips them (`grep -v '^\s*#'` then `awk NF`).
    ids = [ln.strip() for ln in text.splitlines()
           if ln.strip() and not ln.lstrip().startswith('#')]
    # Sortedness is of the ids, which is what comm(1) gets (the gate cuts field 1 first).
    ids = [split_entry(ln)[0] for ln in ids]
    for tid in ids:
        if '#' not in tid:
            failures.append(f'baseline entry is not Class#method: {tid!r}')
    if ids != sorted(ids):
        failures.append('baseline-failset.txt is not sorted — the gate diffs it with comm(1), '
                        'which silently misreads unsorted input')
    if len(ids) != len(set(ids)):
        failures.append('baseline-failset.txt has duplicate entries')
    if not failures:
        print(f'OK: baseline-failset.txt — {len(ids)} entries, sorted and unique.')
    return set(ids)


def check_intermittent() -> set[str]:
    text = require(INTERMITTENT)
    ids = set()
    problems_before = len(failures)
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        tid, evidence = split_entry(line)
        ids.add(tid)
        if '#' not in tid:
            failures.append(
                f'intermittent-tests.txt:{lineno}: {tid!r} is not Class#method')
        if not evidence:
            failures.append(
                f'intermittent-tests.txt:{lineno}: {tid} has no evidence comment. An entry here '
                'downgrades a gate FAILURE to INDETERMINATE; without recorded evidence that is a '
                'silenced test.')
    if len(failures) == problems_before:
        print(f'OK: intermittent-tests.txt — {len(ids)} entr(ies), each with evidence.')
    return ids


def check_resolution_exceptions() -> None:
    """docs/resolution-exceptions.tsv is well-formed, unique, and every row carries a reason.

    A row waives step 0 of the gate, so a reason is mandatory. The gate enforces staleness.
    """
    if not EXCEPTIONS.is_file():
        print('OK: resolution-exceptions.tsv absent — step 0 then requires exact uniformity.')
        return
    problems_before = len(failures)
    seen = set()
    rows = 0
    for lineno, line in enumerate(EXCEPTIONS.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 5:
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: needs 5 tab-separated fields '
                '(coordinate, module, configuration, version, reason)')
            continue
        rows += 1
        coord, module, config, version, reason = (p.strip() for p in parts[:5])
        if ':' not in coord or '/' in coord:
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: {coord!r} is not a group:name coordinate')
        if not module.startswith(':'):
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: {module!r} is not a gradle module path')
        if not reason:
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: {coord} {module} has no reason. This row '
                'waives the only check that tells a safe bump from one that changed nothing.')
        elif len(reason) < 40:
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: {coord} {module} has a reason too short to '
                'say WHY the difference is harmless; restating the versions is not a reason.')
        key = (coord, module, config)
        if key in seen:
            failures.append(
                f'resolution-exceptions.tsv:{lineno}: duplicate entry for {coord} {module} {config}')
        seen.add(key)
    if len(failures) == problems_before:
        print(f'OK: resolution-exceptions.tsv — {rows} row(s), each with a reason.')


def check_mutation_registry() -> None:
    """Runs check-gate-sensitivity.py's registry and anchor validation (about a second), so a
    drifted anchor fails on push rather than in the weekly sweep."""
    script = ROOT / 'scripts' / 'check-gate-sensitivity.py'
    if not script.is_file():
        failures.append('scripts/check-gate-sensitivity.py is missing — mutation coverage is '
                        'unverifiable, which is not the same as absent')
        return
    proc = subprocess.run([sys.executable, str(script), '--check-registry'],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        failures.append('mutation registry/anchors are not valid:\n      '
                        + '\n      '.join(
                            (proc.stderr or proc.stdout).strip().splitlines()[:6]))
        return
    print('OK: ' + (proc.stdout.strip().splitlines() or ['mutation registry valid'])[0].strip())


def main() -> int:
    check_counts_agree()
    baseline = check_baseline()
    intermittent = check_intermittent()
    check_resolution_exceptions()
    check_mutation_registry()

    both = baseline & intermittent
    if both:
        failures.append(
            'test(s) in BOTH baseline-failset.txt and intermittent-tests.txt: '
            + ', '.join(sorted(both))
            + ' — a test cannot be both a known-stable failure and a known flake; a real '
              'regression could hide behind either reading')

    if failures:
        print(f'\nFAIL: {len(failures)} gate-metadata problem(s):', file=sys.stderr)
        for f in failures:
            print(f'  - {f}', file=sys.stderr)
        return 1
    print('\nOK: gate metadata is consistent.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
