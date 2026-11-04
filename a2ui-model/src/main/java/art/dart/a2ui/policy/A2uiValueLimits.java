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
import androidx.annotation.VisibleForTesting;

/**
 * The limits on values that a restriction reading implies: string length, item count, nesting
 * depth. Separate from {@link A2uiRestrictionPolicy}, which removes components; these rewrite
 * content in place.
 *
 * <p>The numbers are per-vehicle configuration delivered on the live reading, so {@link #from}
 * takes them as arguments. {@link #failClosed()}'s numbers are this fork's choice: the platform
 * defaults are a system API and could not be read.
 */
public final class A2uiValueLimits {

    /** No limit. Only reachable from a genuine unrestricted reading. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * Fail-closed limits: a Dart choice, deliberately tighter than common AAOS configuration,
     * because only too-loose is a safety defect.
     */
    private static final int FAIL_CLOSED_STRING_LENGTH = 60;
    private static final int FAIL_CLOSED_CONTENT_ITEMS = 6;
    private static final int FAIL_CLOSED_CONTENT_DEPTH = 2;

    private final int mMaxStringLength;
    private final int mMaxContentItems;
    private final int mMaxContentDepth;

    private A2uiValueLimits(int maxStringLength, int maxContentItems, int maxContentDepth) {
        mMaxStringLength = maxStringLength;
        mMaxContentItems = maxContentItems;
        mMaxContentDepth = maxContentDepth;
    }

    /** Longest permitted title/subtitle, or {@link #UNLIMITED}. */
    public int getMaxStringLength() {
        return mMaxStringLength;
    }

    /**
     * Most content items permitted in the whole projected tree, or {@link #UNLIMITED}. Cumulative,
     * not per container, or a surface could exceed it by splitting.
     */
    public int getMaxContentItems() {
        return mMaxContentItems;
    }

    /**
     * Deepest permitted nesting, counting the root as 1, or {@link #UNLIMITED}. AAOS defines depth
     * for browse hierarchies; this fork applies it to component nesting.
     */
    public int getMaxContentDepth() {
        return mMaxContentDepth;
    }

    /** True if nothing is limited — every field is {@link #UNLIMITED}. */
    public boolean isUnrestricted() {
        return mMaxStringLength == UNLIMITED
                && mMaxContentItems == UNLIMITED
                && mMaxContentDepth == UNLIMITED;
    }

    /** No limits. */
    @NonNull
    public static A2uiValueLimits unrestricted() {
        return new A2uiValueLimits(UNLIMITED, UNLIMITED, UNLIMITED);
    }

    /**
     * The conservative limits to apply when no reading can be trusted, kept apart from
     * {@code from(FULLY_RESTRICTED, ...)} so the two can diverge.
     */
    @NonNull
    public static A2uiValueLimits failClosed() {
        return new A2uiValueLimits(
                FAIL_CLOSED_STRING_LENGTH, FAIL_CLOSED_CONTENT_ITEMS, FAIL_CLOSED_CONTENT_DEPTH);
    }

    /**
     * Derives the limits from a live reading. Each applies only when its bit is set:
     * {@code LIMIT_STRING_LENGTH} for strings, {@code LIMIT_CONTENT} for item count and depth.
     *
     * @param activeRestrictions the mask from {@code CarUxRestrictions.getActiveRestrictions()}
     * @param vehicleMaxStringLength {@code getMaxRestrictedStringLength()}
     * @param vehicleMaxContentItems {@code getMaxCumulativeContentItems()}
     * @param vehicleMaxContentDepth {@code getMaxContentDepth()}
     */
    @NonNull
    public static A2uiValueLimits from(int activeRestrictions, int vehicleMaxStringLength,
            int vehicleMaxContentItems, int vehicleMaxContentDepth) {
        boolean limitStrings = isSet(activeRestrictions,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH);
        boolean limitContent = isSet(activeRestrictions,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT);
        return new A2uiValueLimits(
                limitStrings ? sanitise(vehicleMaxStringLength, FAIL_CLOSED_STRING_LENGTH)
                        : UNLIMITED,
                limitContent ? sanitise(vehicleMaxContentItems, FAIL_CLOSED_CONTENT_ITEMS)
                        : UNLIMITED,
                limitContent ? sanitise(vehicleMaxContentDepth, FAIL_CLOSED_CONTENT_DEPTH)
                        : UNLIMITED);
    }

    /**
     * A set bit with no usable number (0 or negative) falls back to the conservative value, never
     * to unlimited, which would honour the restriction by ignoring it.
     */
    private static int sanitise(int vehicleValue, int conservativeFallback) {
        return vehicleValue > 0 ? vehicleValue : conservativeFallback;
    }

    /**
     * Degenerate limits, for tests only: {@link #sanitise} makes a zero budget unreachable through
     * every public factory, which would leave {@code A2uiPruner.applyLimits}'s guard untested.
     */
    @VisibleForTesting
    @NonNull
    static A2uiValueLimits degenerateForTesting(
            int maxStringLength, int maxContentItems, int maxContentDepth) {
        return new A2uiValueLimits(maxStringLength, maxContentItems, maxContentDepth);
    }

    private static boolean isSet(int active, int flag) {
        return (active & flag) != 0;
    }

    @Override
    public String toString() {
        return "A2uiValueLimits{string=" + describe(mMaxStringLength)
                + ", items=" + describe(mMaxContentItems)
                + ", depth=" + describe(mMaxContentDepth) + '}';
    }

    private static String describe(int v) {
        return v == UNLIMITED ? "unlimited" : String.valueOf(v);
    }
}
