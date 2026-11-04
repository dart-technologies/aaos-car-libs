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

import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The enforcement half of the handoff: {@code A2uiHandoffTest} proves the destination's decisions
 * are computed; this proves they are the ones that run.
 */
public class A2uiDisplayBinderTest {

    /** A reader whose answers can be changed between calls, which is the whole point. */
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

        A2uiActionPolicy.Decision last() {
            return mDecisions.get(mDecisions.size() - 1);
        }
    }

    private static A2uiMessageProcessor processorWithHandoffSurface() {
        UseCaseCorpus.UseCase c = UseCaseCorpus.byId("handoff_to_driver");
        A2uiMessageProcessor p = new A2uiMessageProcessor(UseCaseCorpus.CATALOG_ID,
                new HashSet<>(Collections.singletonList(UseCaseCorpus.VERSION)));
        p.processAll(c.getMessages());
        return p;
    }

    /** The consequential Button that survives every prune — see UseCaseCorpus.handoffToDriver. */
    private static A2uiComponent buy(A2uiMessageProcessor p) {
        A2uiComponent root = p.getSurface("handoff_to_driver").project("root");
        for (A2uiComponent child : root.getChildren()) {
            if ("buy".equals(child.getId())) {
                return child;
            }
        }
        throw new AssertionError("the corpus lost the `buy` component; this suite is now vacuous");
    }

    // One processor, one surface, one instant, two displays: they must disagree. Any wiring through
    // the processor's own reader gives both binders the same answer.

    @Test
    public void twoBindersOverOneProcessorDisagree_becauseTheirDisplaysDo() {
        A2uiMessageProcessor p = processorWithHandoffSurface();
        // The processor's OWN reader is the source display's, exactly as a host would leave it.
        Reader source = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        p.setRestrictionReader(source);
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);

        Recorder atSource = new Recorder();
        Recorder atDestination = new Recorder();
        A2uiDisplayBinder sourceBinder =
                new A2uiDisplayBinder(p, "handoff_to_driver", source, atSource);
        A2uiDisplayBinder destinationBinder =
                new A2uiDisplayBinder(p, "handoff_to_driver", destination, atDestination);

        A2uiComponent buy = buy(p);

        // What the renderer asks: the source offers a confirmation, the destination offers nothing.
        assertThat(sourceBinder.decisionFor(buy)).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(destinationBinder.decisionFor(buy)).isEqualTo(A2uiActionPolicy.Decision.REFUSE);

        // A touch on the destination must refuse even though the processor's reader would confirm.
        sourceBinder.onInvoke(buy);
        destinationBinder.onInvoke(buy);
        assertThat(atSource.last()).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        assertThat(atDestination.last()).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void invocationREREADSTheDestination_ratherThanReplayingTheDrawTimeDecision() {
        // The snapshot-is-not-authorization property, on the display axis. The renderer decided
        // this control was pressable; the destination started restricting before the finger landed.
        A2uiMessageProcessor p = processorWithHandoffSurface();
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        Recorder outcome = new Recorder();
        A2uiDisplayBinder binder =
                new A2uiDisplayBinder(p, "handoff_to_driver", destination, outcome);
        A2uiComponent buy = buy(p);

        assertThat(binder.decisionFor(buy)).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);

        destination.mActive = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;

        binder.onInvoke(buy);
        assertThat(outcome.last()).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void aConfirmationOnAHandedOffSurfaceIsReDecidedAgainstITSDisplay() {
        // Both axes at once, which is the case a handed-off dialog actually is: answered later
        // (time) on a screen that did not compose it (display).
        A2uiMessageProcessor p = processorWithHandoffSurface();
        p.setRestrictionReader(new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        A2uiDisplayBinder binder =
                new A2uiDisplayBinder(p, "handoff_to_driver", destination, new Recorder());
        A2uiComponent buy = buy(p);

        // The dialog goes up while the destination is stationary...
        assertThat(binder.decisionFor(buy)).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);

        // The destination starts moving; a confirmAction that fell back to the source would
        // complete the purchase.
        destination.mActive = A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;

        assertThat(binder.confirm(buy)).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void anUntrustworthyDestinationRefusesRegardlessOfItsBits() {
        // Provenance travels with the display too.
        A2uiMessageProcessor p = processorWithHandoffSurface();
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        destination.mTrustworthy = false;
        Recorder outcome = new Recorder();
        A2uiDisplayBinder binder =
                new A2uiDisplayBinder(p, "handoff_to_driver", destination, outcome);

        assertThat(binder.decisionFor(buy(p))).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
        binder.onInvoke(buy(p));
        assertThat(outcome.last()).isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void aNonInteractiveComponentHasNoDecision() {
        A2uiMessageProcessor p = processorWithHandoffSurface();
        A2uiDisplayBinder binder = new A2uiDisplayBinder(p, "handoff_to_driver",
                new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE), new Recorder());

        A2uiComponent root = p.getSurface("handoff_to_driver").project("root");
        A2uiComponent why = null;
        for (A2uiComponent child : root.getChildren()) {
            if ("why".equals(child.getId())) {
                why = child;
            }
        }
        assertThat(why).isNotNull();
        assertThat(binder.decisionFor(why)).isNull();
    }

    @Test
    public void onInvokeGoesThroughTheProcessor_soTheSinkStillSeesIt() {
        // onInvoke must go through the processor, or it loses the ownership check and the sink.
        A2uiMessageProcessor p = processorWithHandoffSurface();
        List<String> sink = new ArrayList<>();
        p.setActionSink((surfaceId, componentId, action, payload) -> sink.add(componentId));

        A2uiDisplayBinder binder = new A2uiDisplayBinder(p, "handoff_to_driver",
                new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE), new Recorder());

        A2uiComponent root = p.getSurface("handoff_to_driver").project("root");
        A2uiComponent send = null;
        for (A2uiComponent child : root.getChildren()) {
            if ("send".equals(child.getId())) {
                send = child;
            }
        }
        assertThat(send).isNotNull();

        binder.onInvoke(send);
        assertThat(sink).isEmpty();
        binder.confirm(send);
        assertThat(sink).containsExactly("send");
    }

    @Test
    public void ownershipIsStillValidatedAtTheDestination() {
        // The destination binder is not a way around classifyAgainstSurface. A component that does
        // not declare the action being dispatched is refused as an integrity error on any display.
        A2uiMessageProcessor p = processorWithHandoffSurface();
        Reader destination = new Reader(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        try {
            p.dispatchAction("handoff_to_driver", "buy", "play", null, null, destination);
            throw new AssertionError("dispatched an action the component does not declare");
        } catch (A2uiProtocolException expected) {
            assertThat(expected.getCategory()).isEqualTo(A2uiProtocolException.Category.INTEGRITY);
        }
    }

    @Test
    public void aBinderReportsTheReaderItSpeaksFor() {
        // Exposed for logging, where a one-reader-for-all bug would otherwise be invisible.
        A2uiMessageProcessor.RestrictionReader driving = reader(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED, true);
        A2uiMessageProcessor.RestrictionReader parked = reader(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, true);

        A2uiMessageProcessor p = new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList("v0.9")));
        A2uiDisplayBinder.Outcome ignored = (component, decision) -> { };
        assertThat(new A2uiDisplayBinder(p, "s1", driving, ignored).reader())
                .isSameInstanceAs(driving);
        assertThat(new A2uiDisplayBinder(p, "s1", parked, ignored).reader())
                .isSameInstanceAs(parked);
    }

    private static A2uiMessageProcessor.RestrictionReader reader(int active, boolean trustworthy) {
        return new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return active;
            }

            @Override
            public boolean isTrustworthy() {
                return trustworthy;
            }
        };
    }
}
