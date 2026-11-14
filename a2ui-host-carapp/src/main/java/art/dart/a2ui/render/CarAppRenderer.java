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
import android.text.SpannableString;
import android.text.Spanned;

import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.Header;
import androidx.car.app.model.InputCallback;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.ParkedOnlyOnClickListener;
import androidx.car.app.model.Row;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.car.app.model.signin.InputSignInMethod;
import androidx.car.app.model.signin.ProviderSignInMethod;
import androidx.car.app.model.signin.SignInTemplate;
import androidx.car.app.navigation.model.MapTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiVisibleTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** Receives sign-in text submitted into a {@link #renderSignIn} template, with its field. */
    public interface InputListener {
        void onInputSubmitted(@NonNull A2uiComponent field, @NonNull String text);
    }

    /**
     * Resolves a place row's distance in meters. The wire carries titles, not numbers, so the
     * host owns distances; negative means unknown.
     */
    public interface DistanceResolver {
        double distanceMeters(@NonNull A2uiComponent row);
    }

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
        requireUsableLimit(contentLimit);
        Header.Builder header = new Header.Builder().setTitle(title);
        if (headerAction != null) {
            header.addEndHeaderAction(headerAction);
        }
        return new ListTemplate.Builder()
                .setHeader(header.build())
                .setSingleList(buildListItems(
                        A2uiVisibleTree.renderableRows(root), contentLimit, binder))
                .build();
    }

    /**
     * Builds a grid template from a pruned surface: one item per renderable row, truncated to the
     * host's grid limit. A grid item has one text line where a row has three, so subtitle, note
     * and voice hint join it in that order.
     *
     * @param contentLimit the host's limit for {@code CONTENT_LIMIT_TYPE_GRID}
     * @param itemImage the image on every item: the template requires one per item and the wire
     *        carries no bitmaps, so the host supplies one placeholder and the titles distinguish
     */
    @NonNull
    public static Template renderGrid(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit,
            @Nullable A2uiActionBinder binder, @Nullable Action headerAction,
            @NonNull CarIcon itemImage) {
        requireUsableLimit(contentLimit);
        List<A2uiComponent> rows = A2uiVisibleTree.renderableRows(root);
        ItemList.Builder items = new ItemList.Builder();
        int shown = Math.min(rows.size(), contentLimit);
        for (int i = 0; i < shown; i++) {
            items.addItem(gridItem(rows.get(i), binder, itemImage));
        }
        if (shown == 0) {
            items.setNoItemsMessage(A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE);
        }
        Header.Builder header = new Header.Builder().setTitle(title);
        if (headerAction != null) {
            header.addEndHeaderAction(headerAction);
        }
        return new GridTemplate.Builder()
                .setHeader(header.build())
                .setSingleList(items.build())
                .build();
    }

    /**
     * Builds a long-message template: the first text row is the message, button rows become
     * template actions. A refused button is removed, not disabled: removal is the prune's
     * language, and a message template has no line that could say why a button died. No header
     * action: the template admits none custom-titled.
     */
    @NonNull
    public static Template renderLongMessage(
            @Nullable A2uiComponent root, @NonNull String title,
            @Nullable A2uiActionBinder binder) {
        List<A2uiComponent> rows = A2uiVisibleTree.renderableRows(root);
        String message = A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE;
        for (A2uiComponent row : rows) {
            if (row.getType() == ComponentType.TEXT) {
                message = row.getSubtitle() != null ? row.getSubtitle()
                        : row.getTitle() != null ? row.getTitle() : message;
                break;
            }
        }
        LongMessageTemplate.Builder template = new LongMessageTemplate.Builder(message)
                .setTitle(title);
        for (A2uiComponent row : rows) {
            if (row.getType() != ComponentType.BUTTON) {
                continue;
            }
            A2uiActionPolicy.Decision decision =
                    binder == null ? null : binder.decisionFor(row);
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                continue;
            }
            template.addAction(templateAction(row, binder, decision));
        }
        return template.build();
    }

    /**
     * Builds a sign-in template. A text-field row becomes text input; button rows become template
     * actions. With no field (pruned by NO_KEYBOARD) the first button becomes a provider method,
     * so the flow degrades to a single continue action instead of vanishing. No header action:
     * the template admits none custom-titled.
     *
     * @param inputListener receives submitted text with its field; null listens to nothing
     * @throws IllegalArgumentException if there is neither a field nor a button to sign in with
     */
    @NonNull
    public static Template renderSignIn(
            @Nullable A2uiComponent root, @NonNull String title,
            @Nullable A2uiActionBinder binder, @Nullable InputListener inputListener) {
        List<A2uiComponent> rows = A2uiVisibleTree.renderableRows(root);
        A2uiComponent field = null;
        List<A2uiComponent> buttons = new ArrayList<>();
        for (A2uiComponent row : rows) {
            if (field == null && row.getType() == ComponentType.TEXT_FIELD) {
                field = row;
            } else if (row.getType() == ComponentType.BUTTON) {
                buttons.add(row);
            }
        }
        if (field == null && buttons.isEmpty()) {
            throw new IllegalArgumentException(
                    "sign-in needs a field or a button; the prune left neither");
        }
        SignInTemplate.Builder template;
        int firstAction;
        if (field != null) {
            template = new SignInTemplate.Builder(signInInput(field, binder, inputListener));
            firstAction = 0;
        } else {
            A2uiComponent cont = buttons.get(0);
            A2uiActionPolicy.Decision decision =
                    binder == null ? null : binder.decisionFor(cont);
            Action.Builder action = new Action.Builder().setTitle(actionTitle(cont));
            // Parked-only, as the provider method requires: signing in while moving is refused
            // by the host as well as the policy. The method also requires a listener, so a
            // refused action carries a no-op one — and stays disabled, so it can never fire.
            final A2uiComponent invoked = cont;
            action.setOnClickListener(ParkedOnlyOnClickListener.create(
                    decision != null && decision != A2uiActionPolicy.Decision.REFUSE
                            ? () -> binder.onInvoke(invoked) : () -> { }));
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                action.setEnabled(false);
            }
            template = new SignInTemplate.Builder(new ProviderSignInMethod(action.build()));
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                template.setAdditionalText(REFUSED_SUFFIX);
            }
            firstAction = 1;
        }
        template.setTitle(title);
        String instructions = !buttons.isEmpty() ? buttons.get(0).getSubtitle()
                : field.getSubtitle();
        if (instructions != null) {
            template.setInstructions(instructions);
        }
        for (int i = firstAction; i < buttons.size(); i++) {
            A2uiComponent button = buttons.get(i);
            A2uiActionPolicy.Decision decision =
                    binder == null ? null : binder.decisionFor(button);
            if (decision == A2uiActionPolicy.Decision.REFUSE) {
                continue;
            }
            template.addAction(templateAction(button, binder, decision));
        }
        return template.build();
    }

    /**
     * Builds a tab template: one tab per child of a Tabs root, showing the active child's rows as
     * a list. An unknown or null active id shows the first tab. No header action: a TabTemplate
     * allows no custom-titled header actions, so the voice action cannot ride one (the debug
     * LISTEN broadcast still demonstrates voice). Voice still reads {@code visibleRoot}, which
     * sees only the Tabs root, so tab contents are visible but not voice-addressable yet.
     *
     * @param contentLimit the host's list limit, applied to the active tab's rows
     * @param activeContentId the tab to show, by child id; null or unknown shows the first
     * @param tabIcon the mark on every tab: the template requires an icon per tab and the wire
     *        carries no tab art, so the host supplies one neutral icon and the titles distinguish
     * @return the tab template, or the single page as a list when the prune left one child: the
     *         template requires at least two tabs, and a tab bar with one tab is a list
     * @throws IllegalArgumentException if the root is not a Tabs with children
     */
    @NonNull
    public static Template renderTab(
            @Nullable A2uiComponent root, int contentLimit,
            @Nullable A2uiActionBinder binder, @Nullable String activeContentId,
            @NonNull TabTemplate.TabCallback tabCallback, @NonNull CarIcon tabIcon) {
        requireUsableLimit(contentLimit);
        if (root == null || root.getType() != ComponentType.TABS || root.getChildren().isEmpty()) {
            throw new IllegalArgumentException(
                    "a tab template needs a Tabs root with children");
        }
        if (root.getChildren().size() == 1) {
            A2uiComponent only = root.getChildren().get(0);
            return new ListTemplate.Builder()
                    .setHeader(new Header.Builder()
                            .setTitle(only.getTitle() != null ? only.getTitle() : only.getId())
                            .build())
                    .setSingleList(buildListItems(
                            A2uiVisibleTree.renderableRows(only), contentLimit, binder))
                    .build();
        }
        TabTemplate.Builder template = new TabTemplate.Builder(tabCallback);
        // Required, and standard rather than custom-titled: the template rejects custom header
        // actions and refuses to build with none.
        template.setHeaderAction(Action.APP_ICON);
        A2uiComponent active = root.getChildren().get(0);
        for (A2uiComponent page : root.getChildren()) {
            template.addTab(new Tab.Builder()
                    .setTitle(page.getTitle() != null ? page.getTitle() : page.getId())
                    .setContentId(page.getId())
                    .setIcon(tabIcon)
                    .build());
            if (page.getId().equals(activeContentId)) {
                active = page;
            }
        }
        ListTemplate content = new ListTemplate.Builder()
                .setHeader(new Header.Builder()
                        .setTitle(active.getTitle() != null ? active.getTitle() : active.getId())
                        .build())
                .setSingleList(buildListItems(
                        A2uiVisibleTree.renderableRows(active), contentLimit, binder))
                .build();
        template.setActiveTabContentId(active.getId());
        template.setTabContents(new TabContents.Builder(content).build());
        return template.build();
    }

    /**
     * Builds a map template: the host's map behind the pruned surface's rows. The app draws no
     * map pixels of its own; the map row is content like any other.
     *
     * @param contentLimit the host's list limit, applied to the overlay rows
     */
    @NonNull
    public static Template renderMap(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit,
            @Nullable A2uiActionBinder binder, @Nullable Action headerAction) {
        requireUsableLimit(contentLimit);
        Header.Builder header = new Header.Builder().setTitle(title);
        if (headerAction != null) {
            header.addEndHeaderAction(headerAction);
        }
        return new MapTemplate.Builder()
                .setHeader(header.build())
                .setItemList(buildListItems(
                        A2uiVisibleTree.renderableRows(root), contentLimit, binder))
                .build();
    }

    /**
     * Builds a place-list navigation template: one row per stop, each navigating through the
     * binder. Every row carries a distance span — the template rejects rows without one — over a
     * host-formatted distance line, US customary, followed by the row's own subtitle.
     *
     * @param contentLimit the host's limit for {@code CONTENT_LIMIT_TYPE_PLACE_LIST}
     * @param resolver the distance behind every row; unknown distances fail loudly, because a
     *        row without one fails at the template anyway
     */
    @NonNull
    public static Template renderPlaceListNavigation(
            @Nullable A2uiComponent root, @NonNull String title, int contentLimit,
            @Nullable A2uiActionBinder binder, @Nullable Action headerAction,
            @NonNull DistanceResolver resolver) {
        requireUsableLimit(contentLimit);
        List<A2uiComponent> rows = A2uiVisibleTree.renderableRows(root);
        ItemList.Builder items = new ItemList.Builder();
        int shown = Math.min(rows.size(), contentLimit);
        for (int i = 0; i < shown; i++) {
            items.addItem(placeRow(rows.get(i), binder, resolver));
        }
        if (shown == 0) {
            items.setNoItemsMessage(A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE);
        }
        Header.Builder header = new Header.Builder().setTitle(title);
        if (headerAction != null) {
            header.addEndHeaderAction(headerAction);
        }
        return new PlaceListNavigationTemplate.Builder()
                .setHeader(header.build())
                .setItemList(items.build())
                .build();
    }

    private static void requireUsableLimit(int contentLimit) {
        if (contentLimit < MIN_USABLE_CONTENT_LIMIT) {
            // Loudly: substituting a default would invent the host's constraint.
            throw new IllegalArgumentException(
                    "content limit " + contentLimit + " is below the usable floor "
                            + MIN_USABLE_CONTENT_LIMIT + "; the host did not supply a real limit");
        }
    }

    /** The rows a list — top-level or tab content — draws, truncated, with the honest empty. */
    @NonNull
    private static ItemList buildListItems(
            @NonNull List<A2uiComponent> rows, int contentLimit,
            @Nullable A2uiActionBinder binder) {
        ItemList.Builder items = new ItemList.Builder();
        int shown = Math.min(rows.size(), contentLimit);
        for (int i = 0; i < shown; i++) {
            items.addItem(listRow(rows.get(i), binder));
        }
        if (shown == 0) {
            // Distinct from an over-limit truncation, and it must say so. An empty surface after a
            // prune is the correct outcome of a fully-restricted vehicle, not an error.
            items.setNoItemsMessage(A2uiActionBinder.EMPTY_WHILE_DRIVING_NOTE);
        }
        return items.build();
    }

    @NonNull
    private static Row listRow(
            @NonNull A2uiComponent component, @Nullable A2uiActionBinder binder) {
        return rowWithSubtitle(component, binder,
                component.getSubtitle() != null
                        ? new CarText.Builder(component.getSubtitle()).build() : null);
    }

    @NonNull
    private static Row placeRow(
            @NonNull A2uiComponent component, @Nullable A2uiActionBinder binder,
            @NonNull DistanceResolver resolver) {
        return rowWithSubtitle(component, binder, distanceLine(component, resolver));
    }

    /**
     * The distance line: a host-formatted miles figure with a distance span over it, then the
     * row's own subtitle. US customary, matching the NYC demo; a real host follows the car
     * locale, and the corpus carries no miles of its own to contradict.
     */
    @NonNull
    private static CarText distanceLine(@NonNull A2uiComponent component,
            @NonNull DistanceResolver resolver) {
        double meters = resolver.distanceMeters(component);
        if (!(meters >= 0)) {
            // Loudly: the template rejects a row without a distance span anyway.
            throw new IllegalArgumentException(
                    "no distance for place row " + component.getId()
                            + "; the template requires one per row");
        }
        double miles = meters / 1609.344;
        String distanceText = String.format(Locale.US, "%.1f mi", miles);
        String line = component.getSubtitle() == null ? distanceText
                : distanceText + " · " + component.getSubtitle();
        SpannableString spannable = new SpannableString(line);
        spannable.setSpan(
                DistanceSpan.create(Distance.create(miles, Distance.UNIT_MILES_P1)),
                0, distanceText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return new CarText.Builder(spannable).build();
    }

    @NonNull
    private static Row rowWithSubtitle(
            @NonNull A2uiComponent component, @Nullable A2uiActionBinder binder,
            @Nullable CarText subtitle) {
        Row.Builder row = new Row.Builder()
                .setTitle(component.getTitle() == null
                        ? component.getType().catalogName() : component.getTitle());
        if (subtitle != null) {
            row.addText(subtitle);
        }
        A2uiActionPolicy.Decision decision =
                binder == null ? null : binder.decisionFor(component);
        if (decision == A2uiActionPolicy.Decision.REFUSE) {
            // Said out loud. A control that silently stops working reads as a broken app, and
            // the driver's next move is to press it again.
            row.addText(REFUSED_SUFFIX);
        } else if (decision != null) {
            final A2uiComponent invoked = component;
            if (component.getType() == ComponentType.CHECK_BOX
                    && decision == A2uiActionPolicy.Decision.ALLOW) {
                // Native toggle without parsing agent prose into state; the change is reported
                // through the binder like every other action.
                row.setToggle(new Toggle.Builder(checked -> binder.onInvoke(invoked)).build());
            } else {
                row.setOnClickListener(() -> binder.onInvoke(invoked));
                if (component.getType() == ComponentType.CARD
                        || component.getType() == ComponentType.LIST
                        || component.getType() == ComponentType.TABS) {
                    row.setBrowsable(true);
                }
            }
            if (component.getVoicePhrase() != null
                    && !component.getVoicePhrase().trim().isEmpty()) {
                row.addText(A2uiActionBinder.voiceHint(component.getVoicePhrase()));
            }
        }
        return row.build();
    }

    @NonNull
    private static GridItem gridItem(
            @NonNull A2uiComponent component, @Nullable A2uiActionBinder binder,
            @NonNull CarIcon itemImage) {
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(component.getTitle() == null
                        ? component.getType().catalogName() : component.getTitle())
                .setImage(itemImage);
        StringBuilder text = new StringBuilder();
        if (component.getSubtitle() != null) {
            text.append(component.getSubtitle());
        }
        A2uiActionPolicy.Decision decision =
                binder == null ? null : binder.decisionFor(component);
        if (decision == A2uiActionPolicy.Decision.REFUSE) {
            appendGridLine(text, REFUSED_SUFFIX);
        } else if (decision != null) {
            final A2uiComponent invoked = component;
            item.setOnClickListener(() -> binder.onInvoke(invoked));
            if (component.getVoicePhrase() != null
                    && !component.getVoicePhrase().trim().isEmpty()) {
                appendGridLine(text, A2uiActionBinder.voiceHint(component.getVoicePhrase()));
            }
        }
        if (text.length() > 0) {
            item.setText(text.toString());
        }
        return item.build();
    }

    private static void appendGridLine(@NonNull StringBuilder text, @NonNull String line) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(line);
    }

    /**
     * A button row as a template action: titled, invoking through the binder when decided. The
     * listener is parked-only, as message and sign-in templates require.
     */
    @NonNull
    private static Action templateAction(
            @NonNull A2uiComponent button, @Nullable A2uiActionBinder binder,
            @Nullable A2uiActionPolicy.Decision decision) {
        Action.Builder action = new Action.Builder().setTitle(actionTitle(button));
        if (decision != null) {
            final A2uiComponent invoked = button;
            action.setOnClickListener(ParkedOnlyOnClickListener.create(
                    () -> binder.onInvoke(invoked)));
        }
        return action.build();
    }

    @NonNull
    private static String actionTitle(@NonNull A2uiComponent button) {
        return button.getTitle() != null ? button.getTitle()
                : button.getType().catalogName();
    }

    /** A field row as sign-in text input: refused entry says so up front and submits nothing. */
    @NonNull
    private static InputSignInMethod signInInput(
            @NonNull A2uiComponent field, @Nullable A2uiActionBinder binder,
            @Nullable InputListener inputListener) {
        A2uiActionPolicy.Decision decision =
                binder == null ? null : binder.decisionFor(field);
        InputSignInMethod.Builder method = new InputSignInMethod.Builder(
                signInCallback(decision, field, inputListener));
        method.setHint(field.getTitle() != null ? field.getTitle() : "");
        if (decision == A2uiActionPolicy.Decision.REFUSE) {
            method.setErrorMessage(REFUSED_SUFFIX);
        }
        return method.build();
    }

    /**
     * Forwards submitted text with its field, unless the field was refused — then nothing is
     * submitted at all. Static so both branches are unit-testable without a templates host.
     */
    @VisibleForTesting
    static InputCallback signInCallback(@Nullable A2uiActionPolicy.Decision decision,
            @NonNull A2uiComponent field, @Nullable InputListener inputListener) {
        return new InputCallback() {
            @Override
            public void onInputSubmitted(String text) {
                if (decision != A2uiActionPolicy.Decision.REFUSE && inputListener != null) {
                    inputListener.onInputSubmitted(field, text);
                }
            }
        };
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
