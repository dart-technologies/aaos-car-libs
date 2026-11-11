#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Audits the AAOS manifest contract for both A2UI hosts (Sprint 1 C7).

Host A (a2ui-host-carapp, template app) must carry the Play-distribution feature,
the templates-host feature, a minCarApiLevel that is exactly the level its code
needs, at least one car-app category, and a distraction-optimized launcher entry.
Host B (a2ui-host-carui, native activities) must carry the Play-distribution
feature and distraction optimization, and must NOT claim the template contract.

The required car API level is re-derived from the code on every run: template
classes and gated builder calls map to levels taken from the pinned car-app
sources (androidx.car.app:app 1.7.0 — class- and member-level @RequiresCarApi).
A template the table does not know fails the check, so landing a new template
forces a human to classify it instead of silently keeping a stale level.

Exit codes: 0 pass, 1 a check failed, 2 an input was missing (which is not a pass).
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CARAPP_MANIFEST = ROOT / 'a2ui-host-carapp' / 'src' / 'main' / 'AndroidManifest.xml'
CARAPP_JAVA = ROOT / 'a2ui-host-carapp' / 'src' / 'main' / 'java'
CARUI_MANIFEST = ROOT / 'a2ui-host-carui' / 'src' / 'main' / 'AndroidManifest.xml'

ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
AUTOMOTIVE_FEATURE = 'android.hardware.type.automotive'
TEMPLATES_HOST_FEATURE = 'android.software.car.templates_host'
MIN_CAR_API_KEY = 'androidx.car.app.minCarApiLevel'
CARAPP_SERVICE_ACTION = 'androidx.car.app.CarAppService'
CARAPP_ACTIVITY = 'androidx.car.app.activity.CarAppActivity'

# CarAppService categories valid on AAOS. Media is a MediaLibraryService, not a
# CarAppService category; parked categories are plain activities, not templates.
CARAPP_CATEGORIES = frozenset({'NAVIGATION', 'POI', 'IOT', 'WEATHER'})

# Templates whose host dispatcher throws SecurityException without the permission, found on the
# AAOS emulator (PlaceListMap) rather than in unit tests. Search/List/Message/Pane/Grid/Tab need
# no permission; navigation templates need NAVIGATION_TEMPLATES by the same rule.
MAP_TEMPLATES = frozenset({'PlaceListMapTemplate', 'MapTemplate', 'MapWithContentTemplate'})
NAV_TEMPLATES = frozenset({'NavigationTemplate', 'PlaceListNavigationTemplate',
                            'RoutePreviewNavigationTemplate'})
MAP_PERMISSION = 'androidx.car.app.MAP_TEMPLATES'
NAV_PERMISSION = 'androidx.car.app.NAVIGATION_TEMPLATES'

# Class-level @RequiresCarApi from car-app 1.7.0 sources; unannotated == 1.
# MapTemplate is deprecated for MapWithContentTemplate but still classifies at 5.
TEMPLATE_LEVELS = {
    'ListTemplate': 1,
    'MessageTemplate': 1,
    'PaneTemplate': 1,
    'SearchTemplate': 1,
    'GridTemplate': 1,
    'PlaceListMapTemplate': 1,
    'LongMessageTemplate': 2,
    'SignInTemplate': 2,
    'TabTemplate': 6,
    'NavigationTemplate': 1,
    'PlaceListNavigationTemplate': 1,
    'RoutePreviewNavigationTemplate': 1,
    'MapTemplate': 5,
    'MapWithContentTemplate': 7,
}

# Member-level gates the hosts could call, same source. addAction/getActions are
# level 6 on ListTemplate but 7 on GridTemplate — GATED_CALL_GRID_BUMP handles it.
GATED_CALLS = [
    (re.compile(r'\.setHeader\s*\('), 7, 'setHeader(Header)'),
    (re.compile(r'\.addAction\s*\('), 6, 'addAction(Action)'),
    (re.compile(r'\.getActions\s*\('), 6, 'getActions()'),
    (re.compile(r'setOnContentRefreshListener\s*\('), 5,
     'setOnContentRefreshListener(..)'),
    (re.compile(r'getOnContentRefreshDelegate\s*\('), 5,
     'getOnContentRefreshDelegate()'),
    (re.compile(r'\.setLoading\s*\('), 2, 'setLoading(..)'),
    (re.compile(r'\.isLoading\s*\('), 2, 'isLoading()'),
]
GRID_BUMPED = ('addAction(Action)', 'getActions()')
TEMPLATE_REF = re.compile(r'\b([A-Z]\w*Template)\b')

failures: list[str] = []


def require(path: Path) -> str:
    if not path.is_file():
        print(f'MISSING: {path.relative_to(ROOT)}', file=sys.stderr)
        sys.exit(2)
    return path.read_text()


def strip_java(text: str) -> str:
    """Removes comments and string/char literals; a mention in prose must not count."""
    out = []
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == '//':
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif two == '/*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif text[i] in '"\'':
            q = text[i]
            i += 1
            while i < n and text[i] != q:
                i += 2 if text[i] == '\\' else 1
            i += 1
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def required_level(sources: list[tuple[str, str]]) -> tuple[int, list[str]]:
    """Returns (max level, contributors like 'setHeader(Header)@7 in Foo.java').

    Unknown *Template references are returned as contributors prefixed with '?';
    the caller turns them into failures naming the file.
    """
    level = 1
    contributors: list[str] = []
    for name, text in sources:
        code = strip_java(text)
        for match in sorted(set(TEMPLATE_REF.findall(code))):
            if match == 'Template':  # the interface itself, not a template
                continue
            if match not in TEMPLATE_LEVELS:
                contributors.append(f'?{match} in {name}')
                continue
            if TEMPLATE_LEVELS[match] > level:
                level = TEMPLATE_LEVELS[match]
                contributors.append(f'{match}@{level} in {name}')
        grid = 'GridTemplate' in code
        for pattern, call_level, label in GATED_CALLS:
            if pattern.search(code):
                bumped = grid and label in GRID_BUMPED
                eff = 7 if bumped else call_level
                if eff > level:
                    level = eff
                    contributors.append(f'{label}@{eff} in {name}')
    return level, contributors


def uses_feature(root: ET.Element, name: str) -> str | None:
    for feat in root.findall('uses-feature'):
        if feat.get(f'{ANDROID_NS}name') == name:
            return feat.get(f'{ANDROID_NS}required')
    return None


def uses_permission(root: ET.Element, name: str) -> bool:
    return any(p.get(f'{ANDROID_NS}name') == name for p in root.findall('uses-permission'))


def check_carapp(manifest_text: str, sources: list[tuple[str, str]]) -> None:
    try:
        root = ET.fromstring(manifest_text)
    except ET.ParseError as e:
        failures.append(f'Host A manifest does not parse: {e}')
        return
    if uses_feature(root, AUTOMOTIVE_FEATURE) != 'true':
        failures.append('Host A manifest lacks '
                        f'<uses-feature {AUTOMOTIVE_FEATURE} required="true"> — Play would '
                        'distribute a car-only template app to phones')
    if uses_feature(root, TEMPLATES_HOST_FEATURE) != 'true':
        failures.append('Host A manifest lacks '
                        f'<uses-feature {TEMPLATES_HOST_FEATURE} required="true"> — nothing '
                        'can render it without the templates host')
    used = set()
    for _, text in sources:
        used |= set(TEMPLATE_REF.findall(strip_java(text)))
    used -= {'Template'}
    if used & MAP_TEMPLATES and not uses_permission(root, MAP_PERMISSION):
        failures.append(f'Host A renders {sorted(used & MAP_TEMPLATES)} but lacks '
                        f'<uses-permission {MAP_PERMISSION}> — the host dispatcher throws '
                        'SecurityException without it (seen on the AAOS emulator)')
    if used & NAV_TEMPLATES and not uses_permission(root, NAV_PERMISSION):
        failures.append(f'Host A renders {sorted(used & NAV_TEMPLATES)} but lacks '
                        f'<uses-permission {NAV_PERMISSION}> — the host dispatcher throws '
                        'SecurityException without it')
    app = root.find('application')
    if app is None:
        failures.append('Host A manifest has no <application>')
        return

    declared = None
    for meta in app.findall('meta-data'):
        if meta.get(f'{ANDROID_NS}name') == MIN_CAR_API_KEY:
            declared = meta.get(f'{ANDROID_NS}value')
    need, contributors = required_level(sources)
    unknown = sorted({c.split(' in ')[0][1:] + ' in ' + c.split(' in ')[1]
                      for c in contributors if c.startswith('?')})
    for u in unknown:
        failures.append(f'Host A uses unclassified template {u} — add its car-app 1.7.0 '
                        '@RequiresCarApi level to TEMPLATE_LEVELS in check-aaos-manifest.py')
    if declared is None:
        failures.append(f'Host A manifest lacks {MIN_CAR_API_KEY} meta-data')
    elif declared != str(need):
        top = ', '.join(c for c in contributors if not c.startswith('?')) or 'base templates'
        failures.append(f'Host A declares {MIN_CAR_API_KEY}={declared} but the code needs '
                        f'{need} ({top}) — the level must be what the code needs, not the '
                        'newest')

    services = [s for s in app.findall('service')
                if any(a.get(f'{ANDROID_NS}name') == CARAPP_SERVICE_ACTION
                       for f in s.findall('intent-filter') for a in f.findall('action'))]
    if not services:
        failures.append(f'Host A manifest has no service with the {CARAPP_SERVICE_ACTION} '
                        'action')
    else:
        categories = set()
        for svc in services:
            if svc.get(f'{ANDROID_NS}exported') != 'true':
                failures.append('Host A CarAppService is not exported — the host cannot bind')
            for f in svc.findall('intent-filter'):
                for c in f.findall('category'):
                    name = c.get(f'{ANDROID_NS}name') or ''
                    if name.startswith('androidx.car.app.category.'):
                        categories.add(name.rsplit('.', 1)[1])
        if not categories:
            failures.append('Host A CarAppService declares no car-app category')
        for cat in sorted(categories):
            if cat not in CARAPP_CATEGORIES:
                failures.append(f'Host A declares unknown car-app category {cat} — expected one '
                                f'of {sorted(CARAPP_CATEGORIES)}')

    launcher = [a for a in app.findall('activity')
                if a.get(f'{ANDROID_NS}name') == CARAPP_ACTIVITY]
    if not launcher:
        failures.append(f'Host A manifest lacks the {CARAPP_ACTIVITY} AAOS launcher entry')
    else:
        act = launcher[0]
        if act.get(f'{ANDROID_NS}exported') != 'true':
            failures.append(f'Host A {CARAPP_ACTIVITY} is not exported')
        distracted = any(m.get(f'{ANDROID_NS}name') == 'distractionOptimized'
                         and m.get(f'{ANDROID_NS}value') == 'true'
                         for m in act.findall('meta-data'))
        if not distracted:
            failures.append(f'Host A {CARAPP_ACTIVITY} lacks distractionOptimized=true — the '
                            'launcher would hide it while driving')


def check_carui(manifest_text: str) -> None:
    try:
        root = ET.fromstring(manifest_text)
    except ET.ParseError as e:
        failures.append(f'Host B manifest does not parse: {e}')
        return
    if uses_feature(root, AUTOMOTIVE_FEATURE) != 'true':
        failures.append('Host B manifest lacks '
                        f'<uses-feature {AUTOMOTIVE_FEATURE} required="true"> — Play would '
                        'distribute a car-ui activity app to phones')
    app = root.find('application')
    if app is None:
        failures.append('Host B manifest has no <application>')
        return
    for meta in app.findall('meta-data'):
        if meta.get(f'{ANDROID_NS}name') == MIN_CAR_API_KEY:
            failures.append('Host B declares minCarApiLevel but has no CarAppService — a '
                            'native activity must not claim the template contract')
    carapp_services = [s for s in app.findall('service')
              if any(a.get(f'{ANDROID_NS}name') == CARAPP_SERVICE_ACTION
                     for f in s.findall('intent-filter') for a in f.findall('action'))]
    if carapp_services:
        failures.append('Host B declares a CarAppService — it renders in-process and must stay '
                        'a native AAOS app')
    for act in app.findall('activity'):
        if act.get(f'{ANDROID_NS}exported') == 'true':
            distracted = any(m.get(f'{ANDROID_NS}name') == 'distractionOptimized'
                             and m.get(f'{ANDROID_NS}value') == 'true'
                             for m in act.findall('meta-data'))
            if not distracted:
                failures.append(f'Host B activity {act.get(f"{ANDROID_NS}name")} is exported '
                                'without distractionOptimized=true — the launcher would hide '
                                'it while driving')


def main() -> int:
    carapp_text = require(CARAPP_MANIFEST)
    carui_text = require(CARUI_MANIFEST)
    if not CARAPP_JAVA.is_dir():
        print(f'MISSING: {CARAPP_JAVA.relative_to(ROOT)}', file=sys.stderr)
        return 2
    sources = [(p.relative_to(ROOT).as_posix(), p.read_text())
               for p in sorted(CARAPP_JAVA.rglob('*.java'))]
    if not sources:
        print(f'MISSING: no java sources under {CARAPP_JAVA.relative_to(ROOT)}',
              file=sys.stderr)
        return 2
    check_carapp(carapp_text, sources)
    check_carui(carui_text)
    if failures:
        print(f'\nFAIL: {len(failures)} AAOS manifest problem(s):', file=sys.stderr)
        for f in failures:
            print(f'  - {f}', file=sys.stderr)
        return 1
    print('OK: both hosts satisfy the AAOS manifest contract.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
