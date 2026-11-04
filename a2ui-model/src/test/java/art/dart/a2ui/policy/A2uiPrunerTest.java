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

import java.util.EnumSet;
import java.util.Set;

/** Instance-level pruning, and the container question it forces. */
public class A2uiPrunerTest {

    private static final Set<ComponentType> GLANCEABLE =
            A2uiRestrictionPolicy.failClosedComponents();

    private static A2uiComponent leaf(ComponentType type) {
        return new A2uiComponent.Builder(type).setTitle(type.catalogName()).build();
    }

    @Test
    public void allowedLeafSurvives() {
        assertThat(A2uiPruner.prune(leaf(ComponentType.TEXT), GLANCEABLE)).isNotNull();
    }

    @Test
    public void disallowedLeafIsRemoved() {
        assertThat(A2uiPruner.prune(leaf(ComponentType.VIDEO), GLANCEABLE)).isNull();
    }

    @Test
    public void pruningRemovesAtEveryDepth() {
        A2uiComponent tree = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.ROW)
                        .addChild(new A2uiComponent.Builder(ComponentType.COLUMN)
                                .addChild(leaf(ComponentType.VIDEO))
                                .addChild(leaf(ComponentType.TEXT))
                                .build())
                        .build())
                .build();

        A2uiComponent pruned = A2uiPruner.prune(tree, GLANCEABLE);

        assertThat(A2uiPruner.countOfType(pruned, ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.TEXT)).isEqualTo(1);
    }

    // Containers. Pruning an instance tree takes a disallowed container's legal children with it,
    // so the root container's own legality decides whether anything survives.

    /**
     * A {@code List}-rooted surface vanishes entirely under {@code FULLY_RESTRICTED}, glanceable
     * children included: correct fail-closed behaviour, and a trap for surface authors.
     */
    @Test
    public void listRootedSurfaceDisappearsEntirelyWhenFullyRestricted() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.LIST)
                .addChild(leaf(ComponentType.TEXT))
                .addChild(leaf(ComponentType.BUTTON))
                .build();

        A2uiComponent pruned = A2uiPruner.prune(surface, GLANCEABLE);

        assertThat(pruned).isNull();
        // The children were individually legal. They are gone anyway.
        assertThat(GLANCEABLE).contains(ComponentType.TEXT);
        assertThat(GLANCEABLE).contains(ComponentType.BUTTON);
    }

    /** The same content under a {@code Column} root survives. The root choice is the difference. */
    @Test
    public void columnRootedSurfaceKeepsItsGlanceableChildren() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.TEXT))
                .addChild(leaf(ComponentType.VIDEO))
                .addChild(leaf(ComponentType.BUTTON))
                .build();

        A2uiComponent pruned = A2uiPruner.prune(surface, GLANCEABLE);

        assertThat(pruned).isNotNull();
        assertThat(pruned.getChildren()).hasSize(2);
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.VIDEO)).isEqualTo(0);
    }

    /** A disallowed container (e.g. Modal) takes its legal children with it; none are re-parented. */
    @Test
    public void disallowedContainerTakesItsLegalChildrenWithIt() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.TEXT))
                .addChild(new A2uiComponent.Builder(ComponentType.MODAL)
                        .addChild(leaf(ComponentType.TEXT))
                        .addChild(leaf(ComponentType.BUTTON))
                        .build())
                .build();

        A2uiComponent pruned = A2uiPruner.prune(surface, GLANCEABLE);

        assertThat(pruned).isNotNull();
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.TEXT)).isEqualTo(1);
        assertThat(A2uiPruner.countOfType(pruned, ComponentType.BUTTON)).isEqualTo(0);
    }

    @Test
    public void pruningIsNonDestructive() {
        A2uiComponent original = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.VIDEO))
                .addChild(leaf(ComponentType.TEXT))
                .build();

        A2uiPruner.prune(original, GLANCEABLE);

        // The source model is reused across renders; mutating it would make the first restriction
        // permanent.
        assertThat(original.getChildren()).hasSize(2);
        assertThat(A2uiPruner.countOfType(original, ComponentType.VIDEO)).isEqualTo(1);
    }

    @Test
    public void baselineLeavesTheTreeIntact() {
        A2uiComponent original = new A2uiComponent.Builder(ComponentType.LIST)
                .addChild(leaf(ComponentType.VIDEO))
                .addChild(leaf(ComponentType.TEXT_FIELD))
                .build();

        A2uiComponent pruned =
                A2uiPruner.prune(original, EnumSet.allOf(ComponentType.class));

        assertThat(pruned).isNotNull();
        assertThat(pruned.getChildren()).hasSize(2);
    }

    /** An empty allowed set removes everything, rather than falling back to anything. */
    @Test
    public void emptyAllowedSetRemovesEverything() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.TEXT))
                .build();

        assertThat(A2uiPruner.prune(surface, EnumSet.noneOf(ComponentType.class))).isNull();
    }

    @Test
    public void anAgentSuppliedVoicePhraseIsClampedToTheVehicleBudget() {
        // voicePhrase is agent text shown as a "say ..." hint, so it must be clamped.
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy").setAction("purchase")
                .setVoicePhrase("buy the soundtrack and everything else this artist has ever "
                        + "released including the live bootlegs")
                .build();

        A2uiComponent limited = A2uiPruner.applyLimits(root, A2uiValueLimits.failClosed());

        assertThat(A2uiActionBinder.voiceHint(limited.getVoicePhrase()).length())
                .isAtMost(A2uiValueLimits.failClosed().getMaxStringLength());
    }

    @Test
    public void renderedVoiceHintAlwaysFitsSmallStringBudgets() {
        for (int max : new int[]{4, 6, 8, 60}) {
            A2uiComponent root = new A2uiComponent.Builder(ComponentType.BUTTON)
                    .setId("play").setTitle("Play a very long selection")
                    .setAction("play").build();
            A2uiComponent limited = A2uiPruner.applyLimits(root,
                    A2uiValueLimits.degenerateForTesting(max, 10, 10));
            String phrase = limited.getVoicePhrase();
            if (phrase != null && !phrase.isEmpty()) {
                assertThat(A2uiActionBinder.voiceHint(phrase).length()).isAtMost(max);
            } else {
                assertThat(max).isAtMost(6);
            }
        }
    }

    @Test
    public void theTitleFallbackIsNotTurnedIntoADeclaredPhrase() {
        // Clamp the declared phrase, not the effective one, or the title becomes a declaration.
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("play").setTitle("Play").setAction("play").build();

        A2uiComponent limited = A2uiPruner.applyLimits(root, A2uiValueLimits.unrestricted());

        assertThat(limited.getDeclaredVoicePhrase()).isNull();
        assertThat(limited.getVoicePhrase()).isEqualTo("Play");
    }

    @Test
    public void aDeclaredPhraseSurvivesOnANonInteractiveComponent() {
        // The effective phrase is null without an action, so clamping it would delete this one.
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.TEXT)
                .setId("t").setTitle("Now playing").setVoicePhrase("now playing").build();

        A2uiComponent limited = A2uiPruner.applyLimits(root, A2uiValueLimits.unrestricted());

        assertThat(limited.getDeclaredVoicePhrase()).isEqualTo("now playing");
    }
}
