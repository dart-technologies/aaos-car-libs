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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Moves a surface between displays by re-projecting it from protocol state and re-deciding its
 * actions against each display's own state, never by forwarding a pruned tree or decisions.
 *
 * <p>Implements {@code test_automotive_surface_repruned_for_destination_display}. Pruning alone
 * does not stop actions (a Button carrying {@code purchase} is glanceable). {@code A2uiHandoffTest}
 * hands off from a restricted to an unrestricted display, which forwarding cannot pass.
 */
public final class A2uiHandoff {

    /**
     * Everything one display decides with: allowed types, value budget and vehicle state. Bundled
     * so a destination's allowed set cannot be paired with the source's restriction bits.
     */
    public static final class Endpoint {
        private final Set<ComponentType> mAllowed;
        private final A2uiValueLimits mLimits;
        private final int mActiveRestrictions;
        private final boolean mTrustworthy;

        private Endpoint(Set<ComponentType> allowed, A2uiValueLimits limits,
                int activeRestrictions, boolean trustworthy) {
            mAllowed = allowed;
            mLimits = limits;
            mActiveRestrictions = activeRestrictions;
            mTrustworthy = trustworthy;
        }

        /**
         * @param allowed the component types this display may show
         * @param limits this display's value budget
         * @param activeRestrictions this display's mask from {@code getActiveRestrictions()}
         * @param trustworthy whether that mask is a real ({@code DELIVERED}) reading; if not,
         *        {@link A2uiActionPolicy#decide} discards its bits
         */
        @NonNull
        public static Endpoint of(@NonNull Set<ComponentType> allowed,
                @NonNull A2uiValueLimits limits, int activeRestrictions, boolean trustworthy) {
            return new Endpoint(allowed, limits, activeRestrictions, trustworthy);
        }
    }

    /** What each display renders for the same surface, what it will permit, and what the move cost. */
    public static final class Result {
        private final A2uiComponent mAtSource;
        private final A2uiComponent mAtDestination;
        private final Map<String, A2uiActionPolicy.Decision> mDecisionsAtSource;
        private final Map<String, A2uiActionPolicy.Decision> mDecisionsAtDestination;

        Result(@Nullable A2uiComponent atSource, @Nullable A2uiComponent atDestination,
                Map<String, A2uiActionPolicy.Decision> decisionsAtSource,
                Map<String, A2uiActionPolicy.Decision> decisionsAtDestination) {
            mAtSource = atSource;
            mAtDestination = atDestination;
            mDecisionsAtSource = decisionsAtSource;
            mDecisionsAtDestination = decisionsAtDestination;
        }

        /** What the surface looks like where it was composed. */
        @Nullable
        public A2uiComponent atSource() {
            return mAtSource;
        }

        /** What the surface looks like where it is going. Null if nothing survives there. */
        @Nullable
        public A2uiComponent atDestination() {
            return mAtDestination;
        }

        /** What the source display will permit, by component id. */
        @NonNull
        public Map<String, A2uiActionPolicy.Decision> decisionsAtSource() {
            return mDecisionsAtSource;
        }

        /**
         * What the destination display will permit, by component id, for components it actually
         * renders: a pruned component has no entry, not a REFUSE entry.
         */
        @NonNull
        public Map<String, A2uiActionPolicy.Decision> decisionsAtDestination() {
            return mDecisionsAtDestination;
        }

        /**
         * Component ids present on both displays whose decision differs. Empty after a handoff
         * between displays in different states means decisions travelled instead of being retaken.
         */
        @NonNull
        public Set<String> redecided() {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, A2uiActionPolicy.Decision> e : mDecisionsAtSource.entrySet()) {
                A2uiActionPolicy.Decision atDest = mDecisionsAtDestination.get(e.getKey());
                if (atDest != null && atDest != e.getValue()) {
                    out.add(e.getKey());
                }
            }
            return Collections.unmodifiableSet(out);
        }

        /**
         * Components the destination removed relative to the source. Negative when the destination
         * is less restricted, which a forwarding implementation could never report.
         */
        public int netComponentsRemoved() {
            return count(mAtSource) - count(mAtDestination);
        }

        private static int count(@Nullable A2uiComponent c) {
            if (c == null) {
                return 0;
            }
            int n = 1;
            for (A2uiComponent child : c.getChildren()) {
                n += count(child);
            }
            return n;
        }
    }

    private A2uiHandoff() {
    }

    /**
     * Projects one surface for two displays independently, and decides its actions twice. No
     * overload omits the destination's vehicle state, so actions cannot skip re-decision.
     *
     * @param projected the surface projected from retained protocol state, never a pruned tree
     */
    @NonNull
    public static Result reproject(@NonNull A2uiComponent projected,
            @NonNull Endpoint source, @NonNull Endpoint destination) {
        A2uiComponent atSource = render(projected, source);
        A2uiComponent atDestination = render(projected, destination);
        return new Result(atSource, atDestination,
                decide(atSource, source),
                decide(atDestination, destination));
    }

    /** Prune by type, then bound by value. Both sides start from the same input tree. */
    @Nullable
    private static A2uiComponent render(A2uiComponent projected, Endpoint endpoint) {
        A2uiComponent pruned = A2uiPruner.prune(projected, endpoint.mAllowed);
        return pruned == null ? null : A2uiPruner.applyLimits(pruned, endpoint.mLimits);
    }

    /**
     * Decides one display's rendered tree against that same display. Pairing the destination's
     * tree with the source's state compiles; two mutations in check-gate-sensitivity.py guard it.
     */
    @NonNull
    private static Map<String, A2uiActionPolicy.Decision> decide(
            @Nullable A2uiComponent rendered, Endpoint endpoint) {
        Map<String, A2uiActionPolicy.Decision> out = new LinkedHashMap<>();
        collectDecisions(rendered, endpoint, out);
        return Collections.unmodifiableMap(out);
    }

    private static void collectDecisions(@Nullable A2uiComponent node, Endpoint endpoint,
            Map<String, A2uiActionPolicy.Decision> out) {
        if (node == null) {
            return;
        }
        if (node.isInteractive() && node.getId() != null) {
            // The same classify() both hosts' binders use, decided again per display.
            out.put(node.getId(), A2uiActionPolicy.decide(
                    A2uiActionPolicy.classify(node.getAction(), node.getActionClass()),
                    endpoint.mActiveRestrictions, endpoint.mTrustworthy));
        }
        for (A2uiComponent child : node.getChildren()) {
            collectDecisions(child, endpoint, out);
        }
    }
}
