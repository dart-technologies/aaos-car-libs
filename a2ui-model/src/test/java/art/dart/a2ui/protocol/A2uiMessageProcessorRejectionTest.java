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

import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link A2uiMessageProcessor}'s refusals and defaults, which have no upstream counterpart, so they
 * stay out of {@link A2uiMessageProcessorTest}. Notably
 * {@link #aNullRestrictionReaderRestoresTheFailClosedOne}.
 */
public class A2uiMessageProcessorRejectionTest {

    private static final String V = "v0.9";

    private static A2uiMessageProcessor processor() {
        return new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList(V)));
    }

    /** A reader that says the vehicle is parked and means it. */
    private static A2uiMessageProcessor.RestrictionReader parked() {
        return new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE;
            }

            @Override
            public boolean isTrustworthy() {
                return true;
            }
        };
    }

    private static A2uiMessageProcessor withSurface(Map<String, Object>... components) {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1", java.util.Arrays.asList(components)));
        return p;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------------------------------
    // The fail-closed default, and the one route back to it.

    @Test
    public void aNullRestrictionReaderRestoresTheFailClosedOne() {
        // A consequential action: a safe one is allowed even when fully restricted, so it cannot
        // tell the two readers apart.
        A2uiMessageProcessor p = withSurface(
                props("id", "buy", "component", "Button", "action", "purchase"));

        p.setRestrictionReader(parked());
        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);

        // Clearing the reader must not keep trusting the last one.
        p.setRestrictionReader(null);
        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void aProcessorWithNoReaderAtAllIsAlreadyFailClosed() {
        // Same end state as above, reached by never wiring one up rather than by clearing it.
        A2uiMessageProcessor p = withSurface(
                props("id", "buy", "component", "Button", "action", "purchase"));
        assertThat(p.dispatchAction("s1", "buy", "purchase", null))
                .isEqualTo(A2uiActionPolicy.Decision.REFUSE);
    }

    @Test
    public void theFailClosedReaderIsNotMerelyStricter_aSafeActionStillRuns() {
        // Failing closed means the harshest reportable state, not refusing everything: pause is
        // allowed parked and driving alike.
        A2uiMessageProcessor p = withSurface(
                props("id", "pause", "component", "Button", "action", "pause"));
        assertThat(p.dispatchAction("s1", "pause", "pause", null))
                .isEqualTo(A2uiActionPolicy.Decision.ALLOW);
    }

    // ---------------------------------------------------------------------------------------
    // The surface map is a view, not a handle.

    @Test
    public void theSurfaceMapCannotBeWrittenThrough() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        Map<String, A2uiSurface> surfaces = p.getSurfaces();
        assertThat(surfaces.keySet()).containsExactly("s1");
        assertThrows(UnsupportedOperationException.class, () -> surfaces.remove("s1"));
        assertThat(p.getSurface("s1")).isNotNull();
    }

    // ---------------------------------------------------------------------------------------
    // Two declared action classes, and which one wins.

    @Test
    public void aSurfaceClassAndACallerClassMergeToTheMoreRestrictive() {
        A2uiMessageProcessor p = withSurface(props("id", "buy", "component", "Button",
                "action", "purchase", "actionClass", "DESTRUCTIVE"));
        p.setRestrictionReader(parked());

        // The caller says SAFE, the surface DESTRUCTIVE; the caller must not downgrade it.
        A2uiActionPolicy.Decision decision =
                p.dispatchAction("s1", "buy", "purchase", null, A2uiActionClass.SAFE);
        assertThat(decision).isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
    }

    @Test
    public void aCallerClassStricterThanTheSurfacesAlsoWins() {
        // Symmetry check: the merge is "most restrictive", not "the surface always wins". Without
        // this, an implementation that simply ignored the caller would pass the test above.
        A2uiMessageProcessor p = withSurface(props("id", "go", "component", "Button",
                "action", "open", "actionClass", "SAFE"));
        p.setRestrictionReader(parked());

        assertThat(p.dispatchAction("s1", "go", "open", null, A2uiActionClass.DESTRUCTIVE))
                .isEqualTo(A2uiActionPolicy.Decision.CONFIRM);
        // ...and with no caller class it is the surface's SAFE that applies.
        assertThat(p.dispatchAction("s1", "go", "open", null, null))
                .isEqualTo(A2uiActionPolicy.Decision.ALLOW);
    }

    // ---------------------------------------------------------------------------------------
    // Malformed messages.

    @Test
    public void aComponentEntryWithoutAStringIdIsMalformed() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        List<Map<String, Object>> bad =
                Collections.singletonList(props("id", 7, "component", "Text"));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateComponents(V, "s1", bad)));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
        // Pass 1 validates before pass 2 commits, so the surface is untouched.
        assertThat(p.getSurface("s1").getComponents()).isEmpty();
    }

    @Test
    public void aComponentEntryWithoutAStringComponentIsMalformed() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        List<Map<String, Object>> bad =
                Collections.singletonList(props("id", "t", "component", null));
        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateComponents(V, "s1", bad)));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
    }

    @Test
    public void oneBadEntryRejectsTheWholeBatch_ratherThanCommittingTheGoodOnes() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        List<Map<String, Object>> mixed = java.util.Arrays.asList(
                props("id", "good", "component", "Text"),
                props("id", 7, "component", "Text"));
        assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateComponents(V, "s1", mixed)));
        // "good" preceded the bad entry and must still not be there: a half-applied batch is a
        // surface neither side agrees on.
        assertThat(p.getSurface("s1").getComponents()).isEmpty();
    }

    @Test
    public void updateDataModelWithoutAPathIsMalformed() {
        A2uiMessageProcessor p = processor();
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));

        A2uiProtocolException e = assertThrows(A2uiProtocolException.class,
                () -> p.process(A2uiMessage.updateDataModel(V, "s1", null, "x")));
        assertThat(e.getCategory()).isEqualTo(A2uiProtocolException.Category.MALFORMED);
    }

    // ---------------------------------------------------------------------------------------
    // What a message says it is. These strings are what a protocol trace shows.

    @Test
    public void aMessageDescribesItselfByKindAndSurface() {
        assertThat(A2uiMessage.createSurface(V, "s1", "automotive").toString())
                .isEqualTo("CREATE_SURFACE(s1)");
        assertThat(A2uiMessage.deleteSurface(V, "s1").toString())
                .isEqualTo("DELETE_SURFACE(s1)");
    }

    @Test
    public void aDataModelMessageAlsoNamesThePathItWrites() {
        // Without the path, two updateDataModel messages to one surface are indistinguishable in a
        // trace -- which is precisely the trace someone reads when a value lands in the wrong slot.
        assertThat(A2uiMessage.updateDataModel(V, "s1", "/user/name", "ada").toString())
                .isEqualTo("UPDATE_DATA_MODEL(s1 /user/name)");
    }
}
