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

import com.android.car.ui.CarUiText;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiVisibleTree;
import art.dart.a2ui.model.ComponentType;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a pruned A2UI surface as {@code car-ui-lib} list items, structured like
 * {@code CarAppRenderer} for comparison. No content limit here: {@code UxrContentLimiter} applies it
 * later, if the app asks. Runs in a local JVM.
 */
public final class CarUiRenderer {

    private CarUiRenderer() {
    }

    /** Flattens a pruned surface into list items. Empty when pruning removed everything. */
    @NonNull
    public static List<CarUiListItem> render(@Nullable A2uiComponent root) {
        return render(root, null);
    }

    /**
     * Flattens with interaction, applying Host A's rule: a REFUSE item gets no click listener and
     * says why. Both hosts consult {@link A2uiActionBinder}; neither decides itself.
     */
    @NonNull
    public static List<CarUiListItem> render(
            @Nullable A2uiComponent root, @Nullable A2uiActionBinder binder) {
        List<CarUiListItem> items = new ArrayList<>();
        for (A2uiComponent component : renderableRows(root)) {
            CarUiContentListItem item = new CarUiContentListItem(nativeActionFor(component));
            item.setTitle(component.getTitle() == null
                    ? component.getType().catalogName() : component.getTitle());
            // Body lines are a list, not a concatenation: the subtitle is already truncated to the
            // vehicle's string budget, and appending REFUSED_NOTE would exceed it.
            List<CarUiText> body = new ArrayList<>();
            if (component.getSubtitle() != null) {
                body.add(new CarUiText.Builder(component.getSubtitle()).build());
            }
            A2uiActionPolicy.Decision decision =
                    binder == null ? null : binder.decisionFor(component);
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                body.add(new CarUiText.Builder(A2uiActionBinder.REFUSED_NOTE).build());
                item.setEnabled(false);
            } else if (decision != null) {
                final A2uiComponent invoked = component;
                if (item.getAction() == CarUiContentListItem.Action.CHECK_BOX) {
                    item.setOnCheckedChangeListener(
                            (ignored, checked) -> binder.onInvoke(invoked));
                } else {
                    item.setOnItemClickedListener(ignored -> binder.onInvoke(invoked));
                }
                if (component.getVoicePhrase() != null
                        && !component.getVoicePhrase().trim().isEmpty()) {
                    body.add(new CarUiText.Builder(
                            A2uiActionBinder.voiceHint(component.getVoicePhrase())).build());
                }
            }
            if (!body.isEmpty()) {
                item.setBody(body);
            }
            items.add(item);
        }
        return items;
    }

    /**
     * Chooses native car-ui-lib row chrome without letting a sender choose behaviour. Pickers,
     * sliders and dates need a host-owned bounded registry, so they get a chevron into that flow.
     */
    @NonNull
    static CarUiContentListItem.Action nativeActionFor(@NonNull A2uiComponent component) {
        if (!component.isInteractive()) {
            return CarUiContentListItem.Action.NONE;
        }
        switch (component.getType()) {
            case CHECK_BOX:
                return CarUiContentListItem.Action.CHECK_BOX;
            case CARD:
            case LIST:
            case TABS:
            case TEXT_FIELD:
            case CHOICE_PICKER:
            case SLIDER:
            case DATE_TIME_INPUT:
            case VIDEO:
            case AUDIO_PLAYER:
                return CarUiContentListItem.Action.CHEVRON;
            case TEXT:
            case IMAGE:
            case ICON:
            case ROW:
            case COLUMN:
            case MODAL:
            case DIVIDER:
            case BUTTON:
            default:
                return CarUiContentListItem.Action.NONE;
        }
    }

    /**
     * Depth only, no count cap: what {@link #render} drew, no deeper. The activity caps the voice
     * tree itself, to {@code visibleRoot(pruned, N - hostRows)} under LIMIT_CONTENT.
     */
    @Nullable
    static A2uiComponent reachableForVoice(@Nullable A2uiComponent rendered) {
        return A2uiVisibleTree.visibleRoot(rendered, Integer.MAX_VALUE);
    }

    /** Delegates to {@link A2uiVisibleTree#renderableRows}, shared with Host A. */
    @NonNull
    static List<A2uiComponent> renderableRows(@Nullable A2uiComponent root) {
        return A2uiVisibleTree.renderableRows(root);
    }
}
