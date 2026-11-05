#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Lists methods in this fork's own source that no test mentions by name.

Line coverage counts what a test happened to execute; this lists API surface no test names. A
method listed here is not directly exercised. One not listed may still be untested: absence is
not coverage. Use it to find candidates for a test or a mutation in check-gate-sensitivity.py.

Usage: check-test-reachability.py [--all]
Exit codes: 0 always — this reports, it does not gate.
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# This fork's own code; inherited AOSP gaps are documented in docs/VERIFICATION.md.
MODULES = [
    ('a2ui-model', 'src/main/java', 'src/test/java'),
    ('a2ui-host-carapp', 'src/main/java', 'src/test/java'),
    ('a2ui-host-carui', 'src/main/java', 'src/test/java'),
]

# The one AOSP file this fork rewrote end to end, so its surface is ours to answer for.
EXTRA_MAIN = [
    ('car-ui-lib/car-ui-lib/src/main/java/com/android/car/ui/utils/CarUxRestrictionsUtil.java',
     ['car-ui-lib/car-ui-lib/src/test/java', 'car-ui-lib/car-ui-lib/src/androidTest/java']),
]

DECL = re.compile(
    r'^\s*(?:public|protected|private|static|final|abstract|synchronized|@\w+\s+)*'
    r'(?:public|protected|package)?\s*'
    r'(?:static\s+)?(?:final\s+)?'
    r'[\w<>\[\],.?\s]+\s+(\w+)\s*\([^;]*\)\s*(?:throws [\w.,\s]+)?\{')

# Framework overrides and boilerplate: their absence from tests says nothing.
IGNORED = {
    'toString', 'equals', 'hashCode', 'onCreate', 'onDestroy', 'onStart', 'onStop', 'onResume',
    'onPause', 'main', 'run', 'onClick', 'onAction', 'get', 'set', 'build', 'onBind', 'onUnbind',
    'onGetTemplate', 'onCreateSession', 'onReceive', 'onRestrictionsChanged', 'onCarUiInsetsChanged',
    'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized', 'try',
}


def methods_in(path: Path) -> list:
    out = []
    for i, line in enumerate(path.read_text().splitlines(), 1):
        if line.lstrip().startswith(('//', '*', '/*')):
            continue
        m = DECL.match(line)
        if m and m.group(1) not in IGNORED:
            out.append((m.group(1), i))
    return out


def test_corpus(dirs) -> str:
    body = []
    for d in dirs:
        p = ROOT / d
        if p.is_dir():
            for f in p.rglob('*.java'):
                body.append(f.read_text())
    return '\n'.join(body)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='also print methods that ARE named')
    args = ap.parse_args()

    targets = []
    for module, main_rel, test_rel in MODULES:
        main_dir = ROOT / module / main_rel
        if main_dir.is_dir():
            targets.append((module, sorted(main_dir.rglob('*.java')), [f'{module}/{test_rel}']))
    for main_file, test_dirs in EXTRA_MAIN:
        f = ROOT / main_file
        if f.is_file():
            targets.append((main_file.split('/')[-1], [f], test_dirs))

    total = unnamed = 0
    for label, files, test_dirs in targets:
        corpus = test_corpus(test_dirs)
        rows = []
        for f in files:
            for name, line in methods_in(f):
                total += 1
                if not re.search(r'\b' + re.escape(name) + r'\s*\(', corpus):
                    rows.append((f.relative_to(ROOT), line, name))
                    unnamed += 1
        print(f'\n=== {label}: {len(rows)} method(s) no test names')
        for rel, line, name in rows:
            print(f'  {rel}:{line}  {name}()')

    print(f'\n{unnamed} of {total} methods are never named by a test.')
    print('Not a score. Read the list and decide: a test, a mutation, or nothing because the '
          'method is trivial and its failure mode is already covered elsewhere.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
