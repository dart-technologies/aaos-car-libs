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
package art.dart.a2ui.demo;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.Test;

/** The shared surface and the override that drives both hosts, pinned once for both renderers. */
public class DemoSurfaceTest {

    // -------------------------------------------------------------------------------------------

    @Test
    public void demoSurfaceRendersEveryComponentUnderBaseline() {
        A2uiComponent pruned = A2uiPruner.prune(DemoSurface.create(),
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        assertThat(pruned).isNotNull();
        assertThat(pruned.getChildren()).hasSize(9);
    }

    /**
     * Under full restriction the surface still renders something; a List root would render nothing
     * ({@code A2uiPrunerTest#listRootedSurfaceDisappearsEntirelyWhenFullyRestricted}).
     */
    @Test
    public void demoSurfaceStillShowsGlanceableContentWhenFullyRestricted() {
        A2uiComponent pruned = A2uiPruner.prune(DemoSurface.create(),
                A2uiRestrictionPolicy.failClosedComponents());

        assertThat(pruned).isNotNull();
        assertThat(pruned.getChildren()).hasSize(3);
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.TEXT)).isEqualTo(1);
        // Two buttons survive: prune cannot tell them apart, only their actions differ.
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.BUTTON)).isEqualTo(2);
    }

    @Test
    public void demoSurfaceLosesVideoUnderNoVideo() {
        A2uiComponent pruned = A2uiPruner.prune(DemoSurface.create(),
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO));

        assertThat(pruned).isNotNull();
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.VIDEO)).isEqualTo(0);
        assertThat(pruned.getChildren()).hasSize(8);
    }

    // The override holder, shared by both hosts so one broadcast sets the level for each.

    @Test
    public void demoOverrideClearsOnNegative() {
        DemoRestrictions.set(0x10);
        assertThat(DemoRestrictions.get()).isEqualTo(0x10);

        DemoRestrictions.set(-1);

        assertThat(DemoRestrictions.get()).isNull();
    }

    @Test
    public void demoOverrideAcceptsBaselineZero() {
        // 0x0 is a real level, not "unset", or BASELINE would be unreachable via the override.
        DemoRestrictions.set(0);
        try {
            assertThat(DemoRestrictions.get()).isEqualTo(0);
        } finally {
            DemoRestrictions.set(-1);
        }
    }
}
