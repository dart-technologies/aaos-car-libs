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
package art.dart.a2ui.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.model.Action;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiVisibleTree;

import java.util.List;

/**
 * Renders a pruned A2UI surface as an {@code androidx.car.app} template. {@code prune} decides
 * which components may appear ("Scope note 1"); the host's
 * {@code ConstraintManager.getContentLimit} bounds how many, and the host rejects a template over
 * it, so both apply. The limit is a parameter so this is JVM-testable.
 */
public final class CarAppRenderer {

    /**
     * The floor this renderer will accept for a content limit. A host or stub answering 0 means a
     * missing answer, not a restriction level, and must not render as content.
     */
    // metalava api-lint: MinMaxConstant, suppressed: one item is the arithmetic floor of a usable
    // list, not a tuning value.
    @SuppressWarnings("MinMaxConstant")
    public static final int MIN_USABLE_CONTENT_LIMIT =
            art.dart.a2ui.policy.A2uiVisibleTree.MIN_USABLE_CONTENT_LIMIT;

    private CarAppRenderer() {
    }

    /** The note appended to a refused control, shared with Host B via {@link A2uiActionBinder}. */
    @VisibleForTesting
    static final String REFUSED_SUFFIX = A2uiActionBinder.REFUSED_NOTE;

    /**
     * Builds a list template from a pruned surface.
     *
     * @param root         the pruned root, or null if pruning removed everything
     * @param title        the surface title
     * @param contentLimit the host's limit for {@code CONTENT_LIMIT_TYPE_LIST}
     * @throws IllegalArgumentException if {@code contentLimit} is below
     *                                  {@link #MIN_USABLE_CONTENT_LIMIT}
     */
    @NonNull
    public static Template render(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit) {
        return render(root, title, contentLimit, null);
    }

    /**
     * Renders with interaction. A REFUSE row gets no click listener rather than one that rejects;
     * CONFIRM is offered, since it is only reachable when the vehicle is not restricting.
     */
    @NonNull
    public static Template render(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit,
            @Nullable A2uiActionBinder binder) {
        return render(root, title, contentLimit, binder, null);
    }

    /**
     * Renders with interaction and an opaque host-supplied header action, so voice gating is
     * decided only by the screen. A header action, not an action strip: the AAOS templates host
     * draws nothing for a strip, silently.
     *
     * @param headerAction null renders exactly as the four-argument overload does.
     */
    @NonNull
    public static Template render(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit,
            @Nullable A2uiActionBinder binder, @Nullable Action headerAction) {
        if (contentLimit < MIN_USABLE_CONTENT_LIMIT) {
            // Loudly: substituting a default would invent the host's constraint.
            throw new IllegalArgumentException(
                    "content limit " + contentLimit + " is below the usable floor "
                            + MIN_USABLE_CONTENT_LIMIT + "; the host did not supply a real limit");
        }

        List<A2uiComponent> rows = A2uiVisibleTree.renderableRows(root);
        ItemList.Builder items = new ItemList.Builder();
        int shown = Math.min(rows.size(), contentLimit);
        for (int i = 0; i < shown; i++) {
            A2uiComponent component = rows.get(i);
            Row.Builder row = new Row.Builder()
                    .setTitle(component.getTitle() == null
                            ? component.getType().catalogName() : component.getTitle());
            if (component.getSubtitle() != null) {
                row.addText(component.getSubtitle());
            }
            A2uiActionPolicy.Decision decision =
                    binder == null ? null : binder.decisionFor(component);
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                // Said out loud. A control that silently stops working reads as a broken app, and
                // the driver's next move is to press it again.
                row.addText(REFUSED_SUFFIX);
            } else if (decision != null) {
                final A2uiComponent invoked = component;
                if (component.getType() == art.dart.a2ui.model.ComponentType.CHECK_BOX
                        && decision == A2uiActionPolicy.Decision.ALLOW) {
                    // Native toggle without parsing agent prose into state; the change is reported
                    // through the binder like every other action.
                    row.setToggle(new Toggle.Builder(checked -> binder.onInvoke(invoked)).build());
                } else {
                    row.setOnClickListener(() -> binder.onInvoke(invoked));
                    if (component.getType() == art.dart.a2ui.model.ComponentType.CARD
                            || component.getType() == art.dart.a2ui.model.ComponentType.LIST
                            || component.getType() == art.dart.a2ui.model.ComponentType.TABS) {
                        row.setBrowsable(true);
                    }
                }
                if (component.getVoicePhrase() != null
                        && !component.getVoicePhrase().trim().isEmpty()) {
                    row.addText(A2uiActionBinder.voiceHint(component.getVoicePhrase()));
                }
            }
            items.addItem(row.build());
        }
        if (shown == 0) {
            // Distinct from an over-limit truncation, and it must say so. An empty surface after a
            // prune is the correct outcome of a fully-restricted vehicle, not an error.
            items.setNoItemsMessage(A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE);
        }

        Header.Builder header = new Header.Builder().setTitle(title);
        if (headerAction != null) {
            header.addEndHeaderAction(headerAction);
        }
        return new ListTemplate.Builder()
                .setHeader(header.build())
                .setSingleList(items.build())
                .build();
    }

    /**
     * How many items a surface would have to drop to satisfy a limit, or zero. Exposed so
     * truncation is reported, not just observed.
     */
    public static int overflowCount(@Nullable A2uiComponent root, int contentLimit) {
        return Math.max(0, A2uiVisibleTree.renderableRows(root).size() - contentLimit);
    }

    /**
     * Delegating alias for {@link art.dart.a2ui.policy.A2uiVisibleTree#visibleRoot}, kept for its
     * existing callers.
     */
    @Nullable
    public static A2uiComponent visibleRoot(@Nullable A2uiComponent root, int contentLimit) {
        return art.dart.a2ui.policy.A2uiVisibleTree.visibleRoot(root, contentLimit);
    }

}
