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
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.android.car.ui.CarUiText;
import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;

/** B2: host cards are glanceable, read the trace, and survive drive with no way to act on them. */
@RunWith(RobolectricTestRunner.class)
public class A2uiHostCardsTest {
    private static final String ORDER = "Order cold brew + bagel";
    private static final String PICKUP = "DEMO order confirmed · preparing";
    private static final String ARRIVAL = "Welcome to Fifth House · SoHo";
    private static final String WEATHER =
            "Curated snapshot · Sep 7 · warm, showers possible Thursday";

    private Application mApp;
    private int mOriginalFlags;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        CarUiInstaller.register(mApp);
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity.clearGenuineDestinationRestrictionsForTesting();
        mOriginalFlags = mApp.getApplicationInfo().flags;
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        mApp.getApplicationInfo().flags = mOriginalFlags;
    }

    @Test
    public void thePickupCardReplacesTheToastAndSurvivesDriveWithoutActions() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.selectCase("poi_discovery_order")).isTrue();
        assertThat(titles(activity)).doesNotContain(PICKUP);

        confirmOrder(activity);

        CarUiContentListItem card = row(activity, PICKUP);
        assertThat(activity.renderedRowsForTesting().get(0)).isSameInstanceAs(card);
        assertThat(body(card)).containsExactly("Ready in about 6 min",
                "Fifth House Coffee Atelier · SoHo").inOrder();
        assertGlanceable(card);
        assertThat(String.valueOf(ShadowToast.getTextOfLatestToast())).doesNotContain("done");

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        CarUiContentListItem moving = row(activity, PICKUP);
        assertGlanceable(moving);
        assertThat(body(moving)).contains("Ready in about 6 min");
        // Anti-vacuity: the same reading did prune and refuse around the card.
        assertThat(titles(activity)).doesNotContain("Find coffee");
        assertThat(row(activity, ORDER).getOnClickListener()).isNull();
    }

    @Test
    public void theWeatherContextIsARealRowWithAGlyphAndNoAction() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        activity.selectCase("poi_discovery_order");

        CarUiContentListItem weather = row(activity, WEATHER);
        assertThat(weather.getIcon()).isNotNull();
        assertThat(weather.getPrimaryIconType()).isEqualTo(CarUiContentListItem.IconType.STANDARD);
        assertThat(body(weather)).contains("Umbrella reminder · the route stays glanceable in motion");
        assertGlanceable(weather);
    }

    @Test
    public void returningToParkAfterADriveWithAnOrderShowsTheArrivalHero() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("poi_discovery_order");
        confirmOrder(activity);
        // The concierge story carries on into the city note before it leaves.
        assertThat(activity.selectCase("big_buck_bunny")).isTrue();
        assertThat(titles(activity)).contains(PICKUP);
        assertThat(titles(activity)).doesNotContain(ARRIVAL);

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        assertThat(titles(activity)).doesNotContain(ARRIVAL);
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        assertThat(activity.mArrived).isTrue();
        CarUiContentListItem hero = (CarUiContentListItem) activity.renderedRowsForTesting().get(0);
        assertThat(text(hero.getTitle())).isEqualTo(ARRIVAL);
        assertThat(body(hero)).containsExactly("Your DEMO order is ready · city note unlocked");
        assertThat(hero.getPrimaryIconType()).isEqualTo(CarUiContentListItem.IconType.CONTENT);
        assertGlanceable(hero);
        assertThat(titles(activity)).contains(PICKUP);

        // Moving again ends the moment; the pickup stays.
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        assertThat(titles(activity)).doesNotContain(ARRIVAL);
        assertThat(titles(activity)).contains(PICKUP);
    }

    /** Demo artwork is an image: a Button that survives FULLY_RESTRICTED keeps no poster. */
    @Test
    public void demoArtworkOnSurvivingButtonsFollowsImage() {
        A2uiCarUiActivity moving =
                started(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        moving.selectCase("big_buck_bunny");
        assertThat(row(moving, "Watch video").getIcon()).isNull();
        assertThat(row(moving, "Play audio / pause").getIcon()).isNull();

        // Never delivered: the fail-closed set withholds IMAGE too.
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity unknown =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        unknown.selectCase("big_buck_bunny");
        assertThat(row(unknown, "Watch video").getIcon()).isNull();
        assertThat(row(unknown, "Play audio / pause").getIcon()).isNull();

        // Anti-vacuity: parked, the same rows carry the poster.
        drive(unknown, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(row(unknown, "Watch video").getPrimaryIconType())
                .isEqualTo(CarUiContentListItem.IconType.CONTENT);
        assertThat(row(unknown, "Play audio / pause").getIcon()).isNotNull();
    }

    @Test
    public void noOrderNoCards_andCardsStayOnFifthHouseSurfaces() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("poi_discovery_order");
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.mArrived).isFalse();
        assertThat(titles(activity)).containsNoneOf(PICKUP, ARRIVAL);

        confirmOrder(activity);
        assertThat(activity.selectCase("now_playing")).isTrue();
        assertThat(titles(activity)).doesNotContain(PICKUP);
    }

    @Test
    public void aCaseChosenOnTheBusSpeaksTheTracesOwnTitle() {
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        mApp.sendBroadcast(new Intent(DebugBroadcasts.ACTION_CASE)
                .putExtra(DebugBroadcasts.EXTRA_CASE_ID, "poi_discovery_order"));
        shadowOf(Looper.getMainLooper()).idle();
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertThat(tts).isNotNull();
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);

        assertThat(shadowOf(tts).getLastSpokenText()).isEqualTo("Coffee on the way");
        assertThat(activity.renderedRowsForTesting()).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------

    static A2uiCarUiActivity started(int mask) {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        CarUxRestrictionsUtil.getInstance(controller.get()).setUxRestrictions(reading(mask));
        return controller.start().get();
    }

    static void drive(A2uiCarUiActivity activity, int mask) {
        CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(reading(mask));
        shadowOf(Looper.getMainLooper()).idle();
    }

    static CarUxRestrictions reading(int mask) {
        return new CarUxRestrictions.Builder(mask != 0, mask, 0).build();
    }

    /** Taps the order, then OK on the parked confirmation. */
    static void confirmOrder(A2uiCarUiActivity activity) {
        CarUiContentListItem order = row(activity, ORDER);
        order.getOnClickListener().onClick(order);
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(confirmation).isNotNull();
        // A tapped confirmation is pinned inside the display too: capped, a margin each side.
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        assertThat(confirmation.getWindow().getAttributes().width).isEqualTo(Math.min(
                metrics.widthPixels
                        - 2 * Math.round(A2uiCarUiActivity.DIALOG_MARGIN_DP * metrics.density),
                Math.round(A2uiCarUiActivity.DIALOG_MAX_WIDTH_DP * metrics.density)));
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.mOrderPlaced).isTrue();
    }

    /** No listener, no checkbox, no chevron: nothing on a card can be acted on. */
    static void assertGlanceable(CarUiContentListItem card) {
        assertThat(card.getOnClickListener()).isNull();
        assertThat(card.getOnCheckedChangeListener()).isNull();
        assertThat(card.getAction()).isEqualTo(CarUiContentListItem.Action.NONE);
    }

    static List<String> titles(A2uiCarUiActivity activity) {
        List<String> titles = new ArrayList<>();
        for (CarUiListItem row : activity.renderedRowsForTesting()) {
            if (row instanceof CarUiContentListItem
                    && ((CarUiContentListItem) row).getTitle() != null) {
                titles.add(text(((CarUiContentListItem) row).getTitle()));
            }
        }
        return titles;
    }

    static CarUiContentListItem row(A2uiCarUiActivity activity, String title) {
        for (CarUiListItem row : activity.renderedRowsForTesting()) {
            if (row instanceof CarUiContentListItem
                    && ((CarUiContentListItem) row).getTitle() != null
                    && title.equals(text(((CarUiContentListItem) row).getTitle()))) {
                return (CarUiContentListItem) row;
            }
        }
        throw new AssertionError("no row titled " + title + " in " + titles(activity));
    }

    static List<String> body(CarUiContentListItem row) {
        List<String> lines = new ArrayList<>();
        if (row.getBody() != null) {
            for (CarUiText line : row.getBody()) {
                lines.add(text(line));
            }
        }
        return lines;
    }

    static String text(CarUiText text) {
        return text.getPreferredText().toString();
    }
}
