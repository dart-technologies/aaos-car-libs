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

import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The corpus is generated content, so it is checked rather than trusted: every case replays through
 * the real processor, and catalog coverage is measured.
 */
public class UseCaseCorpusTest {

    private static A2uiMessageProcessor processor() {
        return new A2uiMessageProcessor(UseCaseCorpus.CATALOG_ID,
                new HashSet<>(Collections.singletonList(UseCaseCorpus.VERSION)));
    }

    @Test
    public void everyUseCaseReplaysThroughTheRealProcessor() {
        // Protocol messages, through the same ingest validation as an agent's
        // (test_automotive_out_of_catalog_component_is_not_reintroduced, applied to the corpus).
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            A2uiMessageProcessor p = processor();
            p.processAll(c.getMessages());

            assertThat(p.getSurface(c.getId())).isNotNull();
            assertThat(p.getSurface(c.getId()).project("root")).isNotNull();
        }
    }

    @Test
    public void theCorpusCoversTheENTIRECatalog() {
        // A type nobody exercised fails the build.
        Set<ComponentType> covered = new LinkedHashSet<>();
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            covered.addAll(c.getComponentTypes());
        }

        Set<ComponentType> missing = EnumSet.allOf(ComponentType.class);
        missing.removeAll(covered);

        assertThat(missing).isEmpty();
        assertThat(covered).hasSize(ComponentType.values().length);
    }

    @Test
    public void everyUseCaseIsInterestingAtSomeLevel_andSaysWhich() {
        // A corpus is a demo script, not a bag of surfaces. A case that cannot say what it
        // demonstrates is one nobody will know when to show.
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            assertThat(c.getIntent()).isNotEmpty();
            assertThat(c.getInterestingAt()).isNotEmpty();
            assertThat(c.getTemplateCapabilities()).isNotEmpty();
        }
    }

    @Test
    public void corpusCoversEveryPlannedHostTemplateCapability() {
        Set<UseCaseCorpus.TemplateCapability> covered = EnumSet.noneOf(
                UseCaseCorpus.TemplateCapability.class);
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            covered.addAll(c.getTemplateCapabilities());
        }

        assertThat(covered).containsExactlyElementsIn(
                EnumSet.allOf(UseCaseCorpus.TemplateCapability.class));
    }

    @Test
    public void poiScenarioCarriesSearchRouteNavigationAndParkedPurchase() {
        UseCaseCorpus.UseCase poi = UseCaseCorpus.byId("poi_discovery_order");
        assertThat(poi).isNotNull();
        assertThat(poi.getTemplateCapabilities()).containsAtLeast(
                UseCaseCorpus.TemplateCapability.SEARCH,
                UseCaseCorpus.TemplateCapability.PLACE_LIST_MAP,
                UseCaseCorpus.TemplateCapability.MAP_WITH_CONTENT,
                UseCaseCorpus.TemplateCapability.ROUTE_PREVIEW,
                UseCaseCorpus.TemplateCapability.NAVIGATION);

        A2uiMessageProcessor p = processor();
        p.processAll(poi.getMessages());
        A2uiComponent root = p.getSurface(poi.getId()).project("root");
        assertThat(findById(root, "order").getAction()).isEqualTo("purchase");
        assertThat(findById(root, "order").getVoicePhrase()).isEqualTo("order ahead");
        assertThat(findById(root, "route").getAction()).isEqualTo("navigate");
        assertThat(findById(root, "field").getType()).isEqualTo(ComponentType.TEXT_FIELD);
    }

    @Test
    public void bunnyScenarioSeparatesParkedVideoFromMotionSafeAudio() {
        UseCaseCorpus.UseCase bunny = UseCaseCorpus.byId("big_buck_bunny");
        A2uiMessageProcessor p = processor();
        p.processAll(bunny.getMessages());
        A2uiComponent root = p.getSurface(bunny.getId()).project("root");

        A2uiComponent inMotion = A2uiPruner.prune(root,
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO));
        assertThat(A2uiPruner.countOfType(inMotion, ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(inMotion, ComponentType.AUDIO_PLAYER)).isEqualTo(1);
        assertThat(findById(inMotion, "play").getAction()).isEqualTo("play");
        assertThat(findById(inMotion, "play").getVoicePhrase()).isEqualTo("play audio");
        assertThat(findById(root, "watch").getVoicePhrase()).isEqualTo("open Big Buck Bunny");
    }

    @Test
    public void useCaseIdsAreUniqueAndAddressable() {
        Set<String> ids = new LinkedHashSet<>();
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            assertThat(ids.add(c.getId())).isTrue();
            assertThat(UseCaseCorpus.byId(c.getId())).isSameInstanceAs(
                    UseCaseCorpus.byId(c.getId()));
        }
        assertThat(UseCaseCorpus.byId("no-such-case")).isNull();
    }

    @Test
    public void everyUseCaseStillRendersSomethingWhenFullyRestricted_orIsHonestlyEmpty() {
        // Either outcome is legitimate, a crash is not: a root outside the glanceable set prunes to
        // null (A2uiPrunerTest#listRootedSurfaceDisappearsEntirelyWhenFullyRestricted).
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            A2uiMessageProcessor p = processor();
            p.processAll(c.getMessages());
            A2uiComponent projected = p.getSurface(c.getId()).project("root");

            A2uiComponent pruned = A2uiPruner.prune(
                    projected, A2uiRestrictionPolicy.failClosedComponents());

            if (pruned != null) {
                assertThat(A2uiPruner.countOfType(pruned, ComponentType.VIDEO)).isEqualTo(0);
                assertThat(A2uiPruner.countOfType(pruned, ComponentType.TEXT_FIELD)).isEqualTo(0);
            }
        }
    }

    @Test
    public void theHandoffCaseCarriesWhatMakesTheHandoffDangerous() {
        // The handoff surface must contain things the driver display has to strip.
        UseCaseCorpus.UseCase handoff = UseCaseCorpus.byId("handoff_to_driver");

        assertThat(handoff).isNotNull();
        assertThat(handoff.getComponentTypes()).containsAtLeast(
                ComponentType.VIDEO, ComponentType.LIST, ComponentType.TEXT_FIELD);

        A2uiMessageProcessor p = processor();
        p.processAll(handoff.getMessages());
        A2uiComponent composed = p.getSurface(handoff.getId()).project("root");

        // Composed at BASELINE: everything survives.
        A2uiComponent atPassenger = A2uiPruner.prune(composed,
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        assertThat(A2uiPruner.countOfType(atPassenger, ComponentType.VIDEO)).isEqualTo(1);

        // Re-pruned for the driver display: the same projected surface, re-projected.
        A2uiComponent atDriver = A2uiPruner.prune(composed,
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED));
        assertThat(A2uiPruner.countOfType(atDriver, ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(atDriver, ComponentType.LIST)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(atDriver, ComponentType.TEXT_FIELD)).isEqualTo(0);
    }

    @Test
    public void theHandoffCaseCarriesADangerPruningDoesNotAddress() {
        // Button is glanceable, so `buy` arrives and only A2uiActionPolicy stops it. Guarded here
        // too, because A2uiHandoffTest's decision assertions are vacuous without this component.
        UseCaseCorpus.UseCase handoff = UseCaseCorpus.byId("handoff_to_driver");
        A2uiMessageProcessor p = processor();
        p.processAll(handoff.getMessages());
        A2uiComponent composed = p.getSurface(handoff.getId()).project("root");

        A2uiComponent atDriver = A2uiPruner.prune(
                composed, A2uiRestrictionPolicy.failClosedComponents());

        A2uiComponent buy = findById(atDriver, "buy");
        assertThat(buy).isNotNull();
        assertThat(buy.getType()).isEqualTo(ComponentType.BUTTON);
        assertThat(A2uiActionPolicy.classify(buy.getAction(), buy.getActionClass()))
                .isEqualTo(A2uiActionClass.CONSEQUENTIAL);
    }

    @Nullable
    private static A2uiComponent findById(@Nullable A2uiComponent node, String id) {
        if (node == null) {
            return null;
        }
        if (id.equals(node.getId())) {
            return node;
        }
        for (A2uiComponent child : node.getChildren()) {
            A2uiComponent found = findById(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    public void everyCaseDescribesItselfWithItsIdAndShape() {
        // These strings appear in logs and the case picker, so each must describe its own case.
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            String s = c.toString();
            assertThat(s).contains(c.getId());
            assertThat(s).contains("types");
            assertThat(s).contains("interesting at");
        }
    }

    /** Hosts title a surface from its root, so every case names itself in words, never its id. */
    @Test
    public void everyCaseRootCarriesAHumanTitleThatSurvivesTheFailClosedBudget() {
        for (UseCaseCorpus.UseCase c : UseCaseCorpus.all()) {
            A2uiMessageProcessor p = processor();
            p.processAll(c.getMessages());
            String title = p.getSurface(c.getId()).project("root").getTitle();

            assertThat(title).isNotEmpty();
            assertThat(title).doesNotContain("_");
            assertThat(title.length()).isAtMost(A2uiValueLimits.failClosed().getMaxStringLength());
        }
        A2uiMessageProcessor p = processor();
        p.processAll(UseCaseCorpus.byId("poi_discovery_order").getMessages());
        assertThat(p.getSurface("poi_discovery_order").project("root").getTitle())
                .isEqualTo("Coffee on the way");
        assertThat(p.getSurface("poi_discovery_order").readDataModel("/order/pickup"))
                .isEqualTo("Fifth House Coffee Atelier · SoHo");
    }
}
