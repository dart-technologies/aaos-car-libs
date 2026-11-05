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

import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** The handoff, and the two tests that distinguish a real one from a convincing fake. */
public class A2uiHandoffTest {

    private static final Set<ComponentType> FULL =
            A2uiRestrictionPolicy.allowedComponents(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
    private static final Set<ComponentType> GLANCEABLE =
            A2uiRestrictionPolicy.failClosedComponents();

    /**
     * A display with nothing restricted: the passenger's, while moving. Endpoints here are
     * trustworthy, so decisions reflect the destination's reading, not the provenance gate.
     */
    private static A2uiHandoff.Endpoint passenger() {
        return A2uiHandoff.Endpoint.of(FULL, A2uiValueLimits.unrestricted(),
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, true);
    }

    /** A display the vehicle is restricting — the driver's, at speed. */
    private static A2uiHandoff.Endpoint driver() {
        return A2uiHandoff.Endpoint.of(GLANCEABLE, A2uiValueLimits.failClosed(),
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true);
    }

    /** The corpus's handoff case, projected from retained protocol state. */
    private static A2uiComponent composed() {
        UseCaseCorpus.UseCase c = UseCaseCorpus.byId("handoff_to_driver");
        A2uiMessageProcessor p = new A2uiMessageProcessor(UseCaseCorpus.CATALOG_ID,
                new HashSet<>(Collections.singletonList(UseCaseCorpus.VERSION)));
        p.processAll(c.getMessages());
        return p.getSurface(c.getId()).project("root");
    }

    @Test
    public void passengerToDriver_stripsWhatTheDriverMayNotSee() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(), driver());

        // Composed at BASELINE with Video, a List and a TextField...
        assertThat(A2uiPruner.countOfType(r.atSource(), ComponentType.VIDEO)).isEqualTo(1);
        assertThat(A2uiPruner.countOfType(r.atSource(), ComponentType.TEXT_FIELD)).isEqualTo(1);

        // ...none of which arrive at the driver's display.
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.LIST)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.TEXT_FIELD)).isEqualTo(0);
        assertThat(r.netComponentsRemoved()).isGreaterThan(0);
    }

    // The tree test: handing off toward a less restricted display separates re-projection from
    // forwarding, which cannot restore what the source removed.

    @Test
    public void driverToPassenger_RESTORES_whatTheSourceDisplayHadDropped() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), driver(), passenger());

        // The source shows none of these...
        assertThat(A2uiPruner.countOfType(r.atSource(), ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(r.atSource(), ComponentType.TEXT_FIELD)).isEqualTo(0);

        // ...and the destination shows all of them. Only re-projection can do this.
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.VIDEO)).isEqualTo(1);
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.LIST)).isEqualTo(1);
        assertThat(A2uiPruner.countOfType(r.atDestination(), ComponentType.TEXT_FIELD)).isEqualTo(1);

        // Negative: the move added components, which forwarding cannot report.
        assertThat(r.netComponentsRemoved()).isLessThan(0);
    }

    @Test
    public void theDestinationResultDoesNotDependOnTheSourceRestrictions() {
        // Same destination, two very different sources. If the destination's output varied with the
        // source at all, these would differ — which is the leak stated as an equality.
        A2uiComponent fromFree =
                A2uiHandoff.reproject(composed(), passenger(), driver()).atDestination();
        A2uiComponent fromRestricted =
                A2uiHandoff.reproject(composed(), driver(), driver()).atDestination();

        assertThat(fromRestricted.toString()).isEqualTo(fromFree.toString());
    }

    @Test
    public void handingOffToADisplayThatPermitsNothingYieldsNothing_ratherThanFailing() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(),
                A2uiHandoff.Endpoint.of(Collections.<ComponentType>emptySet(),
                        A2uiValueLimits.failClosed(),
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true));

        // An empty destination is a legitimate outcome, not an error. Callers must handle it — the
        // same contract A2uiPruner.prune already has.
        assertThat(r.atDestination()).isNull();
        assertThat(r.atSource()).isNotNull();

        // And it decides nothing there, rather than deciding REFUSE for components that are not on
        // that screen. A null tree has no controls, not refused controls.
        assertThat(r.decisionsAtDestination()).isEmpty();
        assertThat(r.redecided()).isEmpty();
    }

    @Test
    public void valueLimitsTravelWithTheDestination_notWithTheSurface() {
        // The destination's string budget applies, not the one the surface was composed under.
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(),
                A2uiHandoff.Endpoint.of(FULL,
                        A2uiValueLimits.from(
                                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH, 6, 0, 0),
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH, true));

        A2uiComponent atDest = r.atDestination();
        assertThat(atDest).isNotNull();
        assertLongestTitleAtMost(atDest, 6);
    }

    // Actions, re-decided at the destination. Button is glanceable, so `buy` (purchase) survives
    // the prune and arrives at the driver's display; only the action decision stops it.

    @Test
    public void aConsequentialActionIsCONFIRMatTheSource_andREFUSEDatTheDestination() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(), driver());

        // It really is on both screens — otherwise this test would be about pruning again.
        assertThat(componentIds(r.atSource())).contains("buy");
        assertThat(componentIds(r.atDestination())).contains("buy");

        assertThat(r.decisionsAtSource().get("buy")).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(r.decisionsAtDestination().get("buy"))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(r.redecided()).containsExactly("buy", "send");

        // Handoff is an explicit, host-owned action. It is consequential by identifier rather than
        // inferred from its visible label, so it is re-decided just like purchase.
        assertThat(r.decisionsAtSource().get("send")).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(r.decisionsAtDestination().get("send"))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    // The decision test: toward a less restricted display a carried REFUSE cannot become CONFIRM;
    // only re-deciding against the destination produces it.

    @Test
    public void driverToPassenger_RESTORES_anActionTheSourceDisplayREFUSED() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), driver(), passenger());

        assertThat(r.decisionsAtSource().get("buy")).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        assertThat(r.decisionsAtDestination().get("buy"))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(r.redecided()).contains("buy");
    }

    @Test
    public void theDestinationDecisionsDoNotDependOnTheSourceRestrictions() {
        // The equality form of the leak, for decisions rather than for the tree. Same destination,
        // two very different sources; anything carried across would show up as a difference here.
        A2uiHandoff.Result fromFree = A2uiHandoff.reproject(composed(), passenger(), driver());
        A2uiHandoff.Result fromRestricted = A2uiHandoff.reproject(composed(), driver(), driver());

        assertThat(fromRestricted.decisionsAtDestination())
                .containsExactlyEntriesIn(fromFree.decisionsAtDestination());
    }

    @Test
    public void everyInteractiveComponentAtTheDestinationHasADecision_andNothingElseDoes() {
        // Anti-vacuity: the key set must equal the interactive ids from an independent walk, and
        // that walk must find something.
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(), driver());

        Set<String> interactiveAtDestination = interactiveIds(r.atDestination());
        assertThat(interactiveAtDestination).isNotEmpty();
        assertThat(r.decisionsAtDestination().keySet())
                .containsExactlyElementsIn(interactiveAtDestination);

        Set<String> interactiveAtSource = interactiveIds(r.atSource());
        assertThat(interactiveAtSource).isNotEmpty();
        assertThat(r.decisionsAtSource().keySet())
                .containsExactlyElementsIn(interactiveAtSource);

        // The sets differ, so deciding the wrong tree would be detected.
        assertThat(interactiveAtDestination).isNotEqualTo(interactiveAtSource);
    }

    @Test
    public void aComponentPrunedAtTheDestinationCarriesNoDecisionThere() {
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(), driver());

        // `field` is a TextField carrying `compose`. The passenger display renders it and decides
        // it; the driver's prunes it away by type.
        assertThat(r.decisionsAtSource().get("field")).isEqualTo(A2uiActionPolicy.Decision.ALLOW);

        // Absent, not REFUSE: a host reading REFUSE would draw a disabled control the destination
        // never had.
        assertThat(r.decisionsAtDestination()).doesNotContainKey("field");
        assertThat(r.redecided()).doesNotContain("field");
    }

    @Test
    public void decisionMapsAreImmutableSnapshots() {
        // Same reason A2uiSurface returns deeply-immutable snapshots: a caller able to rewrite a
        // decision through the getter that advertises the opposite is a route to the policy.
        A2uiHandoff.Result r = A2uiHandoff.reproject(composed(), passenger(), driver());
        try {
            r.decisionsAtDestination().put("buy", A2uiActionPolicy.Decision.ALLOW);
            throw new AssertionError("the destination's decisions were writable by the caller");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    // -------------------------------------------------------------------------------------------

    private static void assertLongestTitleAtMost(A2uiComponent c, int max) {
        if (c.getTitle() != null) {
            assertThat(c.getTitle().length()).isAtMost(max);
        }
        for (A2uiComponent child : c.getChildren()) {
            assertLongestTitleAtMost(child, max);
        }
    }

    /** Every component id in a tree, found without asking the class under test. */
    private static Set<String> componentIds(A2uiComponent root) {
        Set<String> out = new LinkedHashSet<>();
        collect(root, false, out);
        return out;
    }

    /** Every id carrying an action, found without asking the class under test. */
    private static Set<String> interactiveIds(A2uiComponent root) {
        Set<String> out = new LinkedHashSet<>();
        collect(root, true, out);
        return out;
    }

    private static void collect(A2uiComponent node, boolean interactiveOnly, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.getId() != null && (!interactiveOnly || node.isInteractive())) {
            out.add(node.getId());
        }
        for (A2uiComponent child : node.getChildren()) {
            collect(child, interactiveOnly, out);
        }
    }
}
