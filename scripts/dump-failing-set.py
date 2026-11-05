#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Emits the failing set of an instrumented run as `Class#method<TAB>signature`.

The signature lets the gate see a baselined test fail in a new way. It is the exception type only:
messages and stack frames vary between runs, and a different failure mode is nearly always a
different exception.

Usage: dump-failing-set.py <results-dir>
"""

import glob
import re
import sys
# stdlib ElementTree, not defusedxml: the input is this repo's own build output. Switch if that
# ever changes.
import xml.etree.ElementTree as ET

# `androidx.test.espresso.NoMatchingViewException: No views in hierarchy...` -> the FQ type.
TYPE_AT_START = re.compile(r'^([A-Za-z_][\w.$]*(?:Exception|Error|Throwable|Failure))\b')


def signature(node) -> str:
    """The exception type for one <failure>/<error>, or a stable placeholder."""
    declared = node.get('type')
    if declared:
        return declared.strip()
    body = (node.text or '').strip()
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        m = TYPE_AT_START.match(line)
        return m.group(1) if m else 'UNKNOWN'
    return 'NO_DETAIL'


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    files = glob.glob(sys.argv[1] + '/*.xml')
    if not files:
        print(f'no result XML under {sys.argv[1]}', file=sys.stderr)
        return 2

    rows = []
    for path in files:
        for tc in ET.parse(path).getroot().iter('testcase'):
            nodes = list(tc.iter('failure')) + list(tc.iter('error'))
            if not nodes:
                continue
            name = tc.get('classname', '').split('.')[-1] + '#' + tc.get('name', '')
            rows.append(f'{name}\t{signature(nodes[0])}')
    print('\n'.join(sorted(set(rows))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
