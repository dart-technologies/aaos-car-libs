#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Asserts every declared <overlayable> reaches its own module's shipping artifact.

An overlayable is a promise to OEMs, and one missing from the artifact produces no error. Each
declaration is found by a recursive walk and checked against its own module's debug artifact
only: not another module's, not an androidTest APK, not another variant.

`aapt2 dump overlayable` answers for APKs (grepping resources.arsc finds nothing even when it is
packaged); AARs keep resources as source XML and are read directly.

Usage: check-overlayable-packaged.py [--list]
Exit codes: 0 every declaration is packaged or baselined, 1 one is not, 2 could not be determined.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'aaos-apps-gradle-project' / 'build'
BASELINE = ROOT / 'docs' / 'overlayable-baseline.txt'


def sdk_root() -> Path:
    """The SDK: ANDROID_HOME / ANDROID_SDK_ROOT first, as CI and non-macOS machines set them."""
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        value = os.environ.get(var)
        if value and Path(value).is_dir():
            return Path(value)
    for candidate in (Path.home() / 'Library' / 'Android' / 'sdk',
                      Path.home() / 'Android' / 'Sdk',
                      Path('/usr/local/lib/android/sdk')):
        if candidate.is_dir():
            return candidate
    return Path('/nonexistent')


def _version_key(path: str):
    """Sorts build-tools by version, not lexicographically (36.0.10 after 36.0.9)."""
    return [int(p) if p.isdigit() else -1 for p in Path(path).parent.name.split('.')]


def aapt2() -> str:
    found = glob.glob(str(sdk_root() / 'build-tools' / '*' / 'aapt2'))
    return max(found, key=_version_key) if found else ''


def declarations() -> list:
    """[(overlayable name, source path)], by walking the whole tree.

    A list, not a dict: two modules may legally declare the same name.
    """
    out = []
    for path in sorted(ROOT.rglob('overlayable.xml')):
        rel = path.relative_to(ROOT)
        if {'.git', '.claude', 'build'} & set(rel.parts):
            continue
        for name in re.findall(r'<overlayable\s+name="([^"]+)"', path.read_text()):
            out.append((name, rel))
    return out


def module_of(source: Path) -> Path:
    """The Gradle module owning a declaration: nearest ancestor with a build.gradle."""
    for parent in source.parents:
        if (ROOT / parent / 'build.gradle').is_file():
            return parent
    return Path(source.parts[0])


def build_dir_for(module: Path):
    """The module's build output directory, matched case-insensitively.

    Gradle names it after the project (`:PaintBooth`), not the source dir (`paintbooth`); only a
    case-insensitive filesystem hides the difference.
    """
    if not BUILD.is_dir():
        return None
    want = module.name.lower()
    for entry in sorted(BUILD.iterdir()):
        if entry.is_dir() and entry.name.lower() == want:
            return entry
    return None


# The gate and CI both run assembleDebug, so the debug artifact is what is asserted.
VARIANT = 'debug'


def artifacts_for(module: Path):
    """That module's own shipping artifacts for the variant under test: AAR for a library, APK for
    an app. androidTest APKs and other variants are excluded; a stale one is no evidence.
    """
    base = build_dir_for(module)
    if base is None or not base.is_dir():
        return []
    aars = [a for a in sorted(base.rglob('*.aar')) if VARIANT in a.name]
    if aars:
        return aars
    return [a for a in sorted(base.rglob('*.apk'))
            if VARIANT in a.name and 'androidTest' not in a.as_posix()]


def names_in(artifact: Path, tool: str) -> set:
    """Overlayable names inside ONE artifact."""
    if artifact.suffix == '.aar':
        try:
            with zipfile.ZipFile(artifact) as z:
                found = set()
                for entry in z.namelist():
                    if entry.startswith('res/values/') and entry.endswith('.xml'):
                        body = z.read(entry).decode('utf-8', 'replace')
                        found |= set(re.findall(r'<overlayable\s+name="([^"]+)"', body))
                return found
        except zipfile.BadZipFile:
            return set()
    dumped = subprocess.run([tool, 'dump', 'overlayable', str(artifact)],
                            capture_output=True, text=True).stdout
    return set(re.findall(r'name="([^"]+)"', dumped))


def baselined() -> dict:
    """{(name, module): reason}. Keyed by module too: an exemption covers one declaration."""
    if not BASELINE.is_file():
        return {}
    out = {}
    for line in BASELINE.read_text().splitlines():
        if line.startswith('#') or not line.strip():
            continue
        parts = [c.strip() for c in line.split('\t') if c.strip()]
        if len(parts) < 3:
            continue          # malformed lines grant nothing, rather than granting everything
        out[(parts[0], parts[1])] = parts[2]
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true', help='print declarations and exit')
    args = ap.parse_args()

    declared = declarations()
    if args.list:
        for name, src in sorted(declared):
            print(f'  {name:24s} {module_of(src).as_posix():28s} {src}')
        return 0
    if not declared:
        print('INDETERMINATE: no overlayable.xml found anywhere', file=sys.stderr)
        return 2

    tool = aapt2()
    if not tool:
        print(f'INDETERMINATE: no aapt2 under {sdk_root()}; nothing was checked', file=sys.stderr)
        return 2
    if not any(BUILD.rglob('*.aar')) and not any(BUILD.rglob('*.apk')):
        print('INDETERMINATE: nothing assembled; run assembleDebug first', file=sys.stderr)
        return 2

    known = baselined()
    seen = {}
    for name, src in declared:
        seen.setdefault(name, []).append(module_of(src).as_posix())
    for name, owners in sorted(seen.items()):
        if len(owners) > 1:
            print(f'  note       {name:24s} declared by {len(owners)} modules: '
                  f'{", ".join(owners)} — each checked separately')

    missing, packaged, base_count = [], 0, 0
    for name, src in sorted(declared):
        module = module_of(src)
        arts = artifacts_for(module)
        # Report the artifact the name was actually found in.
        hit = next((a for a in arts if name in names_in(a, tool)), None)
        key = (name, module.as_posix())

        if hit is not None:
            print(f'  packaged   {name:24s} -> {hit.name}')
            packaged += 1
        elif key in known:
            print(f'  baselined  {name:24s} [{module.as_posix()}] {known[key][:48]}…')
            base_count += 1
        elif not arts:
            missing.append(f'{name}: {module.as_posix()} built no {VARIANT} shipping artifact '
                           f'(a library produces an AAR; androidTest and other variants do not '
                           f'count)')
        else:
            missing.append(f'{name}: declared in {src}, absent from '
                           + ', '.join(a.name for a in arts))

    print(f'\n{len(declared)} declaration(s): {packaged} packaged, {base_count} baselined, '
          f'{len(missing)} unaccounted.')

    if missing:
        print('\nFAIL: declared overlayable(s) neither packaged nor baselined:', file=sys.stderr)
        for m in missing:
            print(f'  {m}', file=sys.stderr)
        print('\nEither make the directory a declared resource dir, or record it in\n'
              f'{BASELINE.relative_to(ROOT)} with the reason it cannot be.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
