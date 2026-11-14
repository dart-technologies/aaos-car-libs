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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import androidx.annotation.Nullable;

import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.InputCallback;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.signin.InputSignInMethod;
import androidx.car.app.model.signin.ProviderSignInMethod;
import androidx.car.app.model.signin.SignInTemplate;
import androidx.car.app.navigation.model.MapTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionClass;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessage;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.Test;

/**
 * The quantity constraint {@code prune} cannot express ("Scope note 1"): the templates host rejects
 * an over-limit template, and these tests pin the renderer's half of that.
 *
 * <p>Robolectric, for the place rows: distance spans ride a {@code SpannableString}, whose span
 * bookkeeping is a framework stub under plain JUnit.
 */
@RunWith(RobolectricTestRunner.class)
public class CarAppRendererTest {

    private static A2uiComponent listOf(int itemCount) {
        A2uiComponent.Builder column = new A2uiComponent.Builder(ComponentType.COLUMN);
        for (int i = 0; i < itemCount; i++) {
            column.addChild(new A2uiComponent.Builder(ComponentType.TEXT)
                    .setTitle("item " + i)
                    .build());
        }
        return column.build();
    }

    /** The rows a visible tree stands for, flattened as the renderer flattens (renderableRows). */
    private static int visibleRowCount(@Nullable A2uiComponent visible) {
        if (visible == null) {
            return 0;
        }
        return visible.getChildren().isEmpty() ? 1 : visible.getChildren().size();
    }

    private static int itemCount(Template template) {
        ItemList list = ((ListTemplate) template).getSingleList();
        return list == null ? 0 : list.getItems().size();
    }

    // visibleRoot: what another input mode is allowed to reach.

    /**
     * The visible tree has exactly the rows the renderer draws, across tree shapes, so voice and
     * screen cannot drift apart.
     */
    @Test
    public void theVisibleTreeHasExactlyTheRowsTheRendererDraws() {
        int checked = 0;
        for (int size : new int[] {0, 1, 3, 6, 9, 20}) {
            for (int limit : new int[] {6, 9, 100}) {
                A2uiComponent root = listOf(size);
                int drawn = itemCount(CarAppRenderer.render(root, "t", limit));

                assertThat(visibleRowCount(CarAppRenderer.visibleRoot(root, limit)))
                        .isEqualTo(drawn);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(18);

        // Includes limits render refuses: there nothing may be drawn, so nothing may be visible.
        int refused = 0;
        for (int size : new int[] {0, 1, 3, 6, 9, 20}) {
            for (int limit : new int[] {0, -1}) {
                A2uiComponent root = listOf(size);
                assertThrows(IllegalArgumentException.class,
                        () -> CarAppRenderer.render(root, "t", limit));

                assertThat(visibleRowCount(CarAppRenderer.visibleRoot(root, limit))).isEqualTo(0);
                refused++;
            }
        }
        assertThat(refused).isEqualTo(12);
    }

    /** A control nested inside a drawn row is not on screen: the host draws one line per child. */
    @Test
    public void theVisibleTreeDropsWhatIsNestedInsideARow() {
        A2uiComponent buried = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("prev").setTitle("Previous").setAction("previous").build();
        A2uiComponent row = new A2uiComponent.Builder(ComponentType.ROW)
                .setId("controls").addChild(buried).build();
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.COLUMN).addChild(row).build();

        A2uiComponent visible = CarAppRenderer.visibleRoot(root, 6);

        assertThat(visible.getChildren()).hasSize(1);
        assertThat(visible.getChildren().get(0).getId()).isEqualTo("controls");
        assertThat(visible.getChildren().get(0).getChildren()).isEmpty();
    }

    /** Properties survive; only the descendants go. Otherwise nothing could be matched by voice. */
    @Test
    public void theVisibleTreeKeepsWhatMakesARowAddressable() {
        A2uiComponent button = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy album").setAction("purchase").build();
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(button).build();

        A2uiComponent kept = CarAppRenderer.visibleRoot(root, 6).getChildren().get(0);

        assertThat(kept.getId()).isEqualTo("buy");
        assertThat(kept.getTitle()).isEqualTo("Buy album");
        assertThat(kept.getAction()).isEqualTo("purchase");
    }

    @Test
    public void aNonContainerRootIsVisibleAsItself() {
        A2uiComponent lone = new A2uiComponent.Builder(ComponentType.TEXT)
                .setId("only").setTitle("Only").build();

        A2uiComponent visible = CarAppRenderer.visibleRoot(lone, 6);

        assertThat(visible.getId()).isEqualTo("only");
        assertThat(visible.getChildren()).isEmpty();
    }

    @Test
    public void nothingRenderedIsNothingVisible() {
        assertThat(CarAppRenderer.visibleRoot(null, 6)).isNull();
    }

    // The two places the visible tree diverged from render: something a phrase could reach that
    // the renderer drew no row for.

    private static final String V = "v0.9";

    /**
     * A processor holding one surface, projected as the host does, so assertions go through
     * {@code dispatchByVoice} rather than the returned fields.
     */
    private static A2uiMessageProcessor surfaceWith(List<Map<String, Object>> components) {
        A2uiMessageProcessor p = new A2uiMessageProcessor("automotive",
                new HashSet<>(Collections.singletonList(V)));
        p.process(A2uiMessage.createSurface(V, "s1", "automotive"));
        p.process(A2uiMessage.updateComponents(V, "s1", components));
        // FULLY_RESTRICTED and trustworthy: a safe action is still allowed, so a reached control
        // reaches the sink and a refusal cannot come from the action policy instead.
        p.setRestrictionReader(new A2uiMessageProcessor.RestrictionReader() {
            @Override
            public int activeRestrictions() {
                return A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED;
            }

            @Override
            public boolean isTrustworthy() {
                return true;
            }
        });
        return p;
    }

    private static Map<String, Object> speakable(
            String id, String type, String action, String title) {
        Map<String, Object> m = A2uiMessage.component(id, type);
        m.put("action", action);
        m.put("title", title);
        return m;
    }

    /**
     * A container root draws no row, so it is not a control and cannot be spoken to, even when an
     * agent gives it an action.
     */
    @Test
    public void aContainerRootIsNotSpeakableBecauseNoRowIsDrawnForIt() {
        Map<String, Object> root = speakable("root", "Column", "play", "Play everything");
        root.put("children", Collections.singletonList("track"));
        A2uiMessageProcessor p = surfaceWith(Arrays.asList(
                root, speakable("track", "Button", "play", "Play")));
        List<String> ran = new ArrayList<>();
        p.setActionSink((surfaceId, componentId, action, payload) -> ran.add(componentId));
        A2uiComponent projected = p.getSurface("s1").project("root");

        // The screen: one row, and it is the child's.
        Template drawn = CarAppRenderer.render(projected, "t", 6);
        assertThat(itemCount(drawn)).isEqualTo(1);
        assertThat(firstRow(drawn).getTitle().toString()).isEqualTo("Play");

        // So the root's phrase reaches nothing, and nothing runs.
        A2uiComponent visible = CarAppRenderer.visibleRoot(projected, 6);
        assertThat(p.dispatchByVoice("s1", "play everything", visible))
                .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        assertThat(ran).isEmpty();
    }

    /** The mechanism behind the test above: the tree's root carries no invocation of its own. */
    @Test
    public void theVisibleRootOfAContainerCarriesNoInvocation() {
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").setTitle("Play everything").setAction("play")
                .setVoicePhrase("play everything")
                .setActionClass(A2uiActionClass.SAFE)
                .addChild(button("track", "play")).build();

        A2uiComponent visible = CarAppRenderer.visibleRoot(root, 6);

        assertThat(visible.getAction()).isNull();
        assertThat(visible.getVoicePhrase()).isNull();
        assertThat(visible.getActionClass()).isNull();
        // Identity and text survive. They say which tree this is; they invoke nothing, and a
        // visible tree that could not be identified would be no use to a diagnostic.
        assertThat(visible.getId()).isEqualTo("root");
        assertThat(visible.getTitle()).isEqualTo("Play everything");
        assertThat(visible.getChildren()).hasSize(1);
    }

    /**
     * Roots the renderer does draw (a non-container, an empty container) keep their voice route:
     * the fix keys on the branch taken, not the root's type.
     */
    @Test
    public void aRootTheRendererDoesDrawKeepsItsVoiceRoute() {
        for (String type : new String[] {"Button", "Column"}) {
            A2uiMessageProcessor p = surfaceWith(Collections.singletonList(
                    speakable("root", type, "play", "Play everything")));
            List<String> ran = new ArrayList<>();
            p.setActionSink((surfaceId, componentId, action, payload) -> ran.add(componentId));
            A2uiComponent projected = p.getSurface("s1").project("root");

            assertThat(itemCount(CarAppRenderer.render(projected, "t", 6))).isEqualTo(1);
            assertThat(p.dispatchByVoice("s1", "play everything",
                    CarAppRenderer.visibleRoot(projected, 6)))
                    .isEqualTo(A2uiMessageProcessor.VoiceOutcome.DISPATCHED);
            assertThat(ran).containsExactly("root");
        }
    }

    /**
     * A limit the renderer refuses is not one the visible tree may interpret: below the floor the
     * host gave no answer.
     */
    @Test
    public void aContainerIsNotVisibleAtALimitTheRendererRefuses() {
        A2uiComponent container = listOf(3);

        for (int refused : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CarAppRenderer.render(container, "t", refused));
            assertThat(CarAppRenderer.visibleRoot(container, refused)).isNull();
        }
    }

    /** The same floor on the non-container branch, which once ignored the limit entirely. */
    @Test
    public void aLoneControlIsNotVisibleAtALimitTheRendererRefuses() {
        A2uiComponent lone = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy album").setAction("purchase").build();

        for (int refused : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CarAppRenderer.render(lone, "t", refused));
            assertThat(CarAppRenderer.visibleRoot(lone, refused)).isNull();
        }
    }

    /**
     * Null rather than a throw: the voice path calls visibleRoot without a render, and a throw
     * would escape into the recogniser callback after the listen's outcome was claimed. Null reads
     * as {@link A2uiMessageProcessor.VoiceOutcome#NO_MATCH}.
     */
    @Test
    public void aRefusedLimitLeavesNoControlForAPhraseToReach() {
        Map<String, Object> root = speakable("root", "Column", "play", "Play everything");
        root.put("children", Collections.singletonList("track"));
        A2uiMessageProcessor p = surfaceWith(Arrays.asList(
                root, speakable("track", "Button", "play", "Play")));
        List<String> ran = new ArrayList<>();
        p.setActionSink((surfaceId, componentId, action, payload) -> ran.add(componentId));

        A2uiComponent visible = CarAppRenderer.visibleRoot(p.getSurface("s1").project("root"), 0);

        assertThat(visible).isNull();
        for (String phrase : new String[] {"play everything", "play"}) {
            assertThat(p.dispatchByVoice("s1", phrase, visible))
                    .isEqualTo(A2uiMessageProcessor.VoiceOutcome.NO_MATCH);
        }
        assertThat(ran).isEmpty();
    }

    // The header action, which the renderer carries but knows nothing about.

    @Test
    public void aHeaderActionIsAddedToTheHeaderWhenOneIsSupplied() {
        Action action = new Action.Builder().setTitle("Speak").setOnClickListener(() -> {}).build();

        Template template = CarAppRenderer.render(listOf(3), "t", 6, null, action);

        assertThat(((ListTemplate) template).getHeader().getEndHeaderActions())
                .containsExactly(action);
    }

    @Test
    public void aNullHeaderActionLeavesTheHeaderWithout() {
        Template template = CarAppRenderer.render(listOf(3), "t", 6, null, null);

        assertThat(((ListTemplate) template).getHeader().getEndHeaderActions()).isEmpty();
    }

    /** The four-argument overload, which every caller above uses, must render exactly as before. */
    @Test
    public void theFourArgumentOverloadRendersIdenticallyToPassingNoAction() {
        Template four = CarAppRenderer.render(listOf(3), "t", 6, null);
        Template five = CarAppRenderer.render(listOf(3), "t", 6, null, null);

        assertThat(((ListTemplate) four).getHeader().getEndHeaderActions()).isEmpty();
        assertThat(itemCount(four)).isEqualTo(itemCount(five));
    }

    @Test
    public void rendersEveryItemWhenUnderTheLimit() {
        Template template = CarAppRenderer.render(listOf(3), "t", 6);

        assertThat(itemCount(template)).isEqualTo(3);
    }

    @Test
    public void truncatesToTheHostsLimit() {
        Template template = CarAppRenderer.render(listOf(20), "t", 6);

        assertThat(itemCount(template)).isEqualTo(6);
    }

    @Test
    public void exactlyAtTheLimitIsNotTruncated() {
        Template template = CarAppRenderer.render(listOf(6), "t", 6);

        assertThat(itemCount(template)).isEqualTo(6);
        assertThat(CarAppRenderer.overflowCount(listOf(6), 6)).isEqualTo(0);
    }

    @Test
    public void overflowIsCountable() {
        assertThat(CarAppRenderer.overflowCount(listOf(20), 6)).isEqualTo(14);
        assertThat(CarAppRenderer.overflowCount(listOf(2), 6)).isEqualTo(0);
        assertThat(CarAppRenderer.overflowCount(null, 6)).isEqualTo(0);
    }

    /**
     * A zero limit is refused, not rendered: a stubbed {@code ConstraintManager} answers 0, which
     * is a missing answer, not a restriction.
     */
    @Test
    public void zeroLimitIsRefusedRatherThanRenderedAsEmpty() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CarAppRenderer.render(listOf(3), "t", 0));

        assertThat(e).hasMessageThat().contains("did not supply a real limit");
    }

    @Test
    public void negativeLimitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CarAppRenderer.render(listOf(3), "t", -1));
    }

    /**
     * An empty surface after pruning renders with a message. Not the refused-limit case: "show
     * nothing" versus "told us nothing".
     */
    @Test
    public void prunedToNothingRendersAnExplicitEmptyState() {
        Template template = CarAppRenderer.render(null, "t", 6);

        assertThat(itemCount(template)).isEqualTo(0);
        ItemList list = ((ListTemplate) template).getSingleList();
        assertThat(list).isNotNull();
        assertThat(list.getNoItemsMessage()).isNotNull();
        assertThat(list.getNoItemsMessage().toString()).contains("while driving");
    }

    @Test
    public void nonContainerRootRendersAsASingleRow() {
        A2uiComponent single = new A2uiComponent.Builder(ComponentType.TEXT)
                .setTitle("only")
                .build();

        assertThat(itemCount(CarAppRenderer.render(single, "t", 6))).isEqualTo(1);
    }

    @Test
    public void componentWithoutATitleFallsBackToItsCatalogName() {
        A2uiComponent untitled = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.DIVIDER).build())
                .build();

        Template template = CarAppRenderer.render(untitled, "t", 6);

        androidx.car.app.model.Row row =
                (androidx.car.app.model.Row) ((ListTemplate) template)
                        .getSingleList().getItems().get(0);
        assertThat(row.getTitle()).isNotNull();
        assertThat(row.getTitle().toString()).isEqualTo("Divider");
    }

    // Interaction: the renderer does what the binder says, and attaches no listener to a refused
    // control.

    private static A2uiComponent button(String id, String action) {
        return new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId(id).setTitle(id).setAction(action).build();
    }

    private static A2uiComponent columnOf(A2uiComponent... children) {
        A2uiComponent.Builder b = new A2uiComponent.Builder(ComponentType.COLUMN);
        for (A2uiComponent c : children) {
            b.addChild(c);
        }
        return b.build();
    }

    /** Binder that answers with a fixed decision and records what it was asked to invoke. */
    private static final class FakeBinder implements A2uiActionBinder {
        A2uiActionPolicy.Decision decision;
        final List<String> invoked = new ArrayList<>();

        FakeBinder(A2uiActionPolicy.Decision decision) {
            this.decision = decision;
        }

        @Override
        public A2uiActionPolicy.Decision decisionFor(A2uiComponent component) {
            return component.isInteractive() ? decision : null;
        }

        @Override
        public void onInvoke(A2uiComponent component) {
            invoked.add(component.getAction());
        }
    }

    private static androidx.car.app.model.Row firstRow(Template t) {
        return (androidx.car.app.model.Row) ((ListTemplate) t).getSingleList().getItems().get(0);
    }

    @Test
    public void refusedControlGetsNoClickListener() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.REFUSE);

        Template t = CarAppRenderer.render(
                columnOf(button("buy", "purchase")), "T", 10, binder);

        assertThat(firstRow(t).getOnClickDelegate()).isNull();
    }

    @Test
    public void refusedControlSaysWhyRatherThanFailingSilently() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.REFUSE);

        Template t = CarAppRenderer.render(
                columnOf(button("buy", "purchase")), "T", 10, binder);

        // A control that just stops working reads as a broken app, and the next move is to press it
        // again — which is another glance.
        assertThat(firstRow(t).getTexts().toString()).contains(CarAppRenderer.REFUSED_SUFFIX);
    }

    @Test
    public void allowedControlIsClickableAndReachesTheBinder() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.ALLOW);

        Template t = CarAppRenderer.render(
                columnOf(button("play", "play")), "T", 10, binder);

        assertThat(firstRow(t).getOnClickDelegate()).isNotNull();
    }

    @Test
    public void confirmableControlIsOffered() {
        // CONFIRM is clickable: it is only reachable when the vehicle is not restricting.
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.CONFIRM);

        Template t = CarAppRenderer.render(
                columnOf(button("buy", "purchase")), "T", 10, binder);

        assertThat(firstRow(t).getOnClickDelegate()).isNotNull();
    }

    @Test
    public void nonInteractiveComponentIsNeverClickable() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.ALLOW);
        A2uiComponent text = new A2uiComponent.Builder(ComponentType.TEXT).setTitle("x").build();

        Template t = CarAppRenderer.render(columnOf(text), "T", 10, binder);

        assertThat(firstRow(t).getOnClickDelegate()).isNull();
    }

    @Test
    public void withoutABinderNothingIsClickable() {
        // The three-argument overload is what the other host and every existing caller use. Adding
        // interaction must not have made rows pressable by default.
        Template t = CarAppRenderer.render(columnOf(button("buy", "purchase")), "T", 10);

        assertThat(firstRow(t).getOnClickDelegate()).isNull();
    }

    @Test
    public void checkBoxUsesNativeToggleOnlyWhenImmediatelyAllowed() {
        A2uiComponent checkBox = new A2uiComponent.Builder(ComponentType.CHECK_BOX)
                .setId("explicit").setTitle("Allow explicit content")
                .setAction("select").build();

        androidx.car.app.model.Row allowed = firstRow(CarAppRenderer.render(
                columnOf(checkBox), "T", 10,
                new FakeBinder(A2uiActionPolicy.Decision.ALLOW)));
        androidx.car.app.model.Row confirm = firstRow(CarAppRenderer.render(
                columnOf(checkBox), "T", 10,
                new FakeBinder(A2uiActionPolicy.Decision.CONFIRM)));

        assertThat(allowed.getToggle()).isNotNull();
        assertThat(allowed.getOnClickDelegate()).isNull();
        assertThat(confirm.getToggle()).isNull();
        assertThat(confirm.getOnClickDelegate()).isNotNull();
    }

    @Test
    public void cardUsesNativeBrowsableAffordance_withoutTrustingItsLabel() {
        A2uiComponent card = new A2uiComponent.Builder(ComponentType.CARD)
                .setId("place").setTitle("Arbitrary agent title")
                .setAction("navigate").build();

        androidx.car.app.model.Row row = firstRow(CarAppRenderer.render(
                columnOf(card), "T", 10,
                new FakeBinder(A2uiActionPolicy.Decision.ALLOW)));

        assertThat(row.isBrowsable()).isTrue();
        assertThat(row.getOnClickDelegate()).isNotNull();
    }

    @Test
    public void anOfferedControlShowsItsSpokenForm() {
        // The visible half of "two input modes": the phrase sits next to the tappable control, so a
        // viewer sees ONE control reachable two ways rather than two features.
        Template t = CarAppRenderer.render(columnOf(button("play", "play")), "T", 10,
                new FakeBinder(A2uiActionPolicy.Decision.ALLOW));

        assertThat(firstRow(t).getTexts().toString()).contains("say");
        assertThat(firstRow(t).getTexts().toString()).contains("play");
    }

    @Test
    public void aRefusedControlDoesNotAdvertiseAVoiceRoute() {
        // Refused by touch means refused by voice, so no phrase is advertised.
        Template t = CarAppRenderer.render(columnOf(button("buy", "purchase")), "T", 10,
                new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        assertThat(firstRow(t).getTexts().toString()).doesNotContain("say");
        assertThat(firstRow(t).getTexts().toString()).contains(CarAppRenderer.REFUSED_SUFFIX);
    }

    // Grid: one item per row, truncated to the grid limit, titles without bitmaps.

    private static A2uiComponent card(String id, String title, String subtitle) {
        return new A2uiComponent.Builder(ComponentType.CARD)
                .setId(id).setTitle(title).setSubtitle(subtitle).setAction("open").build();
    }

    private static GridItem firstGridItem(Template t) {
        return (GridItem) ((GridTemplate) t).getSingleList().getItems().get(0);
    }

    private static int gridItemCount(Template t) {
        ItemList list = ((GridTemplate) t).getSingleList();
        return list == null ? 0 : list.getItems().size();
    }

    @Test
    public void gridRendersOneItemPerRowTruncatedToTheGridLimit() {
        Template t = CarAppRenderer.renderGrid(columnOf(
                card("a", "Wheat Field", "van Gogh"),
                card("b", "Harvesters", "Bruegel"),
                card("c", "Card Players", "Cézanne")), "Art", 2, null, null, mock(CarIcon.class));

        assertThat(gridItemCount(t)).isEqualTo(2);
        assertThat(firstGridItem(t).getTitle().toString()).isEqualTo("Wheat Field");
        assertThat(firstGridItem(t).getText().toString()).isEqualTo("van Gogh");
        assertThat(((GridTemplate) t).getTitle().toString()).isEqualTo("Art");
    }

    @Test
    public void gridItemsCarryTheSuppliedPlaceholder() {
        // The template requires an image per item and the wire carries no bitmaps, so the host's
        // one placeholder marks every item and the titles distinguish.
        CarIcon placeholder = mock(CarIcon.class);

        Template t = CarAppRenderer.renderGrid(
                columnOf(card("a", "Wheat Field", "van Gogh")), "Art", 6, null, null, placeholder);

        assertThat(firstGridItem(t).getImage()).isSameInstanceAs(placeholder);
    }

    @Test
    public void gridRefusesTheUnusableFloor() {
        assertThrows(IllegalArgumentException.class, () -> CarAppRenderer.renderGrid(
                columnOf(card("a", "Wheat Field", "van Gogh")), "Art", 0, null, null, mock(CarIcon.class)));
    }

    @Test
    public void gridEmptyShowsTheHonestNote() {
        Template t = CarAppRenderer.renderGrid(null, "Art", 6, null, null, mock(CarIcon.class));

        assertThat(gridItemCount(t)).isEqualTo(0);
        assertThat(((GridTemplate) t).getSingleList().getNoItemsMessage().toString())
                .contains("while driving");
    }

    @Test
    public void gridRefusedItemHasNoListenerAndSaysWhy() {
        Template t = CarAppRenderer.renderGrid(columnOf(card("a", "Wheat Field", "van Gogh")),
                "Art", 6, new FakeBinder(A2uiActionPolicy.Decision.REFUSE), null, mock(CarIcon.class));

        assertThat(firstGridItem(t).getOnClickDelegate()).isNull();
        assertThat(firstGridItem(t).getText().toString())
                .contains(CarAppRenderer.REFUSED_SUFFIX);
    }

    @Test
    public void gridAllowedItemIsClickableAndShowsItsSpokenForm() {
        Template t = CarAppRenderer.renderGrid(columnOf(card("a", "Wheat Field", "van Gogh")),
                "Art", 6, new FakeBinder(A2uiActionPolicy.Decision.ALLOW), null, mock(CarIcon.class));

        assertThat(firstGridItem(t).getOnClickDelegate()).isNotNull();
        assertThat(firstGridItem(t).getText().toString()).contains("say");
    }

    // Long message: the text row is the message, button rows become template actions.

    private static A2uiComponent text(String title, String subtitle) {
        return new A2uiComponent.Builder(ComponentType.TEXT)
                .setTitle(title).setSubtitle(subtitle).build();
    }

    @Test
    public void longMessageCarriesTitleAndBody() {
        Template t = CarAppRenderer.renderLongMessage(
                columnOf(text("Session complete", "18.4 kWh added")), "Receipt", null);

        assertThat(((LongMessageTemplate) t).getTitle().toString()).isEqualTo("Receipt");
        assertThat(((LongMessageTemplate) t).getMessage().toString())
                .isEqualTo("18.4 kWh added");
    }

    @Test
    public void longMessageButtonsBecomeActions() {
        Template t = CarAppRenderer.renderLongMessage(
                columnOf(text("Session complete", "18.4 kWh added"), button("done", "dismiss")),
                "Receipt", new FakeBinder(A2uiActionPolicy.Decision.ALLOW));

        assertThat(((LongMessageTemplate) t).getActions()).hasSize(1);
        assertThat(((LongMessageTemplate) t).getActions().get(0).getTitle().toString())
                .isEqualTo("done");
        assertThat(((LongMessageTemplate) t).getActions().get(0).getOnClickDelegate())
                .isNotNull();
    }

    @Test
    public void longMessageRefusedButtonIsRemoved() {
        // Removal is the prune's language; a message template has no line that could say why.
        Template t = CarAppRenderer.renderLongMessage(
                columnOf(text("Session complete", "18.4 kWh added"), button("done", "dismiss")),
                "Receipt", new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        assertThat(((LongMessageTemplate) t).getActions()).isEmpty();
    }

    @Test
    public void longMessageWithoutTextSaysItIsEmpty() {
        Template t = CarAppRenderer.renderLongMessage(null, "Receipt", null);

        assertThat(((LongMessageTemplate) t).getMessage().toString()).contains("while driving");
        assertThat(((LongMessageTemplate) t).getActions()).isEmpty();
    }

    // Sign-in: a field becomes text input, buttons become actions, and the flow degrades.

    private static A2uiComponent field(String id, String title) {
        return new A2uiComponent.Builder(ComponentType.TEXT_FIELD)
                .setId(id).setTitle(title).setAction("edit").build();
    }

    @Test
    public void signInFieldBecomesTextInput() {
        A2uiComponent cont = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("continue").setTitle("Continue").setSubtitle("Sync membership")
                .setAction("signin").build();

        Template t = CarAppRenderer.renderSignIn(columnOf(field("email", "Email"), cont),
                "Sign in", new FakeBinder(A2uiActionPolicy.Decision.ALLOW), null);

        SignInTemplate signIn = (SignInTemplate) t;
        assertThat(signIn.getSignInMethod()).isInstanceOf(InputSignInMethod.class);
        assertThat(((InputSignInMethod) signIn.getSignInMethod()).getHint().toString())
                .isEqualTo("Email");
        assertThat(signIn.getInstructions().toString()).isEqualTo("Sync membership");
        assertThat(signIn.getActions()).hasSize(1);
        assertThat(signIn.getActions().get(0).getTitle().toString()).isEqualTo("Continue");
    }

    @Test
    public void signInSubmittedTextReachesTheListenerWithItsField() {
        List<String> got = new ArrayList<>();
        A2uiComponent email = field("email", "Email");

        InputCallback callback = CarAppRenderer.signInCallback(
                A2uiActionPolicy.Decision.ALLOW, email,
                (f, text) -> got.add(f.getId() + "=" + text));
        callback.onInputSubmitted("you@example.com");

        assertThat(got).containsExactly("email=you@example.com");
    }

    @Test
    public void signInRefusedFieldShowsErrorAndSubmitsNothing() {
        List<String> got = new ArrayList<>();
        A2uiComponent email = field("email", "Email");

        Template t = CarAppRenderer.renderSignIn(columnOf(email, button("continue", "signin")),
                "Sign in", new FakeBinder(A2uiActionPolicy.Decision.REFUSE),
                (f, text) -> got.add(text));
        CarAppRenderer.signInCallback(A2uiActionPolicy.Decision.REFUSE, email,
                (f, text) -> got.add(text)).onInputSubmitted("you@example.com");

        assertThat(((InputSignInMethod) ((SignInTemplate) t).getSignInMethod())
                .getErrorMessage().toString()).contains(CarAppRenderer.REFUSED_SUFFIX);
        assertThat(((SignInTemplate) t).getActions()).isEmpty();
        assertThat(got).isEmpty();
    }

    @Test
    public void keyboardlessSignInDegradesToAProviderContinue() {
        // NO_KEYBOARD pruned the field; the button alone still signs in.
        Template t = CarAppRenderer.renderSignIn(columnOf(button("continue", "signin")),
                "Sign in", new FakeBinder(A2uiActionPolicy.Decision.ALLOW), null);

        SignInTemplate signIn = (SignInTemplate) t;
        assertThat(signIn.getSignInMethod()).isInstanceOf(ProviderSignInMethod.class);
        assertThat(((ProviderSignInMethod) signIn.getSignInMethod()).getAction()
                .getOnClickDelegate()).isNotNull();
        assertThat(signIn.getActions()).isEmpty();
    }

    @Test
    public void keyboardlessSignInRefusedDisablesAndSaysWhy() {
        Template t = CarAppRenderer.renderSignIn(columnOf(button("continue", "signin")),
                "Sign in", new FakeBinder(A2uiActionPolicy.Decision.REFUSE), null);

        SignInTemplate signIn = (SignInTemplate) t;
        assertThat(((ProviderSignInMethod) signIn.getSignInMethod()).getAction().isEnabled())
                .isFalse();
        assertThat(signIn.getAdditionalText().toString())
                .contains(CarAppRenderer.REFUSED_SUFFIX);
    }

    @Test
    public void signInWithNothingToSignInWithThrows() {
        assertThrows(IllegalArgumentException.class, () -> CarAppRenderer.renderSignIn(
                columnOf(text("lonely", "no field, no button")), "Sign in", null, null));
    }

    // Tabs: one tab per child, showing the active child's rows as a list.

    private static A2uiComponent tabPage(String id, String title, A2uiComponent... rows) {
        A2uiComponent.Builder b = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId(id).setTitle(title);
        for (A2uiComponent row : rows) {
            b.addChild(row);
        }
        return b.build();
    }

    private static A2uiComponent tabsRoot(A2uiComponent... pages) {
        A2uiComponent.Builder b = new A2uiComponent.Builder(ComponentType.TABS)
                .setId("root").setTitle("lib");
        for (A2uiComponent page : pages) {
            b.addChild(page);
        }
        return b.build();
    }

    private static TabTemplate.TabCallback noopTabs() {
        return new TabTemplate.TabCallback() {
        };
    }

    private static ListTemplate tabContent(Template t) {
        TabContents contents = ((TabTemplate) t).getTabContents();
        assertThat(contents).isNotNull();
        return (ListTemplate) contents.getTemplate();
    }

    @Test
    public void tabRendersOneTabPerChildShowingTheFirst() {
        Template t = CarAppRenderer.renderTab(tabsRoot(
                tabPage("tab_albums", "Albums", text("Recently added", "24 albums")),
                tabPage("tab_artists", "Artists", text("Van Gogh", "14 tracks"))),
                10, null, null, noopTabs(), mock(CarIcon.class));

        TabTemplate tabs = (TabTemplate) t;
        assertThat(tabs.getTabs()).hasSize(2);
        assertThat(tabs.getTabs().get(0).getTitle().toString()).isEqualTo("Albums");
        assertThat(tabs.getTabs().get(0).getIcon()).isNotNull();
        assertThat(tabs.getTabs().get(1).getContentId()).isEqualTo("tab_artists");
        assertThat(tabs.getActiveTabContentId()).isEqualTo("tab_albums");
        assertThat(tabContent(t).getSingleList().getItems()).hasSize(1);
        assertThat(((androidx.car.app.model.Row) tabContent(t).getSingleList().getItems()
                .get(0)).getTitle().toString()).isEqualTo("Recently added");
        assertThat(tabs.getTabCallbackDelegate()).isNotNull();
    }

    @Test
    public void tabExplicitActiveIdShowsThatPage() {
        Template t = CarAppRenderer.renderTab(tabsRoot(
                tabPage("tab_albums", "Albums", text("Recently added", "24 albums")),
                tabPage("tab_artists", "Artists", text("Van Gogh", "14 tracks"))),
                10, null, "tab_artists", noopTabs(), mock(CarIcon.class));

        assertThat(((TabTemplate) t).getActiveTabContentId()).isEqualTo("tab_artists");
        assertThat(((androidx.car.app.model.Row) tabContent(t).getSingleList().getItems()
                .get(0)).getTitle().toString()).isEqualTo("Van Gogh");
    }

    @Test
    public void tabUnknownActiveIdFallsBackToFirst() {
        Template t = CarAppRenderer.renderTab(tabsRoot(
                tabPage("tab_albums", "Albums", text("Recently added", "24 albums")),
                tabPage("tab_artists", "Artists", text("Van Gogh", "14 tracks"))),
                10, null, "no-such-tab", noopTabs(), mock(CarIcon.class));

        assertThat(((TabTemplate) t).getActiveTabContentId()).isEqualTo("tab_albums");
    }

    @Test
    public void tabWithOnePageLeftRendersAsAList() {
        // The template requires at least two tabs; a tab bar with one tab is a list.
        Template t = CarAppRenderer.renderTab(tabsRoot(
                tabPage("tab_albums", "Albums", text("Recently added", "24 albums"))),
                10, null, null, noopTabs(), mock(CarIcon.class));

        assertThat(t).isInstanceOf(ListTemplate.class);
        assertThat(((ListTemplate) t).getHeader().getTitle().toString()).isEqualTo("Albums");
        assertThat(((ListTemplate) t).getSingleList().getItems()).hasSize(1);
    }

    @Test
    public void tabContentTruncatesToTheListLimit() {
        Template t = CarAppRenderer.renderTab(tabsRoot(
                tabPage("tab_albums", "Albums",
                        text("a", "1"), text("b", "2"), text("c", "3")),
                tabPage("tab_artists", "Artists", text("d", "4"))),
                2, null, null, noopTabs(), mock(CarIcon.class));

        assertThat(tabContent(t).getSingleList().getItems()).hasSize(2);
    }

    @Test
    public void tabWithoutTabsRootThrows() {
        assertThrows(IllegalArgumentException.class, () -> CarAppRenderer.renderTab(
                columnOf(text("a", "1")), 10, null, null, noopTabs(), mock(CarIcon.class)));
        assertThrows(IllegalArgumentException.class, () ->
                CarAppRenderer.renderTab(null, 10, null, null, noopTabs(), mock(CarIcon.class)));
    }

    @Test
    public void tabRefusesTheUnusableFloor() {
        assertThrows(IllegalArgumentException.class, () -> CarAppRenderer.renderTab(
                tabsRoot(tabPage("tab_albums", "Albums")), 0, null, null, noopTabs(), mock(CarIcon.class)));
    }

    // Map: the host's map behind the pruned surface's rows, truncated to the list limit.

    private static ItemList mapItems(Template t) {
        return ((MapTemplate) t).getItemList();
    }

    @Test
    public void mapShowsRowsOverTheHostMap() {
        Template t = CarAppRenderer.renderMap(columnOf(
                text("Arrive 2:41 PM · 19 min", "Hudson Yards → The Met"),
                text("Head east", "0.4 mi"),
                text("Turn left", "1.1 mi")), "Route to The Met", 2, null, null);

        assertThat(((MapTemplate) t).getHeader().getTitle().toString())
                .isEqualTo("Route to The Met");
        assertThat(mapItems(t).getItems()).hasSize(2);
        assertThat(((androidx.car.app.model.Row) mapItems(t).getItems().get(0)).getTitle()
                .toString()).isEqualTo("Arrive 2:41 PM · 19 min");
    }

    @Test
    public void mapRefusesTheUnusableFloor() {
        assertThrows(IllegalArgumentException.class, () -> CarAppRenderer.renderMap(
                columnOf(text("a", "1")), "Route", 0, null, null));
    }

    @Test
    public void mapEmptyShowsTheHonestNote() {
        Template t = CarAppRenderer.renderMap(null, "Route", 6, null, null);

        assertThat(mapItems(t).getItems()).isEmpty();
        assertThat(mapItems(t).getNoItemsMessage().toString()).contains("while driving");
    }

    // Place-list navigation: one row per stop, each navigating through the binder.

    private static ItemList placeItems(Template t) {
        return ((PlaceListNavigationTemplate) t).getItemList();
    }

    @Test
    public void placeListNavigationShowsOneRowPerStop() {
        Template t = CarAppRenderer.renderPlaceListNavigation(columnOf(
                button("c1", "navigate"), button("c2", "navigate"), button("c3", "navigate")),
                "Fast chargers", 6, new FakeBinder(A2uiActionPolicy.Decision.ALLOW), null,
                row -> 966.0);

        PlaceListNavigationTemplate places = (PlaceListNavigationTemplate) t;
        assertThat(places.getHeader().getTitle().toString()).isEqualTo("Fast chargers");
        assertThat(placeItems(t).getItems()).hasSize(3);
        assertThat(((androidx.car.app.model.Row) placeItems(t).getItems().get(0))
                .getOnClickDelegate()).isNotNull();
    }

    @Test
    public void placeRowsLeadWithTheHostFormattedDistance() {
        // Meters in, miles out; the template requires a distance span per row, so the line the
        // span sits on is asserted, not the span object.
        Template t = CarAppRenderer.renderPlaceListNavigation(
                columnOf(button("c1", "navigate")), "Fast chargers", 6, null, null,
                row -> 1770.0);

        assertThat(((androidx.car.app.model.Row) placeItems(t).getItems().get(0)).getTexts()
                .toString()).contains("1.1 mi");
    }

    @Test
    public void placeListNavigationRefusesTheUnusableFloor() {
        assertThrows(IllegalArgumentException.class, () ->
                CarAppRenderer.renderPlaceListNavigation(
                        columnOf(button("c1", "navigate")), "Fast chargers", 0, null, null,
                        row -> 966.0));
    }

    @Test
    public void placeListNavigationEmptyShowsTheHonestNote() {
        Template t = CarAppRenderer.renderPlaceListNavigation(
                null, "Fast chargers", 6, null, null, row -> 966.0);

        assertThat(placeItems(t).getItems()).isEmpty();
        assertThat(placeItems(t).getNoItemsMessage().toString()).contains("while driving");
    }

    @Test
    public void placeRowWithoutADistanceFailsLoudly() {
        // The template rejects a row without a distance span anyway; unknown is refused here.
        assertThrows(IllegalArgumentException.class, () ->
                CarAppRenderer.renderPlaceListNavigation(
                        columnOf(button("c1", "navigate")), "Fast chargers", 6, null, null,
                        row -> -1.0));
    }
}
