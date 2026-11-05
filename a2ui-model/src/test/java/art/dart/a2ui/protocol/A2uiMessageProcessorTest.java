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
package art.dart.a2ui.protocol;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The protocol layer. Cases that mirror upstream name their fixture from
 * {@code conformance/core/message_processor.yaml} at the pinned v1-candidate SHA.
 */
public class A2uiMessageProcessorTest {

    private static final String V = "v0.9";

    private static A2uiMessageProcessor processor() {
        return new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList(V)));
    }

    private static List<Map<String, Object>> one(String id, String type) {
        Map<String, Object> m = A2uiMessage.component(id, type);
        return Collections.singletonList(m);
    }

    // Surface lifecycle. Upstream: test_create_surface_basic, test_create_surface_duplicate_error,
    // test_delete_surface, test_update_data_model_unknown_surface_error.

    @Test
    public void createSurface_makesItAddressable() {
        A2uiMessageProcessor p = processor();

        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        assertThat(p.getSurface("s1")).isNotNull();
        assertThat(p.getSurface("s1").getCatalogId()).isEqualTo("automotive");
    }

    /** Upstream asserts IntegrityError for this; same category deliberately. */
    @Test
    public void duplicateSurfaceId_isAnIntegrityError() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.createSurface(V, "s1", "automotive")));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
    }

    @Test
    public void deleteSurface_removesIt() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        p.process(A2uiMessage.deleteSurface(V, "s1"));

        assertThat(p.getSurface("s1")).isNull();
    }

    /** Addressing an unknown surface is an error, not a no-op, for update, delete and data alike. */
    @Test
    public void addressingAnUnknownSurface_isAnIntegrityError() {
        A2uiMessageProcessor p = processor();

        for (A2uiMessage m : Arrays.asList(
                A2uiMessage.updateComponents(V, "ghost", one("a", "Text")),
                A2uiMessage.updateDataModel(V, "ghost", "/x", 1),
                A2uiMessage.deleteSurface(V, "ghost"))) {
            A2uiProtocolException e = assertThrows(A2uiProtocolException.class, () -> p.process(m));
            assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        }
    }

    @Test
    public void updateAfterDelete_isRefused() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.deleteSurface(V, "s1"));

        assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateComponents(V, "s1", one("a", "Text"))));
    }

    @Test
    public void processAllRollsBackEveryEarlierMessageWhenTheBatchFails() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "existing", "automotive"));
        p.process(A2uiMessage.updateDataModel(V, "existing", "/value", "before"));

        assertThrows(A2uiProtocolException.class, () -> p.processAll(Arrays.asList(
                A2uiMessage.updateDataModel(V, "existing", "/value", "after"),
                A2uiMessage.createSurface(V, "temporary", "automotive"),
                A2uiMessage.deleteSurface(V, "missing"))));

        assertThat(p.getSurface("existing").readDataModel("/value")).isEqualTo("before");
        assertThat(p.getSurface("temporary")).isNull();
    }

    // Catalog negotiation fails closed (this implementation's addition).

    @Test
    public void unnegotiatedCatalog_isRefused() {
        A2uiMessageProcessor p = processor();

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.createSurface(V, "s1", "some-other-catalog")));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
    }

    @Test
    public void unnegotiatedProtocolVersion_isRefused() {
        A2uiMessageProcessor p = processor();

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.createSurface("v0.8", "s1", "automotive")));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
    }

    /** A component outside the catalog is refused, not skipped. */
    @Test
    public void componentOutsideTheCatalog_isRefusedNotSkipped() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateComponents(V, "s1", one("x", "HologramDeck"))));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.CATALOG);
        assertThat(p.getSurface("s1").getComponents()).isEmpty();
    }

    // Data model. Upstream: test_update_data_model_nested_pointer.

    @Test
    public void updateDataModel_writesAndReadsAJsonPointer() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        p.process(A2uiMessage.updateDataModel(V, "s1", "/user/name", "Alice"));

        assertThat(p.getSurface("s1").readDataModel("/user/name")).isEqualTo("Alice");
    }

    @Test
    public void dataModelPointerThroughANonObject_isMalformed() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateDataModel(V, "s1", "/user", "not-an-object"));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateDataModel(V, "s1", "/user/name", "Alice")));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
    }

    @Test
    public void updateComponents_mergesPropertiesRatherThanReplacing() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> first = A2uiMessage.component("t", "Text");
        first.put("title", "one");
        first.put("subtitle", "keep me");
        p.process(A2uiMessage.updateComponents(V, "s1", Collections.singletonList(first)));

        Map<String, Object> second = A2uiMessage.component("t", "Text");
        second.put("title", "two");
        p.process(A2uiMessage.updateComponents(V, "s1", Collections.singletonList(second)));

        Map<String, Object> merged = p.getSurface("s1").getComponents().get("t");
        assertThat(merged.get("title")).isEqualTo("two");
        assertThat(merged.get("subtitle")).isEqualTo("keep me");
    }

    // Projection: the seam between protocol state and what a host draws.

    @Test
    public void projectionResolvesDataPointers() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> t = A2uiMessage.component("t", "Text");
        t.put("title", Collections.singletonMap("path", "/now/title"));
        p.process(A2uiMessage.updateComponents(V, "s1", Collections.singletonList(t)));
        p.process(A2uiMessage.updateDataModel(V, "s1", "/now/title", "Currently playing"));

        A2uiComponent projected = p.getSurface("s1").project("t");

        assertThat(projected.getTitle()).isEqualTo("Currently playing");
    }

    /** Content changes without the components being resent. */
    @Test
    public void aDataModelUpdateChangesTheProjectionWithoutResendingComponents() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> t = A2uiMessage.component("t", "Text");
        t.put("title", Collections.singletonMap("path", "/now/title"));
        p.process(A2uiMessage.updateComponents(V, "s1", Collections.singletonList(t)));
        p.process(A2uiMessage.updateDataModel(V, "s1", "/now/title", "First"));
        assertThat(p.getSurface("s1").project("t").getTitle()).isEqualTo("First");

        p.process(A2uiMessage.updateDataModel(V, "s1", "/now/title", "Second"));

        assertThat(p.getSurface("s1").project("t").getTitle()).isEqualTo("Second");
    }

    @Test
    public void aCyclicComponentGraph_isRefused() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> a = A2uiMessage.component("a", "Column");
        a.put("children", Collections.singletonList("b"));
        Map<String, Object> b = A2uiMessage.component("b", "Column");
        b.put("children", Collections.singletonList("a"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(a, b)));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.getSurface("s1").project("a"));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
    }

    // Actions: the round trip back toward the agent.

    /** A reader whose answers can change between a dispatch and its confirmation. */
    private static final class MutableReader implements A2uiMessageProcessor.RestrictionReader {
        int active = A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
        boolean trustworthy = true;

        @Override public int activeRestrictions() { return active; }
        @Override public boolean isTrustworthy() { return trustworthy; }
    }

    private static Map<String, Object> actionable(String id, String action) {
        Map<String, Object> m = A2uiMessage.component(id, "Button");
        m.put("action", action);
        return m;
    }

    /** A surface whose components declare the actions these tests dispatch; ownership is checked. */
    private static A2uiMessageProcessor withSurface(MutableReader reader) {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(
                actionable("play", "pause"),
                actionable("buy", "purchase"),
                actionable("del", "delete"))));
        p.setRestrictionReader(reader);
        return p;
    }

    @Test
    public void dispatchAction_reachesTheSink() {
        MutableReader reader = new MutableReader();
        A2uiMessageProcessor p = withSurface(reader);
        String[] seen = new String[3];
        p.setActionSink((surfaceId, componentId, action, payload) -> {
            seen[0] = surfaceId; seen[1] = componentId; seen[2] = action;
        });

        // A known-safe name: an unknown one would be classified consequential and refused.
        assertThat(p.dispatchAction("s1", "play", "pause", null))
                .isEqualTo(A2uiActionPolicy.Decision.ALLOW);

        assertThat(seen).asList().containsExactly("s1", "play", "pause").inOrder();
    }

    @Test
    public void dispatchAction_withNoReaderConfigured_failsClosed() {
        // The case a host hits by forgetting to wire the reader up. If the default were permissive
        // the symptom would be that everything works.
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1",
                Arrays.asList(actionable("buy", "purchase"))));
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void dispatchAction_doesNotReachTheSinkWhenRefused() {
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchAction("s1", "del", "delete", null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void dispatchAction_doesNotReachTheSinkWhenConfirmationIsRequired() {
        // CONFIRM must not run the action. A dispatch that both asks and acts has asked nothing.
        MutableReader reader = new MutableReader();
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void confirmAction_runsWhatWasConfirmed() {
        MutableReader reader = new MutableReader();
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);
        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);

        p.confirmAction("s1", "buy", "purchase", null, null);

        assertThat(reached[0]).isTrue();
    }

    @Test
    public void confirmAction_refusesWhenTheVehicleStartedMovingAfterTheDialogWasShown() {
        // Tapped "purchase" while parked, confirmed after pulling away: the decision is retaken at
        // confirmation.
        MutableReader reader = new MutableReader();
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);
        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);

        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;

        assertThat(p.confirmAction("s1", "buy", "purchase", null, null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void confirmAction_refusesWhenTheReadingBecameUntrustworthy() {
        // Car service dying between the dialog and the answer is the same problem wearing a
        // different hat: what is known about the vehicle at the moment of acting is nothing.
        MutableReader reader = new MutableReader();
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);
        p.dispatchAction("s1", "buy", "purchase", null);

        reader.trustworthy = false;

        assertThat(p.confirmAction("s1", "buy", "purchase", null, null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void agentCannotDowngradeAnActionThroughTheProcessor() {
        // The classify() rule, asserted at the layer that actually calls it — a downgrade accepted
        // here would bypass the policy no matter how correct A2uiActionPolicy is in isolation.
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchAction("s1", "del", "delete", null, A2uiActionClass.SAFE))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void dispatchAction_onAnUnknownSurface_isRefused() {
        A2uiMessageProcessor p = processor();

        assertThrows(A2uiProtocolException.class, () -> p.dispatchAction("ghost", "c", "click", null));
    }

    // The policy applies to the projection, which is why the two are separate.

    /**
     * Pruning removes a component from the projection while protocol state keeps it, so a
     * relaxation re-projects it with no further round trip.
     */
    @Test
    public void pruningAffectsTheProjectionButNotTheProtocolState() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> root = A2uiMessage.component("r", "Column");
        root.put("children", Arrays.asList("v", "t"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(
                root, A2uiMessage.component("v", "Video"), A2uiMessage.component("t", "Text"))));

        A2uiComponent full = p.getSurface("s1").project("r");
        A2uiComponent restricted = A2uiPruner.prune(full,
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO));

        assertThat(A2uiPruner.countOfType(full, ComponentType.VIDEO)).isEqualTo(1);
        assertThat(A2uiPruner.countOfType(restricted, ComponentType.VIDEO)).isEqualTo(0);
        // The agent's component is still there; only the drawing lost it.
        assertThat(p.getSurface("s1").getComponents()).containsKey("v");
        assertThat(A2uiPruner.countOfType(p.getSurface("s1").project("r"), ComponentType.VIDEO))
                .isEqualTo(1);
    }

    // Ownership: the surface decides what an action is; the caller only says which one to run.
    // Otherwise a consequential action could run as a safe one under a different name.

    @Test
    public void dispatchingAnActionTheComponentDoesNotDeclare_isRefused() {
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        // "buy" declares purchase. Calling it "pause" would classify it SAFE and run it.
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.dispatchAction("s1", "buy", "pause", null));

        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void confirmingAnActionTheComponentDoesNotDeclare_isRefused() {
        // The confirm path validates too. Checking only dispatch would leave the bypass open on the
        // side that actually performs the action.
        A2uiMessageProcessor p = withSurface(new MutableReader());
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThrows(A2uiProtocolException.class,
                () -> p.confirmAction("s1", "buy", "pause", null, null));

        assertThat(reached[0]).isFalse();
    }

    @Test
    public void dispatchingOnAComponentThatCarriesNoAction_isRefused() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1",
                Arrays.asList(A2uiMessage.component("label", "Text"))));
        p.setRestrictionReader(new MutableReader());

        assertThrows(A2uiProtocolException.class,
                () -> p.dispatchAction("s1", "label", "pause", null));
    }

    @Test
    public void dispatchingOnAnUnknownComponent_isRefused() {
        A2uiMessageProcessor p = withSurface(new MutableReader());

        assertThrows(A2uiProtocolException.class,
                () -> p.dispatchAction("s1", "ghost", "pause", null));
    }

    @Test
    public void theSurfacesDeclaredClassRaises_evenWhenTheCallerOmitsIt() {
        // A caller that simply says nothing must not thereby get the milder answer. Downgrade by
        // silence is the cheapest kind to perform and the hardest to notice at a call site.
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> declared = actionable("odd", "pause");   // host table says SAFE
        declared.put("actionClass", "DESTRUCTIVE");                  // the surface disagrees
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(declared)));
        p.setRestrictionReader(new MutableReader());                 // BASELINE, trustworthy

        // SAFE alone would be ALLOW. The surface's DESTRUCTIVE makes it CONFIRM.
        assertThat(p.dispatchAction("s1", "odd", "pause", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
    }

    // Atomicity and encapsulation at the protocol boundary.

    @Test
    public void aRejectedComponentUpdateChangesNothing() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        // Valid first, invalid second. The old code committed the first and then threw.
        assertThrows(A2uiProtocolException.class, () -> p.process(A2uiMessage.updateComponents(
                V, "s1", Arrays.asList(
                        A2uiMessage.component("good", "Text"),
                        A2uiMessage.component("bad", "Hologram")))));

        // "Rejected" and "unchanged" must mean the same thing, or a refusal is not safe to retry.
        assertThat(p.getSurface("s1").getComponents()).isEmpty();
    }

    @Test
    public void aRejectedUpdateDoesNotPartiallyOverwriteExistingComponents() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> before = A2uiMessage.component("row", "Text");
        before.put("title", "original");
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(before)));

        Map<String, Object> overwrite = A2uiMessage.component("row", "Text");
        overwrite.put("title", "clobbered");
        assertThrows(A2uiProtocolException.class, () -> p.process(A2uiMessage.updateComponents(
                V, "s1", Arrays.asList(overwrite, A2uiMessage.component("bad", "Hologram")))));

        assertThat(p.getSurface("s1").getComponents().get("row").get("title"))
                .isEqualTo("original");
    }

    @Test
    public void componentsReturnedFromTheSurfaceCannotBeWrittenThrough() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1",
                Arrays.asList(actionable("buy", "purchase"))));

        // A shallow unmodifiable view would let this rewrite protocol state, including the action
        // that ownership is decided from.
        Map<String, Object> inner = p.getSurface("s1").getComponents().get("buy");
        assertThrows(UnsupportedOperationException.class, () -> inner.put("action", "play"));

        assertThat(p.getSurface("s1").requireDeclaredAction("buy")).isEqualTo("purchase");
    }

    @Test
    public void mutatingTheSENTMessageAfterwardsDoesNotChangeTheSurface() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> sent = actionable("buy", "purchase");
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("path", "/a");
        sent.put("title", nested);
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(sent)));

        // The sender keeps a reference to what it sent and edits it. Without a deep copy on ingest
        // this changes the surface with no message and no validation.
        sent.put("action", "play");
        nested.put("path", "/b");

        assertThat(p.getSurface("s1").requireDeclaredAction("buy")).isEqualTo("purchase");
        Object title = p.getSurface("s1").getComponents().get("buy").get("title");
        assertThat(((Map<?, ?>) title).get("path")).isEqualTo("/a");
    }

    @Test
    public void theDataModelIsImmutableAllTheWayDown() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("name", "Ada");
        p.process(A2uiMessage.updateDataModel(V, "s1", "/user", value));

        Map<String, Object> model = p.getSurface("s1").getDataModel();
        assertThrows(UnsupportedOperationException.class, () -> model.put("x", 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) model.get("user");
        assertThrows(UnsupportedOperationException.class, () -> user.put("name", "Grace"));
    }

    // Voice as an input mode, not a privilege: voice and touch reach identical decisions.

    private static Map<String, Object> speakable(String id, String action, String title) {
        Map<String, Object> m = actionable(id, action);
        m.put("title", title);
        return m;
    }

    private static A2uiMessageProcessor withSpeakableSurface(MutableReader reader) {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> root = A2uiMessage.component("root", "Column");
        root.put("children", Arrays.asList("play", "buy"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(
                root,
                speakable("play", "play", "Play"),
                speakable("buy", "purchase", "Buy album"))));
        p.setRestrictionReader(reader);
        return p;
    }

    @Test
    public void aSafeActionCanBeSpoken() {
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSpeakableSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchByVoice("s1", "Play", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.DISPATCHED);
        assertThat(reached[0]).isTrue();
    }

    @Test
    public void voiceIsNotABypassForARefusedAction() {
        // The headline. Same vehicle state, same control, both input modes: both refuse.
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSpeakableSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(p.dispatchByVoice("s1", "buy album", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.REFUSED_BY_POLICY);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void voiceRaisesTheSameConfirmationTouchDoes() {
        A2uiMessageProcessor p = withSpeakableSurface(new MutableReader());   // BASELINE
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchByVoice("s1", "buy album", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.CONFIRMATION_REQUIRED);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void voiceResultReturnsTheExactMatchAndUsesTheDestinationReader() {
        MutableReader processorReader = new MutableReader();
        processorReader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSpeakableSurface(processorReader);
        MutableReader destinationReader = new MutableReader();
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        A2uiMessageProcessor.VoiceDispatchResult result = p.dispatchByVoiceForResult(
                "s1", "buy album", p.getSurface("s1").project("root"), destinationReader);

        assertThat(result.getOutcome())
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.CONFIRMATION_REQUIRED);
        assertThat(result.getMatchedComponent().getId()).isEqualTo("buy");
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void voiceResultOmitsAMatchWhenResolutionIsAmbiguous() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> root = A2uiMessage.component("root", "Column");
        root.put("children", Arrays.asList("a", "b"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(root,
                speakable("a", "play", "go"), speakable("b", "pause", "go"))));

        A2uiMessageProcessor.VoiceDispatchResult result = p.dispatchByVoiceForResult(
                "s1", "go", p.getSurface("s1").project("root"), new MutableReader());

        assertThat(result.getOutcome()).isEqualTo(A2uiMessageProcessor.VoiceOutcome.AMBIGUOUS);
        assertThat(result.getMatchedComponent()).isNull();
    }

    @Test
    public void matchingIgnoresCaseAndSurroundingSpace() {
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSpeakableSurface(reader);

        assertThat(p.dispatchByVoice("s1", "  PLAY  ", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.DISPATCHED);
    }

    @Test
    public void anUnmatchedUtteranceRunsNothing() {
        // A misheard utterance must be NO_MATCH, never a near-miss.
        A2uiMessageProcessor p = withSpeakableSurface(new MutableReader());
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        assertThat(p.dispatchByVoice("s1", "buy albums please", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        assertThat(p.dispatchByVoice("s1", "", p.getSurface("s1").project("root"))).isEqualTo(
                A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        assertThat(p.dispatchByVoice("s1", null, p.getSurface("s1").project("root"))).isEqualTo(
                A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void anAmbiguousUtteranceRunsNothing_ratherThanPickingTheFirst() {
        // Picking one would make the choice depend on the order an agent happened to send its
        // components in — the sort of thing that works in every demo and then buys an album.
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> ambiguousRoot = A2uiMessage.component("root", "Column");
        ambiguousRoot.put("children", Arrays.asList("a", "b"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(
                ambiguousRoot,
                speakable("a", "play", "Go"),
                speakable("b", "purchase", "Go"))));
        p.setRestrictionReader(new MutableReader());
        boolean[] reached = {false};
        p.setActionSink((x, y, z, w) -> reached[0] = true);

        assertThat(p.dispatchByVoice("s1", "go", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.AMBIGUOUS);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void aNonInteractiveComponentCannotBeSpokenInto() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> label = A2uiMessage.component("label", "Text");
        label.put("title", "Now playing");
        Map<String, Object> labelRoot = A2uiMessage.component("root", "Column");
        labelRoot.put("children", Arrays.asList("label"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(labelRoot, label)));
        p.setRestrictionReader(new MutableReader());

        // A Text with a title is not a control, so its title is not a phrase.
        assertThat(p.dispatchByVoice("s1", "now playing", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
    }

    @Test
    public void anExplicitVoicePhraseOverridesTheTitle() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> c = speakable("buy", "purchase", "Buy album");
        c.put("voicePhrase", "purchase this");
        Map<String, Object> phraseRoot = A2uiMessage.component("root", "Column");
        phraseRoot.put("children", Arrays.asList("buy"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(phraseRoot, c)));
        p.setRestrictionReader(new MutableReader());

        assertThat(p.dispatchByVoice("s1", "purchase this", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.CONFIRMATION_REQUIRED);
        assertThat(p.dispatchByVoice("s1", "Buy album", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
    }

    @Test
    public void aPrunedAwayControlIsNotVoiceInvocable() {
        // Voice searches only what is rendered, so a control pruned from the screen is not
        // speakable.
        MutableReader reader = new MutableReader();
        reader.active = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiMessageProcessor p = withSpeakableSurface(reader);
        boolean[] reached = {false};
        p.setActionSink((a, b, c, d) -> reached[0] = true);

        A2uiComponent rendered = A2uiPruner.prune(p.getSurface("s1").project("root"),
                A2uiRestrictionPolicy.failClosedComponents());

        // "play" is a Button and survives; prove the fixture is still speakable at all...
        assertThat(p.dispatchByVoice("s1", "play", rendered))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.DISPATCHED);

        // ...then prune it away entirely and confirm voice cannot reach it.
        A2uiComponent nothing = A2uiPruner.prune(p.getSurface("s1").project("root"),
                java.util.Collections.<ComponentType>emptySet());
        reached[0] = false;
        assertThat(p.dispatchByVoice("s1", "play", nothing))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        assertThat(reached[0]).isFalse();
    }

    @Test
    public void withNothingRenderedNothingIsSpeakable() {
        // The fail-closed reading of "the host did not say what it rendered".
        A2uiMessageProcessor p = withSpeakableSurface(new MutableReader());

        assertThat(p.dispatchByVoice("s1", "play", null))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
    }

    @Test
    public void anOrphanedControlIsNotVoiceInvocable() {
        // A component never attached to the root renders nowhere, so it is not speakable.
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        Map<String, Object> root = A2uiMessage.component("root", "Column");
        root.put("children", Arrays.asList("play"));
        p.process(A2uiMessage.updateComponents(V, "s1", Arrays.asList(
                root,
                speakable("play", "play", "Play"),
                speakable("orphan", "purchase", "Buy album"))));
        p.setRestrictionReader(new MutableReader());

        assertThat(p.dispatchByVoice("s1", "buy album", p.getSurface("s1").project("root")))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
    }
}
