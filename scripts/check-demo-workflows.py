#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Asserts scripts/record-demo.sh records every UseCaseCorpus case, and no case it invented.

Ids are checked in both directions. Masks are not checked against Java, since they come from
prose, but every case must have a mask and a description.

Usage
-----
  check-demo-workflows.py        rc=0 if the two lists agree; rc=1 if they do not
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / 'a2ui-model' / 'src' / 'main' / 'java' / 'art' / 'dart' / 'a2ui' / 'demo' \
    / 'UseCaseCorpus.java'
SCRIPT = ROOT / 'scripts' / 'record-demo.sh'


def corpus_ids() -> list:
    """Every id passed to a UseCase Builder, in declaration order."""
    text = CORPUS.read_text()
    return re.findall(r'new Builder\(\s*"([a-z_]+)"', text)


def script_block(name: str) -> dict:
    """One `typeset -A`-style associative block from the shell script, as id -> value."""
    text = SCRIPT.read_text()
    m = re.search(rf'^{name}=\(\n(.*?)^\)', text, re.M | re.S)
    if not m:
        return {}
    out = {}
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            out[parts[0]] = parts[1].split('#')[0].strip().strip('"')
    return out


def main() -> int:
    ids = corpus_ids()
    if not ids:
        # Never pass on an empty set.
        print(f'INDETERMINATE: found no UseCase ids in {CORPUS.relative_to(ROOT)} — the parser is '
              'broken, which is not a pass', file=sys.stderr)
        return 2
    masks = script_block('CASE_MASK')
    whats = script_block('CASE_WHAT')
    if not masks:
        print(f'INDETERMINATE: found no CASE_MASK table in {SCRIPT.relative_to(ROOT)}',
              file=sys.stderr)
        return 2

    corpus, script = set(ids), set(masks)
    problems = []
    for missing in sorted(corpus - script):
        problems.append(f'{missing}: in the corpus, not recorded by record-demo.sh')
    for extra in sorted(script - corpus):
        problems.append(f'{extra}: recorded by record-demo.sh, not in the corpus')
    for case in sorted(script & corpus):
        if not masks.get(case):
            problems.append(f'{case}: has no restriction mask')
        if not whats.get(case):
            problems.append(f'{case}: has no description of what the recording shows')

    if problems:
        print('FAIL: record-demo.sh and UseCaseCorpus disagree:', file=sys.stderr)
        for p in problems:
            print(f'  {p}', file=sys.stderr)
        return 1

    print(f'OK: record-demo.sh records all {len(ids)} corpus case(s), each with a mask and a '
          'description.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
