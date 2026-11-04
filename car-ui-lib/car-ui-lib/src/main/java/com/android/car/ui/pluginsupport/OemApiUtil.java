/*
 * Copyright (C) 2020 The Android Open Source Project
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
 *
 * Modified by Dart Technologies, 2026; see FORK.md.
 */
package com.android.car.ui.pluginsupport;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV2;
import com.android.car.ui.plugin.oemapis.PluginVersionProviderOEMV1;

/**
 * Helper class for accessing oem-apis without reflection.
 *
 * Because this class creates adapters, and adapters implement OEM APIs, this class cannot
 * be created with the app's classloader. You must use {@link AdapterClassLoader} to load it
 * so that both the app and plugin's classes can be loaded from this class.
 */
@RequiresApi(MIN_TARGET_API)
final class OemApiUtil {

    private static final String TAG = "carui";

    /**
     * Given a plugin's context, return it's implementation of {@link PluginFactory}.
     *
     * This is done by asking the plugin's {@link PluginVersionProvider} for a
     * factory object, and checking if it's instanceof each version of
     * {@link PluginFactoryOEMV1}, casting to the correct one when found.
     *
     * @param pluginContext The plugin's context. This context will return
     *                             the plugin's classloader from
     *                             {@link Context#getClassLoader()}.
     * @param appPackageName The package name of the application. This is passed to the plugin
     *                       so that it can provide unique customizations per-app.
     * @return A {@link PluginFactory}
     */
    /**
     * The highest {@code PluginFactoryOEMV#} this library has an adapter for.
     *
     * <p>Dart fork addition. Keep in lockstep with the {@code instanceof} chain in
     * {@link #getPluginFactory}: this is what we tell the plugin we can consume, and asking for
     * more than we can adapt is how an app ends up holding a factory it cannot call.
     */
    private static final int MAX_SUPPORTED_VERSION = 2;

    @SuppressLint("PrivateApi")
    static PluginFactory getPluginFactory(
            Context pluginContext, String appPackageName) {

        Object oemVersionProvider = null;
        try {
            oemVersionProvider = Class
                    .forName("com.android.car.ui.plugin.PluginVersionProviderImpl")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "PluginVersionProviderImpl not found.", e);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "PluginVersionProviderImpl could not be instantiated!", e);
        }

        // Add new version providers in an if-else chain here, in descending version order so
        // that higher versions are preferred.
        PluginVersionProvider versionProvider = null;
        if (oemVersionProvider instanceof PluginVersionProviderOEMV1) {
            versionProvider = new PluginVersionProviderAdapterV1(
                    (PluginVersionProviderOEMV1) oemVersionProvider);
        } else {
            Log.e(TAG, "PluginVersionProviderImpl was not instanceof any known "
                    + "versions of PluginVersionProviderOEMV#.");
        }

        PluginFactory oemPluginFactory = null;
        if (versionProvider != null) {
            // Dart fork fix: advertise the HIGHEST version we can actually consume, and test the
            // chain HIGHEST-FIRST.
            //
            // This previously passed 1 while accepting V2 below, and tested V1 before V2. Two
            // defects in three lines: the app understated its ceiling, so a contract-honouring
            // plugin would hand back a V1 factory and the V2 branch was dead code; and where a
            // factory implemented both, ascending order adapted it as V1 and discarded the V2
            // capabilities. The comment on the version-provider chain above already says
            // "descending version order so that higher versions are preferred" — it simply was
            // not applied here.
            //
            // This is the app-side mirror of docs/upstream/AOSP-PLUGIN-VERSION-REPORT.md, which is
            // about the plugin ignoring maxVersion. Negotiation was failing from both ends, which
            // is why the version scheme did not prevent the ABI mismatch it exists to prevent.
            //
            // MAX_SUPPORTED_VERSION must equal the highest PluginFactoryAdapterV# implemented
            // here. Raise it only when a new adapter lands, never speculatively — over-advertising
            // asks the plugin for a factory we cannot consume, which is the crash this fork spent
            // a day diagnosing.
            Object factory = versionProvider.getPluginFactory(
                    MAX_SUPPORTED_VERSION, pluginContext, appPackageName);
            oemPluginFactory = adaptFactory(factory);
        }

        return oemPluginFactory;
    }

    /**
     * The version-negotiation decision, extracted so it can be tested.
     *
     * <p>Dart fork addition. Everything above this point needs a real plugin APK and a
     * {@code Class.forName} against its ClassLoader, so the negotiation could not be exercised
     * without one — and negotiation is precisely what this fork had to repair at both ends. The
     * app-side repair (advertise the real ceiling, test highest-first) had NO test until this seam
     * existed, which is the same shape as the defect it fixed: a correctness argument written in a
     * comment and asserted nowhere.
     *
     * @param factory whatever the plugin's version provider handed back, possibly null
     * @return an adapter for the highest version this library can consume, or null
     */
    @Nullable
    @VisibleForTesting
    static PluginFactory adaptFactory(@Nullable Object factory) {
        if (classExists("com.android.car.ui.plugin.oemapis.PluginFactoryOEMV2")
                && factory instanceof PluginFactoryOEMV2) {
            return new PluginFactoryAdapterV2((PluginFactoryOEMV2) factory);
        } else if (factory instanceof PluginFactoryOEMV1) {
            return new PluginFactoryAdapterV1((PluginFactoryOEMV1) factory);
        } else {
            // NAME what arrived. This used to say only "did not provide a factory implementing any
            // known interfaces!", which tells an integrator that something is wrong and nothing
            // about what. The interesting case is a head unit whose plugin implements only
            // PluginFactoryOEMV3..V7 -- five generations this library has no adapter for -- and in
            // that case the class name is the whole diagnosis. Without it, the log is
            // indistinguishable from a plugin that returned garbage.
            Log.e(TAG, "PluginVersionProvider found, but did not provide a factory implementing"
                    + " any interface this library can adapt (highest supported is"
                    + " PluginFactoryOEMV" + MAX_SUPPORTED_VERSION + "). It provided: "
                    + (factory == null ? "null" : factory.getClass().getName()));
            return null;
        }
    }

    /** The highest {@code PluginFactoryOEMV#} this library can consume. See the field. */
    @VisibleForTesting
    static int maxSupportedVersion() {
        return MAX_SUPPORTED_VERSION;
    }

    private static boolean classExists(@Nullable String className) {
        if (className == null) {
            return false;
        }
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private OemApiUtil() {}
}
