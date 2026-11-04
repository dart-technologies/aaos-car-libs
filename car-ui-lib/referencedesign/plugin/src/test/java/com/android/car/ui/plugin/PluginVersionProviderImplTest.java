/*
 * Copyright (C) 2026 Dart Technologies
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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.chassis.car.ui.plugin.PluginFactoryImpl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for the plugin's version-negotiation entry point.
 *
 * <p><b>These are the first tests this module has ever had</b>, across 5,502 lines — and this is the
 * class OEMs copy first when building a plugin of their own.
 *
 * <p>These tests originally pinned a <b>contract violation</b> as characterisation. The violation
 * has since been <b>fixed</b> — this fork's {@code PluginVersionProviderImpl} now honours
 * {@code maxVersion} — so those three tests were deleted and replaced with tests of the contract,
 * exactly as their own javadoc instructed. See {@code docs/upstream/AOSP-PLUGIN-VERSION-REPORT.md};
 * the report still stands against AOSP's reference implementation, which this fork no longer
 * mirrors.
 *
 * <p>{@code PluginVersionProviderOEMV1} specifies:
 *
 * <blockquote>
 * OEMs should aim to return the highest version of the factory possible that is <b>&lt;=
 * maxVersion</b>. If the plugin is not able to provide that version, it <b>may return null</b>, in
 * which case car-ui-lib will fall back to its static, uncustomized implementation.
 * </blockquote>
 *
 * <p>AOSP's reference implementation ignores {@code maxVersion} and always returns the same
 * factory. The consequence is not hypothetical: when the app's {@code oem-apis} is newer than the plugin's,
 * the app calls a method the plugin's interface copy does not implement and the <b>process
 * crashes</b> with {@code AbstractMethodError}. That is exactly what took down this repository's
 * 497-test suite at 227 — for hours, presenting as flake, then as a dependency regression, then as
 * emulator degradation.
 *
 * <p>The contract's null return is the designed escape hatch for precisely this case, and the
 * reference implementation never takes it.
 */
@RunWith(RobolectricTestRunner.class)
public class PluginVersionProviderImplTest {

    private final PluginVersionProviderImpl mProvider = new PluginVersionProviderImpl();
    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Test
    public void returnsAFactory_forAVersionItSupports() {
        Object factory = mProvider.getPluginFactory(2, mContext, "com.example.app");

        assertThat(factory).isInstanceOf(PluginFactoryImpl.class);
    }

    /**
     * The contract's requirement, now honoured.
     *
     * <p>This replaces three characterisation tests that pinned the violation. Their javadoc said
     * to delete rather than adjust them when the fix landed; it landed, and they are deleted.
     *
     * <p>An app advertising a version below what this plugin provides must get {@code null}, so
     * {@code car-ui-lib} falls back to its static implementation. The alternative — handing back a
     * factory the app cannot speak to — is a <b>process crash</b> on first call.
     */
    @Test
    public void belowProvidedVersion_returnsNull_soTheAppCanFallBack() {
        assertThat(mProvider.getPluginFactory(1, mContext, "com.example.app")).isNull();
    }

    /**
     * {@code maxVersion = 0} means "I support no version of this factory". There is no factory at
     * version <= 0, so null is the only conformant answer.
     */
    @Test
    public void maxVersionZero_returnsNull() {
        assertThat(mProvider.getPluginFactory(0, mContext, "com.example.app")).isNull();
    }

    /**
     * A negative version is nonsense and must not yield a factory either. Previously nothing
     * validated this parameter at all — it was never read.
     */
    @Test
    public void negativeMaxVersion_returnsNull() {
        assertThat(mProvider.getPluginFactory(-1, mContext, "com.example.app")).isNull();
    }

    /**
     * At or above the provided version, the factory is returned. Pinned so that "honour
     * maxVersion" cannot degenerate into "always return null", which would pass every test above
     * while silently disabling the plugin.
     */
    @Test
    public void atOrAboveProvidedVersion_returnsTheFactory() {
        assertThat(mProvider.getPluginFactory(2, mContext, "com.example.app"))
                .isInstanceOf(PluginFactoryImpl.class);
        assertThat(mProvider.getPluginFactory(9, mContext, "com.example.app"))
                .isInstanceOf(PluginFactoryImpl.class);
    }

    /**
     * The per-app customisation hook the contract advertises is also unused.
     *
     * <p>Not a defect — the contract says the plugin "may" vary by package — but worth pinning, so
     * that an OEM copying this class can see from the tests that both parameters are inert.
     */
    @Test
    public void doesNotVaryByPackageName() {
        Object a = mProvider.getPluginFactory(2, mContext, "com.example.a");
        Object b = mProvider.getPluginFactory(2, mContext, "com.example.b");

        assertThat(a.getClass()).isEqualTo(b.getClass());
    }

    @Test
    public void returnsADistinctInstancePerCall() {
        Object a = mProvider.getPluginFactory(2, mContext, "com.example.app");
        Object b = mProvider.getPluginFactory(2, mContext, "com.example.app");

        // Not a contract requirement; pinned because a shared instance would make the per-app
        // customisation hook impossible to implement later without a behaviour change.
        assertThat(a).isNotSameInstanceAs(b);
    }
}
