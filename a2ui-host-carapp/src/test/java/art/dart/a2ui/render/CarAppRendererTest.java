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

import androidx.annotation.Nullable;

import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Template;

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
 */
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
}
