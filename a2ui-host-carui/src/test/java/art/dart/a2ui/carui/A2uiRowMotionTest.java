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

import static art.dart.a2ui.carui.A2uiHostCardsTest.confirmOrder;
import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.reading;
import static art.dart.a2ui.carui.A2uiHostCardsTest.titles;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.car.drivingstate.CarUxRestrictions;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.agent.AgentTransport;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** B1: keyed diffs within a reading, an instant swap plus crossfade across one, scale 0 obeyed. */
@RunWith(RobolectricTestRunner.class)
public class A2uiRowMotionTest {

    @Before
    public void setUp() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
        CarUxRestrictionsUtil.clearInstancesForTesting();
        scale(1f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void aContentChangeDiffsInPlaceAndDropsNothingTheReadingAllows() {
        A2uiCarUiActivity activity = visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        activity.selectCase("poi_discovery_order");
        frame();
        List<String> before = titles(activity);
        List<String> events = watch(activity.findViewById(R.id.limited_list));

        confirmOrder(activity);
        frame();

        // Ours, not RecyclerView's default (120 ms adds).
        assertThat(recycler(activity, R.id.limited_list).getItemAnimator().getAddDuration())
                .isEqualTo(180);
        // Keyed by component id: one insert for the card, nothing else rebound or replaced.
        assertThat(events).containsExactly("inserted 0+1");
        List<String> after = titles(activity);
        assertThat(after.get(0)).isEqualTo("DEMO order confirmed · preparing");
        assertThat(after.subList(1, after.size())).containsExactlyElementsIn(before).inOrder();
        assertConsistent(activity);
    }

    @Test
    public void aTighterReadingKeepsExactlyTheRowsItAllows() {
        A2uiCarUiActivity activity = visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        activity.selectCase("poi_discovery_order");
        confirmOrder(activity);
        frame();
        List<String> events = watch(activity.findViewById(R.id.limited_list));

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD);
        frame();

        // A new reading swaps everything at once: no removal is animated, so nothing withheld
        // lingers on screen fading out.
        assertThat(events).containsExactly("changed");
        // NO_KEYBOARD takes the search field and nothing else (the untitled List shows its type).
        assertThat(titles(activity)).containsExactly("DEMO order confirmed · preparing",
                "List", "Preview pickup route", "Order cold brew + bagel",
                "Curated snapshot · Sep 7 · warm, showers possible Thursday").inOrder();
        assertConsistent(activity);
    }

    @Test
    public void aCappedListTakesTheFullRefreshAndStaysConsistent() {
        Application app = RuntimeEnvironment.getApplication();
        int flags = app.getApplicationInfo().flags;
        app.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        try {
            A2uiCarUiActivity activity =
                    visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
            activity.selectCase("poi_discovery_order");
            confirmOrder(activity);
            activity.setAgentTransportForTesting(silentGateway());
            CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(limitContent(2));
            frame();

            // Same reading, one more row: the cap now clamps, so the diff must not be used.
            activity.askAgent("find coffee");
            frame();

            List<String> titles = titles(activity);
            assertThat(titles.get(0)).isEqualTo(
                    activity.getString(R.string.a2ui_brand_thinking_title));
            assertThat(titles).contains("DEMO order confirmed · preparing");
            CarUiRecyclerView limited = activity.findViewById(R.id.limited_list);
            // Two rows and the "limited" message, and the list agrees with its adapter.
            assertThat(limited.getAdapter().getItemCount()).isEqualTo(3);
            assertThat(limited.getLayoutManager().getItemCount()).isEqualTo(3);
        } finally {
            app.getApplicationInfo().flags = flags;
        }
    }

    @Test
    public void aReadingChangeCrossfadesWithinTwoHundredMs() {
        A2uiCarUiActivity activity = visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        View columns = activity.findViewById(R.id.content_columns);
        assertThat(columns.getAlpha()).isEqualTo(1f);

        CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(
                reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED));
        assertThat(columns.getAlpha()).isEqualTo(0f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertThat(columns.getAlpha()).isEqualTo(1f);
    }

    @Test
    public void animatorScaleZeroMeansNoItemAnimatorAndNoFade() {
        scale(0f);
        A2uiCarUiActivity activity = visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        View columns = activity.findViewById(R.id.content_columns);

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);

        // RecyclerView installs a DefaultItemAnimator by itself; scale 0 must take it away.
        assertThat(recycler(activity, R.id.limited_list).getItemAnimator()).isNull();
        assertThat(recycler(activity, R.id.unlimited_list).getItemAnimator()).isNull();
        assertThat(columns.getAlpha()).isEqualTo(1f);
    }

    /** M1: host rows that fill the cap leave nothing hidden reachable by voice, search or note. */
    @Test
    public void hostRowsThatFillTheCapLeaveNoHiddenRowReachable() {
        Application app = RuntimeEnvironment.getApplication();
        int flags = app.getApplicationInfo().flags;
        app.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        try {
            A2uiCarUiActivity activity =
                    visible(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
            activity.selectCase("poi_discovery_order");
            confirmOrder(activity);
            activity.setAgentTransportForTesting(silentGateway());
            activity.askAgent("find coffee");
            CarUxRestrictionsUtil restrictions = CarUxRestrictionsUtil.getInstance(activity);

            // Thinking and pickup take both places; "Find coffee" is in the tree but off screen.
            restrictions.setUxRestrictions(limitContent(2));
            assertThat(titles(activity)).contains("Find coffee");
            assertThat(activity.mSearchItem.isVisible()).isFalse();
            activity.onHeard("find coffee along the route");
            assertThat(String.valueOf(ShadowToast.getTextOfLatestToast()))
                    .isEqualTo("Nothing on screen answers to that");
            assertThat(activity.findViewById(R.id.empty_state).getVisibility())
                    .isEqualTo(View.VISIBLE);

            // Anti-vacuity: one more place shows the field, and then it is reachable.
            restrictions.setUxRestrictions(limitContent(3));
            assertThat(activity.mSearchItem.isVisible()).isTrue();
            activity.onHeard("find coffee along the route");
            assertThat(String.valueOf(ShadowToast.getTextOfLatestToast()))
                    .isNotEqualTo("Nothing on screen answers to that");
            assertThat(activity.findViewById(R.id.empty_state).getVisibility())
                    .isEqualTo(View.GONE);
        } finally {
            app.getApplicationInfo().flags = flags;
        }
    }

    // ---------------------------------------------------------------------------------------

    private static A2uiCarUiActivity visible(CarUxRestrictions initial) {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        CarUxRestrictionsUtil.getInstance(controller.get()).setUxRestrictions(initial);
        A2uiCarUiActivity activity = controller.start().resume().visible().get();
        frame();
        return activity;
    }

    /** Lets layout and item animations run, which is where an inconsistent dispatch throws. */
    private static void frame() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private static CarUxRestrictions limitContent(int maxItems) {
        return new CarUxRestrictions.Builder(true,
                A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT, 0)
                .setMaxCumulativeContentItems(maxItems).build();
    }

    /** A gateway that never answers, so a turn stays pending. */
    private static AgentTransport silentGateway() {
        return new AgentTransport() {
            @Override
            public void send(byte[] request, Executor executor, Callback callback) {
            }

            @Override
            public void close() {
            }
        };
    }

    /** The RecyclerView inside a CarUiRecyclerView, where the item animator actually lives. */
    private static RecyclerView recycler(A2uiCarUiActivity activity, int id) {
        RecyclerView found = find(activity.findViewById(id));
        assertThat(found).isNotNull();
        return found;
    }

    private static RecyclerView find(View view) {
        if (view instanceof RecyclerView) {
            return (RecyclerView) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                RecyclerView found = find(((ViewGroup) view).getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Records what the list's adapter announces, as the RecyclerView hears it. */
    private static List<String> watch(CarUiRecyclerView list) {
        List<String> events = new ArrayList<>();
        list.getAdapter().registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                events.add("changed");
            }

            @Override
            public void onItemRangeChanged(int start, int count) {
                events.add("rebound " + start + "+" + count);
            }

            @Override
            public void onItemRangeChanged(int start, int count, Object payload) {
                events.add("rebound " + start + "+" + count);
            }

            @Override
            public void onItemRangeInserted(int start, int count) {
                events.add("inserted " + start + "+" + count);
            }

            @Override
            public void onItemRangeRemoved(int start, int count) {
                events.add("removed " + start + "+" + count);
            }

            @Override
            public void onItemRangeMoved(int from, int to, int count) {
                events.add("moved " + from + "->" + to);
            }
        });
        return events;
    }

    /** Both lists agree with the rows they read. */
    private static void assertConsistent(A2uiCarUiActivity activity) {
        int rows = activity.renderedRowsForTesting().size();
        CarUiRecyclerView limited = activity.findViewById(R.id.limited_list);
        CarUiRecyclerView unlimited = activity.findViewById(R.id.unlimited_list);
        assertThat(limited.getAdapter().getItemCount()).isEqualTo(rows);
        assertThat(unlimited.getAdapter().getItemCount()).isEqualTo(rows);
        assertThat(limited.getLayoutManager().getItemCount()).isEqualTo(rows);
    }

    private static void scale(float value) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, value);
    }
}
