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
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;

import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;
import art.dart.a2ui.protocol.A2uiDisplayBinder;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Host B's per-display wiring through {@code A2uiCarUiActivity.renderFor}, the whole per-display
 * pipeline as one static call: the rows a display shows must be decided by that display's scope,
 * not the processor's own reader.
 */
public class A2uiHostWiringTest {

    @Test
    public void compactStatusKeepsSyntheticAndUntrustedReadingsHonest() {
        assertThat(A2uiCarUiActivity.compactStatus(
                CarUxRestrictionsUtil.RestrictionsSource.DEMO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))
                .isEqualTo("DEMO · AVAILABLE");
        assertThat(A2uiCarUiActivity.compactStatus(
                CarUxRestrictionsUtil.RestrictionsSource.DEMO,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED))
                .isEqualTo("DEMO · FULLY RESTRICTED");
        assertThat(A2uiCarUiActivity.compactStatus(
                CarUxRestrictionsUtil.RestrictionsSource.NEVER_DELIVERED,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))
                .isEqualTo("SAFE MODE · WAITING");
        assertThat(A2uiCarUiActivity.compactStatus(
                CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE))
                .isEqualTo("SAFE MODE · OFFLINE");
    }

    @Test
    public void hospitalityHeadingsAreHostOwnedAndSurfaceSpecific() {
        assertThat(A2uiCarUiActivity.surfaceHeading("poi_discovery_order"))
                .isEqualTo("FIFTH HOUSE · PICKUP");
        assertThat(A2uiCarUiActivity.surfaceHeading("big_buck_bunny"))
                .isEqualTo("FIFTH HOUSE · CITY NOTE");
        assertThat(A2uiCarUiActivity.surfaceHeading("playback_settings"))
                .isEqualTo("A2UI · PLAYBACK SETTINGS");
        assertThat(A2uiCarUiActivity.toolbarTitleFor("poi_discovery_order", "Showcase"))
                .isEqualTo("Fifth House · NYC Concierge");
        assertThat(A2uiCarUiActivity.toolbarTitleFor("now_playing", "Showcase"))
                .isEqualTo("Showcase");
    }

    /** A reader whose answers can change between calls — the live half of a DisplayScope. */
    private static final class Reader implements A2uiMessageProcessor.RestrictionReader {
        int mActive;
        boolean mTrustworthy = true;

        Reader(int active) {
            mActive = active;
        }

        @Override
        public int activeRestrictions() {
            return mActive;
        }

        @Override
        public boolean isTrustworthy() {
            return mTrustworthy;
        }
    }

    private static final class Recorder implements A2uiDisplayBinder.Outcome {
        final List<A2uiActionPolicy.Decision> mDecisions = new ArrayList<>();

        @Override
        public void onDecided(A2uiComponent component, A2uiActionPolicy.Decision decision) {
            mDecisions.add(decision);
        }
    }

    private static A2uiMessageProcessor processor() {
        A2uiMessageProcessor p = DemoTrace.newProcessor();
        p.processAll(DemoTrace.messages());
        return p;
    }

    private static A2uiComponent projected(A2uiMessageProcessor p) {
        return p.getSurface(DemoTrace.SURFACE_ID).project(DemoTrace.ROOT_ID);
    }

    /** A display with nothing restricted. */
    private static A2uiCarUiActivity.DisplayScope free(Reader reader) {
        return new A2uiCarUiActivity.DisplayScope(
                A2uiRestrictionPolicy.allowedComponents(
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE),
                A2uiValueLimits.unrestricted(), reader, 0);
    }

    /** A display the vehicle is restricting. */
    private static A2uiCarUiActivity.DisplayScope restricted(Reader reader) {
        return new A2uiCarUiActivity.DisplayScope(
                A2uiRestrictionPolicy.failClosedComponents(),
                A2uiValueLimits.failClosed(), reader, 4);
    }

    /** The row for a component, found by the title the demo trace gives it. */
    private static CarUiContentListItem row(List<CarUiListItem> rows, String title) {
        for (CarUiListItem row : rows) {
            if (row instanceof CarUiContentListItem) {
                CarUiContentListItem item = (CarUiContentListItem) row;
                if (item.getTitle() != null
                        && title.contentEquals(item.getTitle().getPreferredText())) {
                    return item;
                }
            }
        }
        return null;
    }

    // The rows a display shows were decided by that display.

    @Test
    public void aRestrictedDisplayWithholdsTheListenerFromAConsequentialControl() {
        A2uiMessageProcessor p = processor();
        A2uiCarUiActivity.Rendered r = A2uiCarUiActivity.renderFor(projected(p),
                restricted(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)),
                p, DemoTrace.SURFACE_ID, new Recorder());

        CarUiContentListItem buy = row(r.mRows, "Buy album");
        assertThat(buy).isNotNull();
        assertThat(buy.getOnClickListener()).isNull();

        // A safe control on the same display keeps its listener, so blanket refusal fails.
        CarUiContentListItem play = row(r.mRows, "Play");
        assertThat(play).isNotNull();
        assertThat(play.getOnClickListener()).isNotNull();
    }

    @Test
    public void anUnrestrictedDisplayOffersTheSameControl() {
        A2uiMessageProcessor p = processor();
        A2uiCarUiActivity.Rendered r = A2uiCarUiActivity.renderFor(projected(p),
                free(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)),
                p, DemoTrace.SURFACE_ID, new Recorder());

        CarUiContentListItem buy = row(r.mRows, "Buy album");
        assertThat(buy).isNotNull();
        assertThat(buy.getOnClickListener()).isNotNull();
    }

    // The processor's own reader says BASELINE (display 0); the scope says FULLY_RESTRICTED (the
    // destination). Building the binder from the processor would offer the control.

    @Test
    public void theSCOPEsReaderDecides_notTheProcessorsOwn() {
        A2uiMessageProcessor p = processor();
        p.setRestrictionReader(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));

        // The allowed set is permissive so `buy` is present; only the reader is restrictive.
        A2uiCarUiActivity.DisplayScope destinationReaderOnly =
                new A2uiCarUiActivity.DisplayScope(
                        A2uiRestrictionPolicy.allowedComponents(
                                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE),
                        A2uiValueLimits.unrestricted(),
                        new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED), 4);

        A2uiCarUiActivity.Rendered r = A2uiCarUiActivity.renderFor(projected(p),
                destinationReaderOnly, p, DemoTrace.SURFACE_ID, new Recorder());

        CarUiContentListItem buy = row(r.mRows, "Buy album");
        assertThat(buy).isNotNull();          // present — pruning did NOT remove it
        assertThat(buy.getOnClickListener()).isNull();   // and still not offered
    }

    @Test
    public void invokingThroughTheRenderedListenerReReadsTheScopesDisplay() {
        // The listener the renderer attached must dispatch against the scope's reader, live. Drawn
        // while the destination was stationary, pressed after it started moving.
        A2uiMessageProcessor p = processor();
        p.setRestrictionReader(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        Recorder outcome = new Recorder();

        A2uiCarUiActivity.Rendered r = A2uiCarUiActivity.renderFor(projected(p),
                free(destination), p, DemoTrace.SURFACE_ID, outcome);
        CarUiContentListItem buy = row(r.mRows, "Buy album");
        assertThat(buy.getOnClickListener()).isNotNull();

        destination.mActive = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        buy.getOnClickListener().onClick(buy);

        assertThat(outcome.mDecisions).containsExactly(A2uiActionPolicy.Decision.REFUSE);
    }

    // Refresh: the same call at a different reading gives a different answer. The listener
    // registration that calls it is covered by A2uiHandoffLifecycleTest.

    @Test
    public void reRenderingAtANewReadingChangesWhatIsOffered() {
        A2uiMessageProcessor p = processor();
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        A2uiCarUiActivity.Rendered before = A2uiCarUiActivity.renderFor(projected(p),
                free(destination), p, DemoTrace.SURFACE_ID, new Recorder());
        assertThat(row(before.mRows, "Buy album").getOnClickListener()).isNotNull();

        // The vehicle starts moving. A re-render is what the destination's UXR listener triggers.
        destination.mActive = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
        A2uiCarUiActivity.Rendered after = A2uiCarUiActivity.renderFor(projected(p),
                restricted(destination), p, DemoTrace.SURFACE_ID, new Recorder());

        assertThat(row(after.mRows, "Buy album").getOnClickListener()).isNull();
        // The tree shrank too: the destination re-prunes as well as re-decides.
        assertThat(nodeCount(after.mTree)).isLessThan(nodeCount(before.mTree));
        assertThat(countOf(before.mTree, ComponentType.VIDEO)).isEqualTo(1);
        assertThat(countOf(after.mTree, ComponentType.VIDEO)).isEqualTo(0);
    }

    @Test
    public void renderForReturnsTheTreeItRendered_soCallersCannotReDeriveItAndDrift() {
        A2uiMessageProcessor p = processor();
        A2uiCarUiActivity.Rendered r = A2uiCarUiActivity.renderFor(projected(p),
                restricted(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)),
                p, DemoTrace.SURFACE_ID, new Recorder());

        assertThat(r.mTree).isNotNull();
        // The pairing logEnforcement depends on: one row per renderable component, same order.
        assertThat(CarUiRenderer.renderableRows(r.mTree)).hasSize(r.mRows.size());
    }

    private static int nodeCount(A2uiComponent node) {
        if (node == null) {
            return 0;
        }
        int n = 1;
        for (A2uiComponent child : node.getChildren()) {
            n += nodeCount(child);
        }
        return n;
    }

    private static int countOf(A2uiComponent node, ComponentType type) {
        if (node == null) {
            return 0;
        }
        int n = node.getType() == type ? 1 : 0;
        for (A2uiComponent child : node.getChildren()) {
            n += countOf(child, type);
        }
        return n;
    }

}
