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
package art.dart.a2ui.policy;

import static com.google.common.truth.Truth.assertThat;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small policy helpers: the null-tree counters (the case a handoff to a driving vehicle produces)
 * and the spoken-form hint, whose text a driver reads and then speaks.
 */
public class A2uiPolicyHelperTest {

    private static final Set<ComponentType> NOTHING = Collections.emptySet();

    private static Set<ComponentType> only(ComponentType... types) {
        return new LinkedHashSet<>(java.util.Arrays.asList(types));
    }

    private static A2uiComponent tree() {
        return new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root")
                .addChild(new A2uiComponent.Builder(ComponentType.TEXT).setId("t").build())
                .addChild(new A2uiComponent.Builder(ComponentType.BUTTON)
                        .setId("buy").setAction("purchase").build())
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Counting nothing.

    @Test
    public void countingTypesInAnAbsentTreeIsZero_notACrash() {
        // prune() returns null when the root is pruned. Asserting 0, not "does not throw".
        assertThat(A2uiPruner.countOfType(null, ComponentType.BUTTON)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(tree(), ComponentType.BUTTON)).isEqualTo(1);
    }

    @Test
    public void aDestinationThatPrunesEverythingReportsTheWholeTreeAsRemoved() {
        // Reached as in production: the destination's allowed set excludes the root's type.
        A2uiHandoff.Endpoint source = A2uiHandoff.Endpoint.of(
                only(ComponentType.COLUMN, ComponentType.TEXT, ComponentType.BUTTON),
                A2uiValueLimits.unrestricted(),
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, true);
        A2uiHandoff.Endpoint destination = A2uiHandoff.Endpoint.of(
                NOTHING, A2uiValueLimits.failClosed(),
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true);

        A2uiHandoff.Result r = A2uiHandoff.reproject(tree(), source, destination);

        assertThat(r.atDestination()).isNull();
        // 3 at the source, none at the destination — and signed, so this cannot be confused with
        // the reverse direction.
        assertThat(r.netComponentsRemoved()).isEqualTo(3);
        // Nothing rendered means nothing decided. A decision for a component the destination never
        // shows would be a decision nobody can act on.
        assertThat(r.decisionsAtDestination()).isEmpty();
    }

    @Test
    public void bothSidesPruningEverythingIsANetOfZero() {
        A2uiHandoff.Endpoint blind = A2uiHandoff.Endpoint.of(
                NOTHING, A2uiValueLimits.failClosed(),
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true);
        A2uiHandoff.Result r = A2uiHandoff.reproject(tree(), blind, blind);

        assertThat(r.atSource()).isNull();
        assertThat(r.atDestination()).isNull();
        // 0 - 0. Distinct from "nothing was removed": both trees are gone. netComponentsRemoved is
        // a difference, not a summary, and this is the case that shows it.
        assertThat(r.netComponentsRemoved()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------------------
    // The spoken form.

    @Test
    public void theVoiceHintIsTheQuotedLowercasePhraseADriverWouldSay() {
        assertThat(A2uiActionBinder.voiceHint("Play")).isEqualTo("say “play”");
        // Curly quotes: this is display text.
        assertThat(A2uiActionBinder.voiceHint("Play")).contains("“");
        assertThat(A2uiActionBinder.voiceHint("Play")).doesNotContain("\"");
    }

    @Test
    public void theVoiceHintNormalisesWhatTheAgentWrote() {
        // The phrase comes from an agent, so its casing and padding are not this repo's to trust.
        // It must match what the recogniser will hand back, which is lower-cased and trimmed.
        assertThat(A2uiActionBinder.voiceHint("  PLAY the ALBUM  "))
                .isEqualTo("say “play the album”");
    }

    // ---------------------------------------------------------------------------------------
    // Describing the limits.

    @Test
    public void unrestrictedLimitsDescribeThemselvesAsUnlimited_notAsAHugeNumber() {
        // Integer.MAX_VALUE in a log line is a number someone will try to interpret. "unlimited"
        // is the thing it actually means.
        String s = A2uiValueLimits.unrestricted().toString();
        assertThat(s).contains("string=unlimited");
        assertThat(s).contains("items=unlimited");
        assertThat(s).contains("depth=unlimited");
        assertThat(s).doesNotContain(String.valueOf(Integer.MAX_VALUE));
    }

    @Test
    public void failClosedLimitsDescribeTheirRealNumbers() {
        String s = A2uiValueLimits.failClosed().toString();
        assertThat(s).startsWith("A2uiValueLimits{");
        assertThat(s).endsWith("}");
        // Whatever the fail-closed values are, they are finite and therefore printed as digits.
        assertThat(s).doesNotContain("unlimited");
    }

    // ---------------------------------------------------------------------------------------
    // The catalog's own refusal.

    @Test
    public void aNullCatalogNameIsNotAType() {
        // A component entry without "component" yields null; it must not NPE before refusal.
        assertThat(ComponentType.fromCatalogName(null)).isNull();
        assertThat(ComponentType.fromCatalogName("Hologram")).isNull();
        assertThat(ComponentType.fromCatalogName("Button")).isEqualTo(ComponentType.BUTTON);
    }
}
