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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "what changed" line shown after a restriction change, computed from the two trees the
 * policy actually produced (before and after) and the rows actually drawn. Nouns come from the
 * host (exact corpus labels, else the component type), never from agent text.
 */
final class ChangeSummary {

    /** Host-owned noun for a component, or null to fall back to its type. */
    interface Labeler {
        @Nullable
        String labelFor(@NonNull A2uiComponent component);
    }

    private static final int MAX_LABELS = 3;

    final List<String> mHidden;
    final List<String> mRestored;
    /** Host cards that left with the change, by host noun. */
    final List<String> mCollapsed;
    final int mRowsBefore;
    final int mRowsAfter;
    final boolean mAudioContinues;

    private ChangeSummary(List<String> hidden, List<String> restored, List<String> collapsed,
            int rowsBefore, int rowsAfter, boolean audioContinues) {
        mHidden = hidden;
        mRestored = restored;
        mCollapsed = collapsed;
        mRowsBefore = rowsBefore;
        mRowsAfter = rowsAfter;
        mAudioContinues = audioContinues;
    }

    /**
     * Diffs two pruned trees by component id. Only the topmost removed (or added) component of a
     * subtree is named: a pruned Modal takes its children with it, and naming them adds nothing.
     */
    @NonNull
    static ChangeSummary between(@Nullable A2uiComponent before, @Nullable A2uiComponent after,
            int rowsBefore, int rowsAfter, boolean audioContinues, @NonNull Labeler labeler) {
        return between(before, after, rowsBefore, rowsAfter, audioContinues, labeler,
                new ArrayList<>());
    }

    /** As above, plus the host cards (pickup, charging) the change took away. */
    @NonNull
    static ChangeSummary between(@Nullable A2uiComponent before, @Nullable A2uiComponent after,
            int rowsBefore, int rowsAfter, boolean audioContinues, @NonNull Labeler labeler,
            @NonNull List<String> collapsedHostCards) {
        return new ChangeSummary(topmostMissing(before, after, labeler),
                topmostMissing(after, before, labeler), new ArrayList<>(collapsedHostCards),
                rowsBefore, rowsAfter, audioContinues);
    }

    /** The one-line text, e.g. "Map & sources hidden · 7→3 rows · Audio continues". */
    @NonNull
    String text() {
        List<String> parts = new ArrayList<>();
        if (!mHidden.isEmpty()) parts.add(join(mHidden) + " hidden");
        if (!mCollapsed.isEmpty()) {
            parts.add(join(mCollapsed) + (mCollapsed.size() == 1 ? " card" : " cards")
                    + " collapsed");
        }
        if (!mRestored.isEmpty()) parts.add(join(mRestored) + " back");
        if (parts.isEmpty()) parts.add("Nothing hidden");
        parts.add(mRowsBefore + "→" + mRowsAfter + (mRowsAfter == 1 ? " row" : " rows"));
        if (mAudioContinues) parts.add("Audio continues");
        return String.join(" · ", parts);
    }

    /** A noun per component type, so the strip says something even for an unlabelled surface. */
    @NonNull
    static String typeNoun(@NonNull ComponentType type) {
        switch (type) {
            case VIDEO:
                return "Video";
            case IMAGE:
                return "Images";
            case TEXT_FIELD:
                return "Search";
            case CHOICE_PICKER:
            case SLIDER:
            case CHECK_BOX:
            case DATE_TIME_INPUT:
                return "Settings";
            case TABS:
                return "Tabs";
            case MODAL:
                return "Pop-ups";
            case LIST:
                return "Lists";
            case CARD:
                return "Cards";
            case AUDIO_PLAYER:
                return "Audio";
            case BUTTON:
                return "Controls";
            case ICON:
                return "Icons";
            case TEXT:
            case ROW:
            case COLUMN:
            case DIVIDER:
            default:
                return "Details";
        }
    }

    private static List<String> topmostMissing(@Nullable A2uiComponent from,
            @Nullable A2uiComponent in, Labeler labeler) {
        Map<String, A2uiComponent> present = new LinkedHashMap<>();
        index(in, present);
        Set<String> labels = new LinkedHashSet<>();
        collect(from, present, false, labeler, labels);
        return new ArrayList<>(labels);
    }

    private static void index(@Nullable A2uiComponent node, Map<String, A2uiComponent> out) {
        if (node == null) return;
        if (node.getId() != null) out.put(node.getId(), node);
        for (A2uiComponent child : node.getChildren()) index(child, out);
    }

    private static void collect(@Nullable A2uiComponent node, Map<String, A2uiComponent> present,
            boolean ancestorMissing, Labeler labeler, Set<String> labels) {
        if (node == null) return;
        boolean missing = node.getId() != null && !present.containsKey(node.getId());
        if (missing && !ancestorMissing) {
            String label = labeler.labelFor(node);
            labels.add(label != null ? label : typeNoun(node.getType()));
        }
        for (A2uiComponent child : node.getChildren()) {
            collect(child, present, ancestorMissing || missing, labeler, labels);
        }
    }

    /** "Map", "Map & sources", "Map, sources & artwork", then "+N more". */
    @NonNull
    static String join(@NonNull List<String> labels) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(labels.size(), MAX_LABELS); i++) {
            String label = labels.get(i);
            shown.add(i == 0 ? label : label.substring(0, 1).toLowerCase(Locale.ROOT)
                    + label.substring(1));
        }
        String text;
        if (shown.size() == 1) {
            text = shown.get(0);
        } else {
            text = String.join(", ", shown.subList(0, shown.size() - 1)) + " & "
                    + shown.get(shown.size() - 1);
        }
        int more = labels.size() - shown.size();
        return more > 0 ? text + " +" + more + " more" : text;
    }
}
