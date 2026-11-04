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

import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.reading;
import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowApplication;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The passenger display's own decision, the debug guard on its trigger, and the closing card. */
@RunWith(RobolectricTestRunner.class)
public class A2uiPassengerAndEndCardTest {

    private static final int DRIVE = 0xff;
    private Application mApp;
    private int mOriginalFlags;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        CarUiInstaller.register(mApp);
        CarUxRestrictionsUtil.clearInstancesForTesting();
        PassengerDisplay.resetForTesting();
        mOriginalFlags = mApp.getApplicationInfo().flags;
        Settings.Global.putFloat(mApp.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        mApp.getApplicationInfo().flags = mOriginalFlags;
        PassengerDisplay.resetForTesting();
    }

    @Test
    public void passengerAndEndCardReceiversExistOnlyOnDebuggableBuilds() {
        mApp.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        assertThat(registered()).isEmpty();

        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        // Anti-vacuity: the debuggable path does register both.
        assertThat(registered()).containsAtLeast(DebugBroadcasts.ACTION_PASSENGER,
                DebugBroadcasts.ACTION_END_CARD);
    }

    @Test
    public void aReleaseBuildIgnoresAPassengerBroadcast() {
        mApp.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        mApp.sendBroadcast(passenger("video"));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(PassengerDisplay.requested()).isNull();
    }

    @Test
    public void aRequestWaitsForThePassengerSurfaceThenItDecidesWithItsOwnReading() {
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        mApp.sendBroadcast(passenger("video"));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(PassengerDisplay.requested().mName).isEqualTo(PassengerMedia.VIDEO);
        assertThat(PassengerDisplay.surface()).isNull();

        A2uiPassengerActivity passenger = Robolectric.buildActivity(
                A2uiPassengerActivity.class).create().start().get();
        assertThat(passenger.videoForTesting()).isNotNull();
        assertThat(PassengerDisplay.playing()).isEqualTo(PassengerMedia.VIDEO);

        // Its display starts restricting: the video goes, its surface with it.
        CarUxRestrictionsUtil.getInstance(passenger).setUxRestrictions(reading(DRIVE));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(passenger.videoForTesting()).isNull();
        assertThat(passenger.messageForTesting().getText().toString())
                .isEqualTo(mApp.getString(R.string.a2ui_passenger_held));
        assertThat(PassengerDisplay.playing()).isNull();
    }

    @Test
    public void theDefaultDisplayNeverGetsADemoZoneReading() {
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity driver = started(DRIVE);
        A2uiPassengerActivity onDefault = Robolectric.buildActivity(
                A2uiPassengerActivity.class).create().start().get();

        assertThat(driver.onPassengerRequest("video")).isTrue();

        // Robolectric places it on display 0, the driver's: held, and the reading left live.
        assertThat(onDefault.videoForTesting()).isNull();
        assertThat(CarUxRestrictionsUtil.getInstance(onDefault).getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
        assertThat(CarUxRestrictionsUtil.getInstance(onDefault).getCurrentRestrictions()
                .getActiveRestrictions()).isEqualTo(DRIVE);
    }

    @Test
    public void stopTearsTheVideoDownAndUnknownMediaIsIgnored() {
        mApp.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        A2uiCarUiActivity driver = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        A2uiPassengerActivity passenger = Robolectric.buildActivity(
                A2uiPassengerActivity.class).create().start().get();
        assertThat(driver.onPassengerRequest("not-a-media")).isFalse();
        assertThat(passenger.videoForTesting()).isNull();

        driver.onPassengerRequest("video");
        assertThat(passenger.videoForTesting()).isNotNull();
        driver.onPassengerRequest(PassengerDisplay.STOP);
        assertThat(passenger.videoForTesting()).isNull();
        assertThat(PassengerDisplay.requested()).isNull();
    }

    @Test
    public void theEndCardIsParkedOnlyAndLeavesOnTheFirstRestriction() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        View card = activity.endCardForTesting();
        assertThat(activity.showEndCard()).isTrue();
        assertThat(card.getVisibility()).isEqualTo(View.VISIBLE);
        LinearLayout bullets = activity.findViewById(R.id.end_card_bullets);
        assertThat(bullets.getChildCount()).isEqualTo(4);
        assertThat(((TextView) activity.findViewById(R.id.end_card_footer)).getText().toString())
                .isEqualTo(mApp.getString(R.string.a2ui_end_card_footer));

        drive(activity, DRIVE);
        assertThat(card.getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.showEndCard()).isFalse();
        assertThat(card.getVisibility()).isEqualTo(View.GONE);

        // An untrusted reading is never "parked".
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity unknown =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        assertThat(unknown.showEndCard()).isFalse();
    }

    @Test
    public void theSlideshowAdvancesOnItsOwnTimerAndStopsCleanly() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        FrameLayout stage = new FrameLayout(activity);
        ArtworkSlideshow show = new ArtworkSlideshow(stage, new ImageView(activity),
                new ImageView(activity), new TextView(activity), new TextView(activity),
                new TextView(activity), new TextView(activity), () -> false);
        assertThat(show.show(new ArrayList<>())).isFalse();
        assertThat(show.show(Arrays.asList(
                new ArtworkSlideshow.Slide(R.drawable.demo_album_art, "One", "a", "credit"),
                new ArtworkSlideshow.Slide(R.drawable.demo_media_poster, "Two", null, null))))
                .isTrue();
        assertThat(show.isShowing()).isTrue();
        assertThat(show.index()).isEqualTo(0);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ArtworkSlideshow.ADVANCE_MS));
        assertThat(show.index()).isEqualTo(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ArtworkSlideshow.ADVANCE_MS));
        assertThat(show.index()).isEqualTo(0);

        show.stop();
        assertThat(show.isShowing()).isFalse();
        assertThat(stage.getVisibility()).isEqualTo(View.GONE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ArtworkSlideshow.ADVANCE_MS));
        assertThat(show.index()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------------------

    private static Intent passenger(String media) {
        return new Intent(DebugBroadcasts.ACTION_PASSENGER)
                .putExtra(DebugBroadcasts.EXTRA_MEDIA, media);
    }

    private List<String> registered() {
        List<String> found = new ArrayList<>();
        for (ShadowApplication.Wrapper w : shadowOf(mApp).getRegisteredReceivers()) {
            IntentFilter filter = w.getIntentFilter();
            if (filter == null) continue;
            for (int i = 0; i < filter.countActions(); i++) {
                String action = filter.getAction(i);
                if (action.equals(DebugBroadcasts.ACTION_PASSENGER)
                        || action.equals(DebugBroadcasts.ACTION_END_CARD)) {
                    found.add(action);
                }
            }
        }
        return found;
    }
}
