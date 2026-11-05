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

import static art.dart.a2ui.carui.A2uiHostCardsTest.body;
import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.reading;
import static art.dart.a2ui.carui.A2uiHostCardsTest.row;
import static art.dart.a2ui.carui.A2uiHostCardsTest.titles;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;

/**
 * The charging window's short to watch: offered only parked at the charger once the coffee is
 * ordered, opened in the full-bleed player, which is parked-only on top of VIDEO.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiDriverMediaTest {

    private static final int DRIVE = 0xff;
    private static final String BBB = DemoNativeContract.BBB_TITLE;

    private Application mApp;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        CarUiInstaller.register(mApp);
        CarUxRestrictionsUtil.clearInstancesForTesting();
        Settings.Global.putFloat(mApp.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        PassengerDisplay.resetForTesting();
    }

    /** A passenger surface reporting {@code media} at a settable live position. */
    private static PassengerDisplay.Surface passengerAt(int[] positionMs) {
        return new PassengerDisplay.Surface() {
            @Override
            public void play(PassengerMedia media) {
            }

            @Override
            public void stopMedia() {
            }

            @Override
            public int positionMs() {
                return positionMs[0];
            }
        };
    }

    @Test
    public void theDriversCopyStartsWhereThePassengerIsOnlyInTheSameClip() {
        // The passenger plays Big Buck Bunny (its portrait cut, a crop of this file) at 7.2 s.
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 7200, "bbb_passenger",
                19300)).isEqualTo(7200);
        // Into one loop, as both loop; and as it is while the length is unknown.
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 19800, "bbb_passenger",
                19300)).isEqualTo(500);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 7200, "bbb_passenger",
                0)).isEqualTo(7200);
        // Not playing, another clip, stills or an unknown name: from the top.
        assertThat(PassengerDisplay.startPositionMs(null, 7200, "bbb_passenger", 19300))
                .isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, -1, "bbb_passenger",
                19300)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.ARTWORK, 7200,
                "bbb_passenger", 19300)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.SLIDESHOW, 7200, "", 19300))
                .isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs("nope", 7200, "bbb_passenger", 19300))
                .isEqualTo(0);
        // The Met film plays once and holds its end: a copy joining it once finished starts from
        // the top rather than flashing the last frame; before that it joins where it is.
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.ARTWORK, 36000,
                "met_artwork_film", 36000)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.ARTWORK,
                36000 - PassengerDisplay.FINISHED_MS, "met_artwork_film", 36000)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.ARTWORK, 30000,
                "met_artwork_film", 36000)).isEqualTo(30000);
    }

    @Test
    public void thePassengersPositionIsReadLiveFromItsSurface() {
        int[] position = {4100};
        PassengerDisplay.Surface surface = passengerAt(position);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
        PassengerDisplay.attach(surface);
        // Attached but playing nothing: no position.
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
        PassengerDisplay.report(PassengerMedia.VIDEO, 0);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(4100);
        position[0] = 4600;
        assertThat(PassengerDisplay.positionMs()).isEqualTo(4600);
        PassengerDisplay.detach(surface);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
    }

    @Test
    public void thePlayerJoinsThePassengersBunnyWhereItIsAndElseStartsFromTheTop() {
        A2uiCarUiActivity activity = startedDriving().get();
        atTheCharger(activity);
        order(activity);
        int[] position = {7200};
        PassengerDisplay.Surface surface = passengerAt(position);
        PassengerDisplay.attach(surface);
        PassengerDisplay.report(PassengerMedia.VIDEO, 0);

        tap(activity, BBB);

        // Asked when the file is ready, so it reads where the passenger is by then.
        DemoMediaController.StartAt startAt = activity.mDemoMedia.startAtForTesting();
        assertThat(startAt).isNotNull();
        position[0] = 7700;
        assertThat(startAt.positionMs(19300)).isEqualTo(7700);
        // The passenger stops: from the top.
        PassengerDisplay.report(null, -1);
        assertThat(startAt.positionMs(19300)).isEqualTo(0);
        PassengerDisplay.detach(surface);
    }

    @Test
    public void theShortIsOfferedOnlyParkedAtTheChargerAfterTheOrder() {
        ActivityController<A2uiCarUiActivity> controller = startedDriving();
        A2uiCarUiActivity activity = controller.get();
        atTheCharger(activity);
        // Parked at the charger, nothing ordered yet: no short.
        assertThat(titles(activity)).doesNotContain(BBB);

        order(activity);
        CarUiContentListItem bbb = row(activity, BBB);
        assertThat(body(bbb).get(0)).isEqualTo("Watch while you charge");
        // The card draws exactly the title and that line: the voice hint is not a card line.
        CardListAdapter.Card card = CardListAdapter.cardFor(BBB, body(bbb));
        assertThat(card.mTitle).isEqualTo(BBB);
        assertThat(card.mSubtitle).isEqualTo("Watch while you charge");
        assertThat(card.mMeta).isNull();
        assertThat(card.mExtra).isEmpty();
        assertThat(bbb.getOnClickListener()).isNotNull();
        // Within the cards a parked screen shows whole: charging, pickup, then the short.
        assertThat(activity.renderedRowsForTesting().indexOf(bbb)).isLessThan(4);

        drive(activity, DRIVE);
        assertThat(titles(activity)).doesNotContain(BBB);
        // Parked at the charger again: back.
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(titles(activity)).contains(BBB);
        // A trusted reading that still allows VIDEO but is not parked withholds it too.
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD);
        assertThat(titles(activity)).doesNotContain(BBB);
    }

    @Test
    public void anUntrustedReadingWithholdsTheShortAndTearsThePlayerDown() {
        A2uiCarUiActivity activity = startedDriving().get();
        atTheCharger(activity);
        order(activity);
        tap(activity, BBB);
        assertThat(activity.videoViewForTesting()).isNotNull();

        CarUxRestrictionsUtil.getInstance(activity).disconnect();
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(titles(activity)).doesNotContain(BBB);
    }

    @Test
    public void tappingTheShortOpensTheFullBleedPlayerAndDrivingTearsItDown() {
        A2uiCarUiActivity activity = startedDriving().get();
        atTheCharger(activity);
        order(activity);
        ShadowAlertDialog.reset();

        tap(activity, BBB);

        // Opening is navigation: no confirmation, straight to the player.
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull();
        assertThat(activity.videoViewForTesting()).isInstanceOf(CoverVideoView.class);
        // No captions burned in: centre-cropped.
        assertThat(((android.widget.FrameLayout.LayoutParams) activity.videoViewForTesting()
                .getLayoutParams()).gravity).isEqualTo(android.view.Gravity.CENTER);
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.VISIBLE);
        // The player's own title and credit; the fixture caption is not shown.
        View overlay = activity.videoTitleOverlayForTesting();
        assertThat(overlay.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(((TextView) activity.findViewById(R.id.video_title)).getText().toString())
                .isEqualTo(BBB);
        assertThat(((TextView) activity.findViewById(R.id.video_credit)).getText().toString())
                .isEqualTo("Blender Foundation · CC BY 3.0");
        assertThat(activity.findViewById(R.id.video_caption).getVisibility())
                .isEqualTo(View.GONE);

        // The title fades after a moment and comes back on a tap of the picture.
        shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(A2uiCarUiActivity.VIDEO_TITLE_MS));
        assertThat(overlay.getVisibility()).isEqualTo(View.GONE);
        activity.videoStageForTesting().performClick();
        assertThat(overlay.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(overlay.getAlpha()).isEqualTo(1f);

        drive(activity, DRIVE);
        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(overlay.getVisibility()).isEqualTo(View.GONE);
    }

    /** A trusted reading that still allows VIDEO, but is not parked, takes the player too. */
    @Test
    public void aNonParkedReadingThatAllowsVideoStillTakesThePlayer() {
        A2uiCarUiActivity activity = startedDriving().get();
        atTheCharger(activity);
        order(activity);
        tap(activity, BBB);
        assertThat(activity.videoViewForTesting()).isNotNull();

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD);

        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void closeDismissesThePlayer() {
        A2uiCarUiActivity activity = startedDriving().get();
        atTheCharger(activity);
        order(activity);
        tap(activity, BBB);

        activity.findViewById(R.id.video_close).performClick();

        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        // Closing leaves the card for another look.
        assertThat(titles(activity)).contains(BBB);
    }

    /**
     * A tap that races a reading which still allows VIDEO but is not parked: the stale tree still
     * holds the card, VIDEO passes, and the player's own parked check refuses it.
     */
    @Test
    public void thePlayerIsRefusedAtOpenUnlessParked() {
        ActivityController<A2uiCarUiActivity> controller = startedDriving();
        A2uiCarUiActivity activity = controller.get();
        atTheCharger(activity);
        order(activity);
        CarUiContentListItem bbb = row(activity, BBB);
        // Stopped, the activity hears no reading: the next one has not been drawn yet.
        controller.stop();
        CarUxRestrictionsUtil.getInstance(activity).setUxRestrictions(
                reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD));
        ShadowToast.reset();

        bbb.getOnClickListener().onClick(bbb);
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    @Test
    public void theDriverMediaIsTheExactCorpusComponentWithHostWords() {
        DemoNativeContract.Media media = null;
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        processor.processAll(UseCaseCorpus.byId("ev_charge_coffee").getMessages());
        A2uiComponent root = processor.getSurface("ev_charge_coffee").project(DemoTrace.ROOT_ID);
        for (A2uiComponent child : root.getChildren()) {
            if (DemoNativeContract.EV_DRIVER_MEDIA.equals(child.getId())) {
                assertThat(DemoNativeContract.isDriverMedia("ev_charge_coffee", child)).isTrue();
                assertThat(DemoNativeContract.isDriverMedia("met_visit", child)).isFalse();
                assertThat(DemoNativeContract.changeLabel("ev_charge_coffee", child))
                        .isEqualTo("Video");
                media = DemoNativeContract.videoFor("ev_charge_coffee", child);
            }
        }
        assertThat(media).isNotNull();
        assertThat(media.mPlayer).isTrue();
        assertThat(media.mRawName).isEqualTo("bbb_passenger");
        assertThat(media.mTitle).isEqualTo(BBB);
        assertThat(media.mCredit).isEqualTo("Blender Foundation · CC BY 3.0");
        assertThat(media.mCaption).isEmpty();
    }

    // The story up to the charging window: low battery, the first charger, parked there.

    private ActivityController<A2uiCarUiActivity> startedDriving() {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        CarUxRestrictionsUtil.getInstance(controller.get()).setUxRestrictions(reading(DRIVE));
        controller.start();
        controller.get().selectCase("ev_charge_coffee");
        return controller;
    }

    private static void atTheCharger(A2uiCarUiActivity activity) {
        activity.onVehicleBattery(20);
        activity.onHeard("take me to the first one");
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.mAtCharger).isTrue();
    }

    private static void order(A2uiCarUiActivity activity) {
        activity.onHeard("Order my usual coffee.");
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(confirm).isNotNull();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.mOrderPlaced).isTrue();
    }

    private static void tap(A2uiCarUiActivity activity, String title) {
        CarUiContentListItem item = row(activity, title);
        item.getOnClickListener().onClick(item);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
