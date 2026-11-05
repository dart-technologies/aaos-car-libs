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

import java.util.ArrayList;
import java.util.List;

/**
 * What a host has actually drawn, as a tree: the reachable set for voice. Shared by both hosts so
 * their voice surfaces cannot diverge from what they render.
 */
public final class A2uiVisibleTree {

    /**
     * The smallest content limit that can draw anything. Below this the host is not saying "show
     * fewer rows", it is saying nothing usable, and {@link #visibleRoot} fails closed.
     */
    @SuppressWarnings("MinMaxConstant")
    public static final int MIN_USABLE_CONTENT_LIMIT = 1;

    private A2uiVisibleTree() {
    }

    /**
     * Exactly what the driver can see: the rows {@link #render} would draw, as a tree. Descendants
     * of a row are dropped, since the host draws one line per row.
     *
     * <p>Returns null below {@link #MIN_USABLE_CONTENT_LIMIT}, so no control is reachable
     * ({@code dispatchByVoice} reads null as NO_MATCH); a throw would land in the recogniser
     * callback. A container root with children loses its action, voice phrase and action class,
     * because no row is drawn for it; a non-container root is the drawn row and keeps them.
     */
    @Nullable
    public static A2uiComponent visibleRoot(@Nullable A2uiComponent root, int contentLimit) {
        if (root == null) {
            return null;
        }
        if (contentLimit < MIN_USABLE_CONTENT_LIMIT) {
            // Fail closed.
            return null;
        }
        List<A2uiComponent> rows = renderableRows(root);
        if (rows.size() == 1 && rows.get(0) == root) {
            // The root is the drawn row here, so it keeps everything; render attaches this row's
            // click listener from the same action.
            return new A2uiComponent.Builder(root.getType()).copyPropertiesFrom(root).build();
        }
        A2uiComponent.Builder visible = new A2uiComponent.Builder(root.getType())
                .copyPropertiesFrom(root)
                // No row is drawn for a container root, so all three invoking fields are cleared
                // after the copy, not only the action.
                .setAction(null)
                .setVoicePhrase(null)
                .setActionClass(null);
        int shown = Math.min(rows.size(), contentLimit);
        for (int i = 0; i < shown; i++) {
            A2uiComponent row = rows.get(i);
            visible.addChild(
                    new A2uiComponent.Builder(row.getType()).copyPropertiesFrom(row).build());
        }
        return visible.build();
    }
    /**
     * The components that become rows: a container's children, or the root itself if it is not a
     * container. Both hosts call this.
     */
    @NonNull
    public static List<A2uiComponent> renderableRows(@Nullable A2uiComponent root) {
        List<A2uiComponent> rows = new ArrayList<>();
        if (root == null) {
            return rows;
        }
        if (isContainer(root.getType()) && root.hasChildren()) {
            rows.addAll(root.getChildren());
        } else {
            rows.add(root);
        }
        return rows;
    }

    /** Whether this type draws a container rather than a row of its own. */
    public static boolean isContainer(@NonNull ComponentType type) {
        return type == ComponentType.LIST
                || type == ComponentType.COLUMN
                || type == ComponentType.CARD;
    }
}
