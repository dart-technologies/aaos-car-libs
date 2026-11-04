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
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Applies {@code action: prune} to a component tree. A disallowed component is removed with its
 * whole subtree; re-parenting a pruned Modal's children would show content meant to stay behind
 * an interaction.
 */
public final class A2uiPruner {

    private A2uiPruner() {
    }

    /**
     * Returns a copy of {@code component} containing only allowed types, or null if the component
     * itself is not allowed.
     */
    @Nullable
    public static A2uiComponent prune(
            @NonNull A2uiComponent component, @NonNull Set<ComponentType> allowed) {
        if (!allowed.contains(component.getType())) {
            return null;
        }
        A2uiComponent.Builder builder = new A2uiComponent.Builder(component.getType())
                .copyPropertiesFrom(component);
        for (A2uiComponent child : component.getChildren()) {
            A2uiComponent prunedChild = prune(child, allowed);
            if (prunedChild != null) {
                builder.addChild(prunedChild);
            }
        }
        return builder.build();
    }

    /**
     * Types that only arrange other components and do not consume the content-item budget; AAOS
     * counts browsable items, not layout nodes.
     */
    private static final Set<ComponentType> LAYOUT_ONLY = Collections.unmodifiableSet(
            EnumSet.of(ComponentType.ROW, ComponentType.COLUMN, ComponentType.DIVIDER));

    /** Ellipsis marking a truncated string. One BMP char, so it costs exactly one of the budget. */
    private static final String ELLIPSIS = "\u2026";
    /** {@code say “} plus the closing quote. */
    private static final int VOICE_HINT_OVERHEAD = 6;

    /**
     * Applies {@link A2uiValueLimits} to an already-pruned tree (prune first, or the budget is
     * spent on components about to be dropped). Every limit shows less: strings are truncated,
     * surplus items dropped, and subtrees below the depth limit cut, never flattened upward.
     *
     * @return the limited tree, or null if the limits exclude even the root
     */
    @Nullable
    public static A2uiComponent applyLimits(
            @NonNull A2uiComponent component, @NonNull A2uiValueLimits limits) {
        return limit(component, limits, 1, new int[]{0});
    }

    @Nullable
    private static A2uiComponent limit(
            A2uiComponent component, A2uiValueLimits limits, int depth, int[] itemsUsed) {
        if (depth > limits.getMaxContentDepth()) {
            return null;
        }
        boolean countsAsContent = !LAYOUT_ONLY.contains(component.getType());
        if (countsAsContent) {
            if (itemsUsed[0] >= limits.getMaxContentItems()) {
                return null;
            }
            itemsUsed[0]++;
        }

        // Copy first, then override the two fields this transform actually changes. Listing the
        // preserved fields by hand here is how an action silently disappears from a pruned tree.
        A2uiComponent.Builder builder = new A2uiComponent.Builder(component.getType())
                .copyPropertiesFrom(component)
                .setTitle(truncate(component.getTitle(), limits.getMaxStringLength()))
                .setSubtitle(truncate(component.getSubtitle(), limits.getMaxStringLength()))
                // voicePhrase is agent text shown as a "say ..." hint, so it is clamped too. The
                // declared phrase, not the effective one, which falls back to the (truncated) title.
                .setVoicePhrase(limitedVoicePhrase(component, limits.getMaxStringLength()));
        // Pre-order, so the budget is spent nearest the top and survivors do not depend on
        // traversal order.
        for (A2uiComponent child : component.getChildren()) {
            A2uiComponent limited = limit(child, limits, depth + 1, itemsUsed);
            if (limited != null) {
                builder.addChild(limited);
            }
        }
        return builder.build();
    }

    @Nullable
    private static String limitedVoicePhrase(A2uiComponent component, int maxStringLength) {
        if (maxStringLength == A2uiValueLimits.UNLIMITED) {
            return component.getDeclaredVoicePhrase();
        }
        if (!component.isInteractive()) {
            return truncate(component.getDeclaredVoicePhrase(), maxStringLength);
        }
        String phrase = component.getVoicePhrase();
        int phraseBudget = Math.max(0, maxStringLength - VOICE_HINT_OVERHEAD);
        return truncate(phrase, phraseBudget);
    }

    /** Truncates to {@code max} characters including the ellipsis. */
    @Nullable
    @VisibleForTesting
    static String truncate(@Nullable String text, int max) {
        if (text == null || max == A2uiValueLimits.UNLIMITED || text.length() <= max) {
            return text;
        }
        if (max <= 0) {
            return "";
        }
        if (max == 1) {
            return ELLIPSIS;
        }
        int keep = max - 1;
        // Never split a surrogate pair. A truncation that produces an unpaired surrogate turns a
        // long title into an invalid string, and emoji in media titles are ordinary, not exotic.
        if (Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        return text.substring(0, keep) + ELLIPSIS;
    }

    /**
     * Counts the components of a given type in a tree. Used by tests to assert a pruned type is
     * gone from every depth, not merely from the root's direct children.
     */
    public static int countOfType(@Nullable A2uiComponent component, @NonNull ComponentType type) {
        if (component == null) {
            return 0;
        }
        int count = component.getType() == type ? 1 : 0;
        for (A2uiComponent child : component.getChildren()) {
            count += countOfType(child, type);
        }
        return count;
    }
}
