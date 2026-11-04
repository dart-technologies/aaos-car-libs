#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Fails if any GitHub Action is referenced by a mutable tag instead of a commit SHA.

Whoever controls a tag controls what runs with this repository's token, and pinning only helps if
it is enforced. Local actions and docker refs are exempt.

Exit codes: 0 all pinned, 1 something is not, 2 nothing was checked (which is not a pass).
"""

import re
import sys
from pathlib import Path

USES = re.compile(r'^\s*(?:-\s*)?uses:\s*([^\s#]+)')
SHA = re.compile(r'^[0-9a-f]{40}$')


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    workflows = sorted((root / '.github' / 'workflows').glob('*.y*ml'))
    if not workflows:
        print('FAIL: no workflow files found — nothing was checked, which is not a pass',
              file=sys.stderr)
        return 2

    problems = []
    checked = 0
    for wf in workflows:
        for lineno, line in enumerate(wf.read_text().splitlines(), 1):
            m = USES.match(line)
            if not m:
                continue
            ref = m.group(1)
            if ref.startswith('./') or ref.startswith('docker://'):
                continue
            checked += 1
            if '@' not in ref:
                problems.append((wf.name, lineno, ref, 'no ref at all'))
                continue
            _, _, version = ref.partition('@')
            if not SHA.match(version):
                problems.append((wf.name, lineno, ref, f'mutable ref {version!r}'))

    if checked == 0:
        print('FAIL: no `uses:` lines found — nothing was checked, which is not a pass',
              file=sys.stderr)
        return 2

    if problems:
        print(f'FAIL: {len(problems)} unpinned action reference(s):', file=sys.stderr)
        for name, lineno, ref, why in problems:
            print(f'  {name}:{lineno}  {ref}  ({why})', file=sys.stderr)
        print('\nResolve a tag to its commit SHA with:', file=sys.stderr)
        print('  gh api "repos/<owner>/<repo>/commits/<tag>" -q .sha', file=sys.stderr)
        print('and keep the tag as a trailing comment: uses: owner/repo@<sha> # v4.2.2',
              file=sys.stderr)
        return 1

    print(f'OK: all {checked} action reference(s) across {len(workflows)} workflow(s) '
          'are pinned to a commit SHA.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
