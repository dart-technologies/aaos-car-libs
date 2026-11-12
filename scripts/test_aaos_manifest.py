#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Device-free tests for check-aaos-manifest.py: level derivation and both hosts.

  python3 scripts/test_aaos_manifest.py
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check_aaos_manifest",
                                               HERE / "check-aaos-manifest.py")
check = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = check
_spec.loader.exec_module(check)

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'

GOOD_CARAPP = f"""<manifest {NS}>
    <uses-feature android:name="android.software.car.templates_host" android:required="true" />
    <uses-feature android:name="android.hardware.type.automotive" android:required="true" />
    <application>
        <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="7" />
        <service android:name="art.dart.A2ui" android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.POI" />
            </intent-filter>
        </service>
        <service android:name="art.dart.Media" android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaLibraryService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
            <meta-data android:name="androidx.car.app.launchable" android:value="true" />
        </service>
        <activity android:name="androidx.car.app.activity.CarAppActivity" android:exported="true">
            <meta-data android:name="distractionOptimized" android:value="true" />
        </activity>
    </application>
</manifest>"""

GOOD_CARUI = f"""<manifest {NS}>
    <uses-feature android:name="android.hardware.type.automotive" android:required="true" />
    <application>
        <activity android:name="art.dart.A2ui" android:exported="true">
            <meta-data android:name="distractionOptimized" android:value="true" />
        </activity>
    </application>
</manifest>"""

NEEDS_LIST = 'import androidx.car.app.model.ListTemplate;\nclass R { ListTemplate t; }'
NEEDS_HEADER = NEEDS_LIST + '\nclass S { void m() { b.setHeader(h); } }'
NEEDS_ADDACTION = NEEDS_LIST + '\nclass S { void m() { b.addAction(a); } }'
NEEDS_GRID_ACTION = ('import androidx.car.app.model.GridTemplate;\n'
                     'class S { void m() { g.addAction(a); } }')
NEEDS_TAB = 'import androidx.car.app.model.TabTemplate;\nclass S { TabTemplate t; }'


class LevelTests(unittest.TestCase):
    def level(self, *bodies):
        sources = [(f'F{i}.java', b) for i, b in enumerate(bodies)]
        return check.required_level(sources)

    def test_base_template_is_level_1(self):
        level, _ = self.level(NEEDS_LIST)
        self.assertEqual(level, 1)

    def test_set_header_needs_7(self):
        level, contributors = self.level(NEEDS_HEADER)
        self.assertEqual(level, 7)
        self.assertTrue(any('setHeader' in c for c in contributors))

    def test_add_action_on_list_needs_6(self):
        level, _ = self.level(NEEDS_ADDACTION)
        self.assertEqual(level, 6)

    def test_add_action_on_grid_needs_7(self):
        level, _ = self.level(NEEDS_GRID_ACTION)
        self.assertEqual(level, 7)

    def test_tab_template_needs_6(self):
        level, _ = self.level(NEEDS_TAB)
        self.assertEqual(level, 6)

    def test_unknown_template_is_flagged_not_guessed(self):
        _, contributors = self.level('class S { FooTemplate t; }')
        self.assertTrue(any(c.startswith('?FooTemplate') for c in contributors))

    def test_bare_template_interface_is_ignored(self):
        level, contributors = self.level('class S { Template t; }')
        self.assertEqual((level, contributors), (1, []))

    def test_comments_and_strings_do_not_count(self):
        body = ('// b.setHeader(h) in a comment\n'
                '/* GridTemplate in a block */\n'
                'class S { String s = "TabTemplate"; char c = \'x\'; }')
        level, contributors = self.level(body)
        self.assertEqual((level, contributors), (1, []))


class ManifestTests(unittest.TestCase):
    def setUp(self):
        check.failures.clear()

    def test_good_carapp_passes(self):
        check.check_carapp(GOOD_CARAPP, [('R.java', NEEDS_HEADER)])
        self.assertEqual(check.failures, [])

    def test_carapp_missing_automotive_feature_fails(self):
        bad = GOOD_CARAPP.replace('android.hardware.type.automotive', 'android.hardware.nothing')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('automotive' in f for f in check.failures))

    def test_carapp_level_mismatch_fails(self):
        bad = GOOD_CARAPP.replace('android:value="7"', 'android:value="6"')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('minCarApiLevel' in f and 'needs 7' in f
                            for f in check.failures))

    def test_carapp_overdeclared_level_fails(self):
        bad = GOOD_CARAPP.replace('android:value="7"', 'android:value="7"')
        check.check_carapp(bad, [('R.java', NEEDS_LIST)])
        self.assertTrue(any('needs 1' in f for f in check.failures))

    def test_carapp_unknown_template_fails(self):
        check.check_carapp(GOOD_CARAPP, [('R.java', 'class S { FooTemplate t; }')])
        self.assertTrue(any('unclassified template FooTemplate' in f
                            for f in check.failures))

    def test_carapp_map_template_without_permission_fails(self):
        # GOOD_CARAPP declares no permissions; PlaceListMap needs MAP_TEMPLATES.
        check.check_carapp(GOOD_CARAPP, [('R.java', 'class S { PlaceListMapTemplate t; }')])
        self.assertTrue(any('MAP_TEMPLATES' in f for f in check.failures))

    def test_carapp_map_template_with_permission_passes(self):
        manifest = GOOD_CARAPP.replace(
            '<application>',
            '<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />\n'
            '    <application>', 1)
        # minCarApiLevel 7 fixture vs PlaceListMap base level 1: align the level first.
        manifest = manifest.replace('android:value="7"', 'android:value="1"')
        check.check_carapp(manifest, [('R.java', 'class S { PlaceListMapTemplate t; }')])
        self.assertEqual(check.failures, [])

    def test_carapp_surface_callback_without_permission_fails(self):
        # GOOD_CARAPP declares no permissions; setSurfaceCallback needs ACCESS_SURFACE.
        check.check_carapp(GOOD_CARAPP,
                           [('R.java', 'class S { void m() { app.setSurfaceCallback(cb); } }')])
        self.assertTrue(any('ACCESS_SURFACE' in f for f in check.failures))

    def test_carapp_surface_callback_with_permission_passes(self):
        manifest = GOOD_CARAPP.replace(
            '<application>',
            '<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />\n'
            '    <application>', 1)
        # minCarApiLevel 7 fixture vs no classified templates: align the level first.
        manifest = manifest.replace('android:value="7"', 'android:value="1"')
        check.check_carapp(
            manifest, [('R.java', 'class S { void m() { app.setSurfaceCallback(cb); } }')])
        self.assertEqual(check.failures, [])

    def test_carapp_unknown_category_fails(self):
        bad = GOOD_CARAPP.replace('app.category.POI', 'app.category.SUBMARINE')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('unknown car-app category SUBMARINE' in f
                            for f in check.failures))

    def test_carapp_media_service_without_legacy_browse_fails(self):
        bad = GOOD_CARAPP.replace(
            '<action android:name="android.media.browse.MediaBrowserService" />', '')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('android.media.browse.MediaBrowserService' in f
                            for f in check.failures))

    def test_carapp_media_service_unexported_fails(self):
        bad = GOOD_CARAPP.replace('android:name="art.dart.Media" android:exported="true"',
                                  'android:name="art.dart.Media" android:exported="false"')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('media library service is not exported' in f
                            for f in check.failures))

    def test_carapp_media_service_without_launchable_fails(self):
        bad = GOOD_CARAPP.replace(
            '<meta-data android:name="androidx.car.app.launchable" android:value="true" />',
            '')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('androidx.car.app.launchable' in f for f in check.failures))

    def test_carapp_without_media_service_fails(self):
        bad = GOOD_CARAPP.replace(
            '<action android:name="androidx.media3.session.MediaLibraryService" />', '')
        check.check_carapp(bad, [('R.java', NEEDS_HEADER)])
        self.assertTrue(any('no service with the androidx.media3.session.MediaLibraryService'
                            in f for f in check.failures))

    def test_good_carui_passes(self):
        check.check_carui(GOOD_CARUI)
        self.assertEqual(check.failures, [])

    def test_carui_native_app_must_not_claim_template_level(self):
        bad = GOOD_CARUI.replace(
            '<activity',
            '<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="7" />\n'
            '        <activity', 1)
        check.check_carui(bad)
        self.assertTrue(any('minCarApiLevel' in f for f in check.failures))

    def test_carui_exported_activity_needs_distraction_optimized(self):
        bad = GOOD_CARUI.replace('distractionOptimized', 'somethingElse')
        check.check_carui(bad)
        self.assertTrue(any('distractionOptimized' in f for f in check.failures))


if __name__ == '__main__':
    unittest.main()
