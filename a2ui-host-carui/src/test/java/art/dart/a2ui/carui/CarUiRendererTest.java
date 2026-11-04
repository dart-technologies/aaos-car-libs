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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;

import com.android.car.ui.CarUiText;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil.RestrictionsSource;

import art.dart.a2ui.demo.DemoSurface;
import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import java.util.ArrayList;
import art.dart.a2ui.policy.A2uiActionPolicy;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.policy.A2uiValueLimits;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/** Host B's renderer, and the properties it must share with Host A's. */
public class CarUiRendererTest {

    private static CarUxRestrictions restrictions(int activeFlags) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(activeFlags);
        return uxr;
    }

    private static A2uiComponent leaf(ComponentType type, String title) {
        return new A2uiComponent.Builder(type).setTitle(title).build();
    }

    @Test
    public void containerRootRendersItsChildrenAsRows() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(leaf(ComponentType.TEXT, "a"))
                .addChild(leaf(ComponentType.TEXT, "b"))
                .build();

        assertThat(CarUiRenderer.render(surface)).hasSize(2);
    }

    @Test
    public void nonContainerRootRendersAsASingleRow() {
        assertThat(CarUiRenderer.render(leaf(ComponentType.TEXT, "only"))).hasSize(1);
    }

    @Test
    public void prunedToNothingRendersNoRows_andTheACTIVITYSaysWhy() {
        // No rows: there is nothing to draw. The activity shows EMPTY_WHILE_DRIVING_NOTE on a
        // dedicated view (@id/empty_state), as Host A passes it to setNoItemsMessage.
        assertThat(CarUiRenderer.render(null)).isEmpty();
    }

    @Test
    public void titleAndSubtitleReachTheListItem() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.TEXT)
                        .setTitle("the title")
                        .setSubtitle("the body")
                        .build())
                .build();

        CarUiContentListItem item = (CarUiContentListItem) CarUiRenderer.render(surface).get(0);

        assertThat(item.getTitle()).isNotNull();
        assertThat(item.getTitle().getPreferredText().toString()).isEqualTo("the title");
        assertThat(item.getBody()).isNotNull();
        assertThat(item.getBody().get(0).getPreferredText().toString()).isEqualTo("the body");
    }

    @Test
    public void componentWithoutATitleFallsBackToItsCatalogName() {
        A2uiComponent surface = new A2uiComponent.Builder(ComponentType.COLUMN)
                .addChild(new A2uiComponent.Builder(ComponentType.DIVIDER).build())
                .build();

        CarUiContentListItem item = (CarUiContentListItem) CarUiRenderer.render(surface).get(0);

        assertThat(item.getTitle().getPreferredText().toString()).isEqualTo("Divider");
    }

    @Test
    public void nativeAffordancesAreChosenByType_notByAgentLabel() {
        A2uiComponent checkBox = new A2uiComponent.Builder(ComponentType.CHECK_BOX)
                .setTitle("Not a checkbox-looking label").setAction("select").build();
        A2uiComponent picker = new A2uiComponent.Builder(ComponentType.CHOICE_PICKER)
                .setTitle("Choice").setAction("select").build();
        A2uiComponent fake = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setTitle("CheckBox").setAction("select").build();

        assertThat(CarUiRenderer.nativeActionFor(checkBox))
                .isEqualTo(CarUiContentListItem.Action.CHECK_BOX);
        assertThat(CarUiRenderer.nativeActionFor(picker))
                .isEqualTo(CarUiContentListItem.Action.CHEVRON);
        assertThat(CarUiRenderer.nativeActionFor(fake))
                .isEqualTo(CarUiContentListItem.Action.NONE);
    }

    @Test
    public void nativeCheckBoxDispatchesThroughBinder() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.ALLOW);
        A2uiComponent checkBox = new A2uiComponent.Builder(ComponentType.CHECK_BOX)
                .setId("explicit").setTitle("Allow explicit content")
                .setAction("select").build();

        CarUiContentListItem item = first(CarUiRenderer.render(columnOf(checkBox), binder));
        assertThat(item.getAction()).isEqualTo(CarUiContentListItem.Action.CHECK_BOX);
        assertThat(item.getOnCheckedChangeListener()).isNotNull();
        item.getOnCheckedChangeListener().onCheckedChanged(item, true);
        assertThat(binder.invoked).containsExactly("select");
    }

    @Test
    public void refusedNativeControlIsDisabledAndUnbound() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.REFUSE);
        A2uiComponent checkBox = new A2uiComponent.Builder(ComponentType.CHECK_BOX)
                .setId("explicit").setTitle("Allow explicit content")
                .setAction("select").build();

        CarUiContentListItem item = first(CarUiRenderer.render(columnOf(checkBox), binder));
        assertThat(item.isEnabled()).isFalse();
        assertThat(item.getOnCheckedChangeListener()).isNull();
        assertThat(item.getOnClickListener()).isNull();
    }

    @Test
    public void nativeChromeAppearsOnlyForExactHostCasesAndSurvivingComponents() {
        A2uiComponent tabs = new A2uiComponent.Builder(ComponentType.TABS)
                .setId("tabs").setAction("select").build();
        A2uiComponent field = new A2uiComponent.Builder(ComponentType.TEXT_FIELD)
                .setId("field").setAction("search").build();
        A2uiComponent both = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").addChild(tabs).addChild(field).build();

        A2uiCarUiActivity.NativeChromePlan browse =
                A2uiCarUiActivity.nativeChromeFor("browse_library", both);
        A2uiCarUiActivity.NativeChromePlan poi =
                A2uiCarUiActivity.nativeChromeFor("poi_discovery_order", both);
        A2uiCarUiActivity.NativeChromePlan unknown =
                A2uiCarUiActivity.nativeChromeFor("agent_chose_this_label", both);
        A2uiCarUiActivity.NativeChromePlan pruned =
                A2uiCarUiActivity.nativeChromeFor("poi_discovery_order", null);

        assertThat(browse.mShowBrowseTabs).isTrue();
        assertThat(browse.mShowSearch).isFalse();
        assertThat(poi.mShowSearch).isTrue();
        assertThat(poi.mSearchHint).isEqualTo("Find a place along the route");
        assertThat(unknown.mShowBrowseTabs).isFalse();
        assertThat(unknown.mShowSearch).isFalse();
        assertThat(pruned.mShowSearch).isFalse();
    }

    @Test
    public void comparisonColumnsAreReservedForBaseShowcase() {
        assertThat(A2uiCarUiActivity.usesComparisonColumns(DemoTrace.SURFACE_ID)).isTrue();
        assertThat(A2uiCarUiActivity.usesComparisonColumns("poi_discovery_order")).isFalse();
        assertThat(A2uiCarUiActivity.usesComparisonColumns("big_buck_bunny")).isFalse();
    }

    // Cross-host agreement: the same surface must be legal on both hosts or neither.

    /**
     * Host B's provenance rule matches Host A's on every source. Duplicated per host so the model
     * does not depend on a car-ui-lib type; this test is what makes the duplicate safe.
     */
    @Test
    public void provenanceRuleAgreesWithHostA() {
        for (RestrictionsSource source : RestrictionsSource.values()) {
            for (int mask : new int[]{
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_SETUP,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED}) {
                Set<ComponentType> hostB =
                        A2uiCarUiActivity.allowedFor(source, restrictions(mask));
                // Host A's rule, restated: :a2ui-host-carapp is not on this classpath, by design.
                Set<ComponentType> hostA = (source == RestrictionsSource.DELIVERED
                        || source == RestrictionsSource.DEMO)
                        ? A2uiRestrictionPolicy.allowedComponents(mask)
                        : A2uiRestrictionPolicy.failClosedComponents();

                assertThat(hostB).isEqualTo(hostA);
            }
        }
    }

    /** Both hosts flatten the shared surface to the same row counts ({@code DemoSurfaceTest}). */
    @Test
    public void rowCountsMatchHostAAtEveryLevel() {
        assertThat(rowsAt(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE)).isEqualTo(9);
        assertThat(rowsAt(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO)).isEqualTo(8);
        // Two glanceable Buttons and a Text survive.
        assertThat(rowsAt(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED)).isEqualTo(3);
    }

    private static int rowsAt(int mask) {
        A2uiComponent pruned = A2uiPruner.prune(
                DemoSurface.create(), A2uiRestrictionPolicy.allowedComponents(mask));
        List<CarUiListItem> items = CarUiRenderer.render(pruned);
        return items.size();
    }

    /**
     * Host B's renderer applies no content limit of its own: the app opts into an adapter wrapper.
     * Truncating here would make the activity's two lists identical.
     */
    @Test
    public void rendererAppliesNoContentLimitOfItsOwn() {
        A2uiComponent.Builder big = new A2uiComponent.Builder(ComponentType.COLUMN);
        for (int i = 0; i < 500; i++) {
            big.addChild(leaf(ComponentType.TEXT, "row " + i));
        }

        assertThat(CarUiRenderer.render(big.build())).hasSize(500);
    }

    // Interaction, mirroring CarAppRendererTest.

    private static A2uiComponent buttonWith(String action) {
        return new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy album").setSubtitle("consequential")
                .setAction(action).build();
    }

    private static A2uiComponent columnOf(A2uiComponent child) {
        return new A2uiComponent.Builder(ComponentType.COLUMN).addChild(child).build();
    }

    private static final class FakeBinder implements A2uiActionBinder {
        final A2uiActionPolicy.Decision decision;
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

    private static CarUiContentListItem first(List<CarUiListItem> items) {
        return (CarUiContentListItem) items.get(0);
    }

    @Test
    public void refusedControlGetsNoClickListener() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("purchase")),
                new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        assertThat(first(items).getOnClickListener()).isNull();
    }

    @Test
    public void refusedControlSaysWhy_withTheSameWordsAsHostA() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("purchase")),
                new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        // The constant both hosts read, as its own body line (the subtitle stays within budget).
        // Read through getPreferredText(): CarUiText has no toString.
        assertThat(first(items).getBody()).hasSize(2);
        assertThat(bodyText(first(items))).contains(A2uiActionBinder.REFUSED_NOTE);
        assertThat(bodyText(first(items))).contains("consequential");   // the subtitle survives
    }

    @Test
    public void allowedControlIsClickableAndReachesTheBinder() {
        FakeBinder binder = new FakeBinder(A2uiActionPolicy.Decision.ALLOW);
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("play")), binder);

        CarUiContentListItem item = first(items);
        assertThat(item.getOnClickListener()).isNotNull();
        item.getOnClickListener().onClick(item);
        assertThat(binder.invoked).containsExactly("play");
    }

    @Test
    public void confirmableControlIsOffered() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("purchase")),
                new FakeBinder(A2uiActionPolicy.Decision.CONFIRM));

        assertThat(first(items).getOnClickListener()).isNotNull();
    }

    @Test
    public void withoutABinderNothingIsClickable() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("purchase")));

        assertThat(first(items).getOnClickListener()).isNull();
    }

    // Value limits apply to Host B too, not only the component set.

    private static CarUxRestrictions withLimits(
            int active, int maxString, int maxItems, int maxDepth) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(active);
        when(uxr.getMaxRestrictedStringLength()).thenReturn(maxString);
        when(uxr.getMaxCumulativeContentItems()).thenReturn(maxItems);
        when(uxr.getMaxContentDepth()).thenReturn(maxDepth);
        return uxr;
    }

    @Test
    public void limitsFor_honoursProvenance() {
        // Generous numbers on a reading that never arrived. They are the fail-closed default's own
        // numbers and describe no vehicle, so they must not unlock anything.
        for (RestrictionsSource source : new RestrictionsSource[]{
                RestrictionsSource.NEVER_DELIVERED, RestrictionsSource.DISCONNECTED}) {
            A2uiValueLimits limits = A2uiCarUiActivity.limitsFor(source,
                    withLimits(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 9999, 9999, 9999));

            assertThat(limits.getMaxStringLength())
                    .isEqualTo(A2uiValueLimits.failClosed().getMaxStringLength());
            assertThat(limits.isUnrestricted()).isFalse();
        }
    }

    @Test
    public void limitsFor_agreesWithHostAsRuleAtEveryLevelAndSource() {
        // Restated against A2uiValueLimits, which both hosts must reduce to.
        for (RestrictionsSource source : RestrictionsSource.values()) {
            for (int mask : new int[]{
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
                    A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED}) {
                A2uiValueLimits actual =
                        A2uiCarUiActivity.limitsFor(source, withLimits(mask, 120, 21, 3));
                A2uiValueLimits expected = (source == RestrictionsSource.DELIVERED
                        || source == RestrictionsSource.DEMO)
                        ? A2uiValueLimits.from(mask, 120, 21, 3)
                        : A2uiValueLimits.failClosed();

                assertThat(actual.getMaxStringLength()).isEqualTo(expected.getMaxStringLength());
                assertThat(actual.getMaxContentItems()).isEqualTo(expected.getMaxContentItems());
                assertThat(actual.getMaxContentDepth()).isEqualTo(expected.getMaxContentDepth());
            }
        }
    }

    @Test
    public void anOfferedControlShowsItsSpokenForm_withTheSameWordingAsHostA() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("play")),
                new FakeBinder(A2uiActionPolicy.Decision.ALLOW));

        // The spoken form falls back to the title, not the action name.
        assertThat(bodyText(first(items))).contains(A2uiActionBinder.voiceHint("Buy album"));
        assertThat(bodyText(first(items))).contains("say \u201cbuy album\u201d");
    }

    @Test
    public void aRefusedControlDoesNotAdvertiseAVoiceRoute() {
        List<CarUiListItem> items = CarUiRenderer.render(columnOf(buttonWith("purchase")),
                new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        assertThat(bodyText(first(items))).doesNotContain("say");
        assertThat(bodyText(first(items))).contains(A2uiActionBinder.REFUSED_NOTE);
    }

    /**
     * Every body line, joined for assertion, through getPreferredText() (CarUiText has no
     * toString).
     */
    private static String bodyText(CarUiListItem item) {
        StringBuilder sb = new StringBuilder();
        for (CarUiText line : ((CarUiContentListItem) item).getBody()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line.getPreferredText());
        }
        return sb.toString();
    }

    // The voice-reachable set equals what was drawn. See reachableForVoice.

    private static A2uiComponent threeDeepTree() {
        // A grandchild is the whole point: render() never draws one, so voice must never reach it.
        A2uiComponent grandchild = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy").setAction("purchase")
                .setVoicePhrase("buy the soundtrack").build();
        A2uiComponent child = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("inner").setTitle("Inner").addChild(grandchild).build();
        return new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").setTitle("Root").addChild(child).build();
    }

    @Test
    public void theVoiceReachableSetDropsGrandchildren_becauseNoRendererDrawsThem() {
        A2uiComponent reachable = CarUiRenderer.reachableForVoice(threeDeepTree());

        assertThat(reachable).isNotNull();
        assertThat(reachable.getChildren()).hasSize(1);
        // The child survives -- it is drawn. Its own child does not.
        assertThat(reachable.getChildren().get(0).hasChildren()).isFalse();
    }

    @Test
    public void theReachableSetMatchesTheRowsActuallyRendered() {
        // One reachable child per drawn row.
        A2uiComponent tree = threeDeepTree();

        int drawn = CarUiRenderer.render(tree, null).size();
        A2uiComponent reachable = CarUiRenderer.reachableForVoice(tree);

        assertThat(reachable.getChildren()).hasSize(drawn);
    }

    @Test
    public void aContainerRootIsNotItselfInvocableByVoice() {
        // No row is drawn for a container root, so it must offer nothing to invoke -- otherwise a
        // phrase invokes a control with nothing on screen behind it.
        A2uiComponent root = new A2uiComponent.Builder(ComponentType.COLUMN)
                .setId("root").setTitle("Root").setAction("purchase")
                .setVoicePhrase("root phrase")
                .addChild(new A2uiComponent.Builder(ComponentType.TEXT).setId("t")
                        .setTitle("Text").build())
                .build();

        A2uiComponent reachable = CarUiRenderer.reachableForVoice(root);

        assertThat(reachable.getAction()).isNull();
        assertThat(reachable.getVoicePhrase()).isNull();
    }

    @Test
    public void aLoneControlSurvivesWhole_becauseItIsTheDrawnRow() {
        // A non-container root is the row, so it keeps its action.
        A2uiComponent lone = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy").setAction("purchase").build();

        A2uiComponent reachable = CarUiRenderer.reachableForVoice(lone);

        assertThat(reachable.getAction()).isEqualTo("purchase");
        assertThat(CarUiRenderer.render(lone, null)).hasSize(1);
    }

    @Test
    public void anAbsentTreeIsReachableAsNothing() {
        assertThat(CarUiRenderer.reachableForVoice(null)).isNull();
    }

    @Test
    public void noBodyLineExceedsTheVehiclesStringBudget() {
        // Appending the refusal note to an already-truncated subtitle would exceed the vehicle's
        // string budget (60 + 3 + 25 at failClosed()).
        A2uiValueLimits limits = A2uiValueLimits.failClosed();
        A2uiComponent overlong = new A2uiComponent.Builder(ComponentType.BUTTON)
                .setId("buy").setTitle("Buy album").setAction("purchase")
                .setSubtitle("a subtitle long enough that truncating it lands exactly on the "
                        + "vehicle's limit and leaves no room whatsoever for anything else")
                .build();
        A2uiComponent limited = A2uiPruner.applyLimits(
                new A2uiComponent.Builder(ComponentType.COLUMN).setId("root")
                        .addChild(overlong).build(),
                limits);

        List<CarUiListItem> items =
                CarUiRenderer.render(limited, new FakeBinder(A2uiActionPolicy.Decision.REFUSE));

        for (CarUiText line : ((CarUiContentListItem) first(items)).getBody()) {
            assertThat(line.getPreferredText().length())
                    .isAtMost(limits.getMaxStringLength());
        }
        // ...and the refusal is still said, on its own line rather than not at all.
        assertThat(bodyText(first(items))).contains(A2uiActionBinder.REFUSED_NOTE);
    }
}
