#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Reports where this fork's Android.bp files and its Gradle build have drifted apart.

Gradle runs every gate here; Soong builds what a head unit installs, and nothing else reads an
Android.bp. Report only: this never edits a .bp (see FORK.md). Three checks, each decidable
without running Soong:

  1. Every `srcs` and `resource_dirs` entry in an Android.bp resolves to something on disk.
  2. `res-overlayable` directories that one build ships and the other does not.
  3. The vendored plugin-API AAR is byte-for-byte the reviewed 1.5.0 artifact.

Not checked: dependency-list equivalence. The two name libraries differently, and a hand-kept
mapping would be a third copy of the truth.

Usage
-----
  check-soong-parity.py           rc=0 if no unbaselined drift; rc=1 if there is
  check-soong-parity.py --list    print every finding, baselined or not
"""
import argparse
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / 'docs' / 'soong-parity-baseline.txt'
OEM_API_AAR = (ROOT / 'car-ui-lib' / 'oem-apis' / 'prebuilts'
               / 'car-ui-lib-plugin-apis-1.5.0.aar')
OEM_API_SHA256 = '7a57d30959cc28fc82a87364fe7a81071f30a92d20ec5f271853bb3a8ff1a562'

# One `name: value` or `name: [a, b]` property inside a Blueprint module block.
LIST_PROP = re.compile(r'\b(srcs|resource_dirs|java_resource_dirs)\s*:\s*\[(.*?)\]', re.S)
NAME_PROP = re.compile(r'\bname\s*:\s*"([^"]+)"')
# Module blocks: `android_app { ... }`, `java_library { ... }`, etc.
MODULE = re.compile(r'^([a-z_]+)\s*\{', re.M)


def modules_in(bp: Path):
    """Yield (kind, name, {prop: [values]}) for each top-level block in an Android.bp."""
    text = bp.read_text()
    for m in MODULE.finditer(text):
        start = m.end() - 1
        depth, i = 0, start
        while i < len(text):
            if text[i] == '{':
                depth += 1
            elif text[i] == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = text[start:i]
        name = NAME_PROP.search(body)
        props = {}
        for prop, raw in LIST_PROP.findall(body):
            props.setdefault(prop, []).extend(re.findall(r'"([^"]+)"', raw))
        yield m.group(1), (name.group(1) if name else '<unnamed>'), props


def resolves(bp_dir: Path, entry: str) -> bool:
    """Does a srcs/resource_dirs entry match anything on disk?"""
    if entry.startswith(':') or entry.startswith('-'):
        return True   # a module reference or an exclusion, not a path
    if any(ch in entry for ch in '*?'):
        return any(bp_dir.glob(entry))
    return (bp_dir / entry).exists()


def read_baseline() -> dict:
    rows = {}
    if not BASELINE.is_file():
        return rows
    for line in BASELINE.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = line.split('\t')
        if len(parts) >= 2 and parts[1].strip():
            rows[parts[0].strip()] = parts[1].strip()
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true')
    args = ap.parse_args()

    # Relative parts: a checkout under .claude/worktrees/ must not skip itself.
    bps = sorted(p for p in ROOT.rglob('Android.bp')
                 if not {'build', '.claude'} & set(p.relative_to(ROOT).parts))
    if not bps:
        # Never pass on an empty set.
        print('INDETERMINATE: found no Android.bp files at all — the walk is broken, which is not '
              'a pass', file=sys.stderr)
        return 2

    if not OEM_API_AAR.is_file():
        print(f'FAIL: required Soong AAR is missing: {OEM_API_AAR.relative_to(ROOT)}',
              file=sys.stderr)
        return 1
    actual_aar_sha = hashlib.sha256(OEM_API_AAR.read_bytes()).hexdigest()
    if actual_aar_sha != OEM_API_SHA256:
        print('FAIL: car-ui-lib-plugin-apis-1.5.0.aar checksum mismatch:', file=sys.stderr)
        print(f'  expected {OEM_API_SHA256}', file=sys.stderr)
        print(f'  actual   {actual_aar_sha}', file=sys.stderr)
        return 1

    findings = []
    for bp in bps:
        rel = bp.relative_to(ROOT)
        for kind, name, props in modules_in(bp):
            for prop, entries in props.items():
                for entry in entries:
                    if not resolves(bp.parent, entry):
                        findings.append((
                            f'{rel}::{name}::{prop}::{entry}',
                            f'{rel}: module {name!r} declares {prop} {entry!r}, which matches '
                            'nothing on disk'))

    # Check 2: the overlayable seam, in both directions.
    for module_dir, bp_rel in [
            ('car-ui-lib/car-ui-lib', 'car-ui-lib/Android.bp'),
            ('car-ui-lib/referencedesign/plugin', 'car-ui-lib/referencedesign/plugin/Android.bp'),
            ('car-ui-lib/paintbooth', 'car-ui-lib/Android.bp')]:
        overlayable = ROOT / module_dir / 'src' / 'main' / 'res-overlayable'
        if not overlayable.is_dir():
            continue
        bp = ROOT / bp_rel
        if not bp.is_file():
            continue
        in_soong = 'res-overlayable' in bp.read_text()
        gradle = ROOT / module_dir / 'build.gradle'
        # Comments stripped: car-ui-lib's build.gradle mentions res-overlayable in a comment.
        in_gradle = False
        if gradle.is_file():
            src = re.sub(r'/\*.*?\*/', '', gradle.read_text(), flags=re.S)
            src = re.sub(r'//[^\n]*', '', src)
            in_gradle = 'res-overlayable' in src
        if in_soong != in_gradle:
            shipped, missing = ('Soong', 'Gradle') if in_soong else ('Gradle', 'Soong')
            findings.append((
                f'overlayable::{module_dir}',
                f'{module_dir}/src/main/res-overlayable is shipped by {shipped} and not by '
                f'{missing} — the OEM surface differs between the two builds'))

    baseline = read_baseline()
    if args.list:
        for key, msg in sorted(findings):
            mark = 'BASELINED' if key in baseline else 'DRIFT    '
            print(f'  {mark}  {msg}')
        if not findings:
            print('  (no findings)')

    unbaselined = [(k, m) for k, m in findings if k not in baseline]
    stale = sorted(k for k in baseline if k not in {f[0] for f in findings})

    if stale:
        print(f'FAIL: {len(stale)} baseline entr(ies) no longer describe any drift:',
              file=sys.stderr)
        for k in stale:
            print(f'  {k}', file=sys.stderr)
        print('  An allowlist entry outliving the condition it waives is a silent hole — the same '
              'rule\n  this repository applies in resolution-exceptions.tsv and '
              'build-coverage-baseline.txt.', file=sys.stderr)
        return 1

    if unbaselined:
        print(f'FAIL: {len(unbaselined)} Soong/Gradle drift(s) with no recorded reason:',
              file=sys.stderr)
        for _, msg in sorted(unbaselined):
            print(f'  {msg}', file=sys.stderr)
        print('', file=sys.stderr)
        print('  Nothing here edits an Android.bp. Either fix the .bp deliberately, or record the '
              'drift\n  with its reason in docs/soong-parity-baseline.txt.', file=sys.stderr)
        return 1

    print(f'OK: {len(bps)} Android.bp file(s) checked; every srcs/resource_dirs entry resolves, '
          f'{len(baseline)} drift(s) baselined with a reason.')
    print(f'    OEM API AAR matches reviewed SHA-256 {OEM_API_SHA256}.')
    print('    Dependency-list equivalence is deliberately NOT checked — see the module docstring.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
