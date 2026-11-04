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
package com.android.car.ui.pluginsupport;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV2;

import org.junit.Test;

/**
 * The APP side of plugin version negotiation, which this fork repaired and never tested.
 *
 * <p>{@code OemApiUtil} carries a long comment describing two defects it fixed: the library
 * advertised {@code maxVersion = 1} while accepting V2 (so a contract-honouring plugin handed back
 * a V1 factory and the V2 branch was dead code), and it tested the {@code instanceof} chain in
 * ASCENDING order (so a factory implementing both was adapted as V1 and its V2 capabilities
 * discarded). Both are fixed. Neither was asserted anywhere — the correctness argument lived in a
 * comment, which is the same shape as the defect it describes.
 *
 * <p>It could not be asserted before: {@code getPluginFactory} reaches the chain only after a
 * {@code Class.forName} against a plugin APK's ClassLoader. {@code adaptFactory} is the seam that
 * makes the decision testable without one.
 *
 * <p><b>What this does NOT test.</b> Whether the adapters themselves are correct, and whether a
 * plugin implementing only {@code PluginFactoryOEMV3..V7} — five generations this library has no
 * adapter for — would work. It would not, by construction, and
 * {@link #anUnadaptableFactoryIsRejectedAndNamed()} pins the diagnosis rather than the capability.
 */
public class OemApiUtilNegotiationTest {

    @Test
    public void theAdvertisedCeilingMatchesTheHighestAdapterThatExists() {
        // The invariant the comment in OemApiUtil states and nothing enforced: MAX_SUPPORTED_VERSION
        // must equal the highest PluginFactoryAdapterV# that exists. Over-advertising asks a plugin
        // for a factory this library cannot consume, which is the ABI crash this fork spent a day
        // diagnosing; under-advertising silently discards the plugin's newer capabilities, which is
        // the defect that was actually found here.
        assertThat(OemApiUtil.maxSupportedVersion()).isEqualTo(2);
        assertThat(classExists("com.android.car.ui.pluginsupport.PluginFactoryAdapterV2")).isTrue();
        assertThat(classExists("com.android.car.ui.pluginsupport.PluginFactoryAdapterV3")).isFalse();
    }

    @Test
    public void aV2FactoryIsAdaptedAsV2() {
        Object factory = mock(PluginFactoryOEMV2.class);
        assertThat(OemApiUtil.adaptFactory(factory)).isInstanceOf(PluginFactoryAdapterV2.class);
    }

    @Test
    public void aV1OnlyFactoryIsAdaptedAsV1() {
        Object factory = mock(PluginFactoryOEMV1.class);
        assertThat(OemApiUtil.adaptFactory(factory)).isInstanceOf(PluginFactoryAdapterV1.class);
    }

    @Test
    public void aFactoryImplementingBOTHIsAdaptedAsTheHIGHER() {
        // The regression that matters. Ascending order adapted this as V1 and threw the V2
        // capabilities away, silently and while working. A test that only covered the two
        // single-interface cases above would have passed against the defect.
        Object both = mock(BothVersions.class);
        assertThat(OemApiUtil.adaptFactory(both)).isInstanceOf(PluginFactoryAdapterV2.class);
    }

    @Test
    public void anUnadaptableFactoryIsRejectedAndNamed() {
        // A plugin implementing only PluginFactoryOEMV3..V7 lands here. Returning null is correct
        // -- the library falls back to its own UI rather than holding a factory it cannot call --
        // and is the whole reason the "negotiate down honestly" half of the plugin work is already
        // done. What was missing is that the log said nothing about WHAT arrived, which is the only
        // thing that tells an integrator their head unit's plugin is five generations ahead.
        assertThat(OemApiUtil.adaptFactory(new Object())).isNull();
        assertThat(OemApiUtil.adaptFactory(null)).isNull();
    }

    /** A factory implementing both versions, as a real plugin using the additive scheme may. */
    private interface BothVersions extends PluginFactoryOEMV1, PluginFactoryOEMV2 {
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
