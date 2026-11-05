#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Fails when production code cites a test class or @Test method that does not exist.

A javadoc saying "a test pins this" is what readers trust instead of re-deriving the argument, and
a comment is never compiled. Checked: every identifier ending in Test or Tests in a comment in a
main source set must name a test class, and Class#method a @Test method. Not checked: whether the
test asserts what the prose claims, or citations inside test sources.

Usage
-----
  check-cited-tests.py           rc=0 if every cited test exists; rc=1 if any does not
  check-cited-tests.py --list    print every citation found, with its resolution
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Loose about form: {@link Foo}, {@code Foo}, Foo#bar and a bare Foo are the same claim.
CITATION = re.compile(r'\b([A-Z][A-Za-z0-9_]*Tests?)\b')

# Class#method: a wrong method in the right class is the more misleading citation.
CITED_METHOD = re.compile(r'\b([A-Z][A-Za-z0-9_]*Tests?)#([a-z][A-Za-z0-9_]*)')

# A @Test-annotated method name, for the file the citation points at.
TEST_METHOD = re.compile(r'@Test\b[^{]*?\b(?:public|protected|private)?\s*void\s+([A-Za-z0-9_]+)\s*\(',
                         re.S)

# Words ending in "Test" that name a concept. Keep it short: a long ignore list stops checking.
NOT_A_CLASS = {'Test', 'Tests', 'UnitTest', 'UnitTests', 'RoboTest', 'InstrumentedTest'}


def is_main_source(path: Path) -> bool:
    parts = {p.lower() for p in path.parts}
    if 'build' in parts or 'generated' in parts:
        return False
    # AOSP modules use src/ and tests/; Gradle modules use src/main and src/test.
    if 'test' in parts or 'tests' in parts or 'androidtest' in parts:
        return False
    return True


def comment_spans(text: str):
    """Yield (line_no, comment_text) for // and /* */ comments, with wrapped lines joined.

    Javadoc wraps long identifiers with a hyphen, so consecutive comment lines are joined and `-\n`
    is closed up. The line number is the head of the block.
    """
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            start = i + 1
            block = []
            while i < len(lines):
                cur = lines[i].strip()
                if not (cur.startswith('//') or cur.startswith('*') or cur.startswith('/*')):
                    break
                block.append(re.sub(r'^(?://+|/?\*+/?)\s?', '', cur))
                i += 1
            joined = '\n'.join(block)
            # A hyphen at end-of-line inside a comment is a wrap, not a character in a name.
            joined = re.sub(r'-\n\s*', '', joined)
            yield start, joined.replace('\n', ' ')
        else:
            i += 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true', help='print every citation and its resolution')
    args = ap.parse_args()

    # Every test class in the repo by simple name, with its @Test methods.
    existing = set()
    methods = {}
    for p in ROOT.rglob('*.java'):
        if {'build', '.claude'} & set(p.relative_to(ROOT).parts):
            continue
        if not is_main_source(p):
            existing.add(p.stem)
            try:
                methods.setdefault(p.stem, set()).update(
                    TEST_METHOD.findall(p.read_text(encoding='utf-8', errors='replace')))
            except OSError:
                pass

    citations = []
    method_problems = []
    for p in ROOT.rglob('*.java'):
        if not is_main_source(p) or {'build', '.claude'} & set(p.relative_to(ROOT).parts):
            continue
        try:
            text = p.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        for line_no, line in comment_spans(text):
            for name in CITATION.findall(line):
                if name in NOT_A_CLASS:
                    continue
                citations.append((p.relative_to(ROOT), line_no, name, name in existing))
            for cls, method in CITED_METHOD.findall(line):
                if cls in NOT_A_CLASS or cls not in existing:
                    continue  # the class miss is already reported above
                known = methods.get(cls, set())
                if not known:
                    # No @Test parsed: unresolved, not agreement.
                    method_problems.append((p.relative_to(ROOT), line_no, cls, method,
                                            'no @Test methods parsed from that class'))
                elif method not in known:
                    method_problems.append((p.relative_to(ROOT), line_no, cls, method,
                                            'no such @Test method'))

    if not citations:
        # Never pass on an empty set.
        print('INDETERMINATE: found no test citations in any main source set — the parser is '
              'broken, which is not a pass', file=sys.stderr)
        return 2

    if args.list:
        for rel, line_no, name, ok in sorted(citations):
            print(f'  {"OK " if ok else "MISSING"}  {rel}:{line_no}  {name}')

    if method_problems:
        print(f'FAIL: {len(method_problems)} citation(s) name a test METHOD that does not exist:',
              file=sys.stderr)
        for rel, line_no, cls, method, why in sorted(method_problems):
            print(f'  {rel}:{line_no}  cites {cls}#{method} — {why}', file=sys.stderr)
        print('', file=sys.stderr)
        print('  The class exists, so the citation looks resolvable and is not. A reader who opens\n'
              '  the right file and cannot find the method assumes they misread the reference.',
              file=sys.stderr)
        return 1

    broken = [c for c in citations if not c[3]]
    if broken:
        print(f'FAIL: {len(broken)} citation(s) name a test class that does not exist:',
              file=sys.stderr)
        for rel, line_no, name, _ in sorted(broken):
            print(f'  {rel}:{line_no}  cites {name}', file=sys.stderr)
        print('', file=sys.stderr)
        print('  A javadoc saying a test pins a rule is what a reader checks INSTEAD of '
              're-deriving\n  the rule. If the file is not there, the sentence is a green light '
              'attached to\n  nothing. Write the test, or delete the claim.', file=sys.stderr)
        return 1

    named = len({c[2] for c in citations})
    n_methods = sum(1 for p in ROOT.rglob('*.java')
                    if is_main_source(p)
                    and not {'build', '.claude'} & set(p.relative_to(ROOT).parts)
                    for _ in CITED_METHOD.findall(p.read_text(encoding='utf-8', errors='replace')))
    print(f'OK: {len(citations)} test citation(s) across {named} distinct class(es), all resolve '
          f'to a file that exists; {n_methods} name a #method and all of those exist too.')
    print('    (Existence only — whether each test asserts what the prose claims needs a reader.)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
