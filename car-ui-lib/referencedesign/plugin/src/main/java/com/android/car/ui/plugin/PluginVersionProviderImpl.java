/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.ui.plugin;

import android.content.Context;

import com.android.car.ui.plugin.oemapis.PluginVersionProviderOEMV1;

import com.chassis.car.ui.plugin.PluginFactoryImpl;

/**
 * An implementation of {@link PluginVersionProviderOEMV1} for the reference design plugin.
 */
public class PluginVersionProviderImpl implements PluginVersionProviderOEMV1 {

    /**
     * The highest {@code PluginFactoryOEMV#} this plugin implements.
     *
     * <p>Keep in lockstep with {@link PluginFactoryImpl}'s {@code implements} clause. It is
     * maintained by hand, which is unattractive — but the alternative, in the tree this fork
     * started from, was a runtime crash with no diagnostic pointing at the version boundary.
     */
    private static final int PROVIDED_VERSION = 2;

    @Override
    public Object getPluginFactory(int maxVersion, Context context, String packageName) {
        // Dart fork fix: honour maxVersion, and take the contract's documented escape hatch.
        //
        // This previously ignored maxVersion entirely and returned its factory unconditionally.
        // PluginVersionProviderOEMV1 specifies:
        //
        //   "OEMs should aim to return the highest version of the factory possible that is <=
        //    maxVersion. If the plugin is not able to provide that version, it may return null,
        //    in which case car-ui-lib will fall back to it's static, uncustomized implementation."
        //
        // Returning null means the app renders unstyled but ALIVE. Returning an incompatible
        // factory means the app calls a method the interface does not implement and the PROCESS
        // CRASHES — which is what took this repository's 497-test suite down at 227, and what
        // happens on a head unit whenever the plugin and the app were built against different
        // plugin-apis versions.
        //
        // This is the defect docs/upstream/AOSP-PLUGIN-VERSION-REPORT.md describes in the
        // reference implementation. Fixing it here means the fork stops shipping the pattern it
        // is reporting.
        if (maxVersion < PROVIDED_VERSION) {
            return null;
        }
        return new PluginFactoryImpl(context);
    }
}
