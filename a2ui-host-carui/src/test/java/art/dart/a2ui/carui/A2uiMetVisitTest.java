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

import static art.dart.a2ui.carui.A2uiDriverStateTest.pruned;
import static art.dart.a2ui.carui.A2uiHostCardsTest.assertGlanceable;
import static art.dart.a2ui.carui.A2uiHostCardsTest.body;
import static art.dart.a2ui.carui.A2uiHostCardsTest.drive;
import static art.dart.a2ui.carui.A2uiHostCardsTest.reading;
import static art.dart.a2ui.carui.A2uiHostCardsTest.row;
import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static art.dart.a2ui.carui.A2uiHostCardsTest.titles;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

/** The Met, safely: what motion takes away, by the policy, and what it leaves. */
@RunWith(RobolectricTestRunner.class)
public class A2uiMetVisitTest {

    private static final int DRIVE = 0xff;
    private static final String ETA = "Arrive 2:41 PM · 19 min";
    private static final String BRIEF = "Today at The Met";
    private static final String GALLERY = "Artwork highlights";
    private static final String FILM = "Film · Slideshow";
    private static final String RESERVE = "Reserve entry for two at 3 PM";
    private static final String TICKET = "Timed entry · Thu Nov 5 · 3:00 PM · 2 guests · DEMO";
    private static final String SOURCE = "Source · metmuseum.org — Hours and admission";

    @Before
    public void setUp() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
        CarUxRestrictionsUtil.clearInstancesForTesting();
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void thePolicyRemovesMapSourcesAndArtworkInMotion() {
        // The trees themselves, without the host: depth cuts map and citations, type policy the
        // Modal and the Video.
        A2uiComponent parked = pruned("met_visit", reading(0));
        A2uiComponent moving = pruned("met_visit", reading(DRIVE));
        assertThat(A2uiPruner.countOfType(parked, ComponentType.IMAGE)).isEqualTo(7);
        assertThat(A2uiPruner.countOfType(moving, ComponentType.IMAGE)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(moving, ComponentType.MODAL)).isEqualTo(0);
        assertThat(A2uiPruner.countOfType(moving, ComponentType.VIDEO)).isEqualTo(0);
        assertThat(A2uiCarUiActivity.findRendered(moving, "src1")).isNull();
        assertThat(A2uiCarUiActivity.findRendered(moving, "route")).isNotNull();
        assertThat(A2uiCarUiActivity.findRendered(moving, "audio")).isNotNull();
    }

    @Test
    public void routeMapAndSourcesShowParkedAndLeaveWithMotion() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.selectCase("met_visit")).isTrue();
        assertThat(activity.visualPanelForTesting().getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(body(row(activity, BRIEF))).contains(SOURCE);
        assertThat(titles(activity)).containsAtLeast(ETA, GALLERY, FILM, RESERVE);

        drive(activity, DRIVE);

        assertThat(activity.visualPanelForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(body(row(activity, BRIEF))).doesNotContain(SOURCE);
        assertThat(titles(activity)).containsNoneOf(GALLERY, FILM);
        // What motion keeps: the ETA, the brief's summary, and the refused reservation.
        assertThat(titles(activity)).containsAtLeast(ETA, BRIEF, RESERVE);
        assertThat(row(activity, RESERVE).getOnClickListener()).isNull();
    }

    @Test
    public void theSlideshowOpensParkedAndLeavesWithMotion() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("met_visit");
        CarUiContentListItem gallery = row(activity, GALLERY);
        gallery.getOnClickListener().onClick(gallery);
        shadowOf(Looper.getMainLooper()).idle();
        ArtworkSlideshow show = activity.slideshowForTesting();
        assertThat(show.isShowing()).isTrue();
        assertThat(show.size()).isEqualTo(6);

        drive(activity, DRIVE);
        assertThat(show.isShowing()).isFalse();

        // And it cannot be reopened in motion: its row is gone.
        assertThat(titles(activity)).doesNotContain(GALLERY);
    }

    @Test
    public void theReservationIsParkedOnlyAndBecomesAGlanceableTicket() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("met_visit");
        // A transcript as a speech service writes it.
        activity.onHeard("Reserve entry for 2 at 3 PM.");
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(confirm).isNotNull();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        CarUiContentListItem ticket = row(activity, TICKET);
        assertGlanceable(ticket);
        assertThat(titles(activity)).doesNotContain(RESERVE);

        int parkedRows = activity.renderedRowsForTesting().size();
        drive(activity, DRIVE);
        assertGlanceable(row(activity, TICKET));
        String expected = ChangeSummary.between(
                A2uiCarUiActivity.withoutComponent(pruned("met_visit", reading(0)), "reserve"),
                A2uiCarUiActivity.withoutComponent(pruned("met_visit", reading(DRIVE)),
                        "reserve"),
                parkedRows, activity.renderedRowsForTesting().size(), false,
                c -> DemoNativeContract.changeLabel("met_visit", c)).text();
        assertThat(activity.changeStripForTesting().getText().toString()).isEqualTo(expected);
        assertThat(expected).isEqualTo("Map, sources & artwork hidden · 7→5 rows");
    }

    @Test
    public void aSpokenReservationInMotionIsRefusedWithoutADialog() {
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("met_visit");
        ShadowAlertDialog.reset();
        activity.onHeard("reserve entry for two at three");
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull();
        assertThat(titles(activity)).doesNotContain(TICKET);
    }

    @Test
    public void parkingAfterTheDriveShowsTheArrivalHero() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("met_visit");
        activity.onHeard("reserve entry for two at three");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        shadowOf(Looper.getMainLooper()).idle();
        drive(activity, DRIVE);
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        assertThat(activity.mArrived).isTrue();
        assertThat(titles(activity)).contains("Arrived · The Met Fifth Avenue");
        assertThat(activity.visualPanelForTesting().getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void theAudioGuideCardIsTextOnlyAndFollowsThePassengerFilm() {
        PassengerDisplay.resetForTesting();
        A2uiCarUiActivity activity = started(DRIVE);
        activity.selectCase("met_visit");
        assertThat(titles(activity)).doesNotContain("Now playing · Audio guide");

        PassengerDisplay.report(PassengerMedia.ARTWORK, 2);
        shadowOf(Looper.getMainLooper()).idle();
        CarUiContentListItem guide = row(activity, "Now playing · Audio guide");
        assertThat(body(guide)).containsExactly("Aristotle with a Bust of Homer (3/6)");
        assertGlanceable(guide);
        // A glyph, never artwork, in motion.
        assertThat(guide.getPrimaryIconType()).isEqualTo(CarUiContentListItem.IconType.STANDARD);

        PassengerDisplay.report(PassengerMedia.ARTWORK, 3);
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(body(row(activity, "Now playing · Audio guide")))
                .containsExactly("The Harvesters (4/6)");
        PassengerDisplay.report(null, -1);
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(titles(activity)).doesNotContain("Now playing · Audio guide");
        PassengerDisplay.resetForTesting();
    }

    @Test
    public void theArtworkToggleIsParkedOnlyAndFlipsFilmAndStills() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("met_visit");
        CarUiContentListItem toggle = row(activity, FILM);
        assertThat(body(toggle)).containsExactly("Showing: slideshow");
        toggle.getOnClickListener().onClick(toggle);
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.mArtworkFilm).isTrue();
        assertThat(body(row(activity, FILM))).containsExactly("Showing: film");

        drive(activity, DRIVE);
        assertThat(titles(activity)).doesNotContain(FILM);
    }

    @Test
    public void filmTimingComesFromFilmJsonElseASixSecondCadence() {
        assertThat(MetArtworks.parseStarts(null))
                .isEqualTo(new long[]{0, 6000, 12000, 18000, 24000, 30000});
        String json = "{\"works\":[{\"objectID\":436535,\"startMs\":0},"
                + "{\"objectID\":437881,\"startMs\":9000},{\"objectID\":437394,\"startMs\":17500},"
                + "{\"objectID\":435809,\"startMs\":26000},{\"objectID\":436105,\"startMs\":34000},"
                + "{\"objectID\":435868,\"startMs\":43000}]}";
        long[] starts = MetArtworks.parseStarts(json);
        assertThat(starts).isEqualTo(new long[]{0, 9000, 17500, 26000, 34000, 43000});
        assertThat(MetArtworks.segmentAt(starts, 8999)).isEqualTo(0);
        assertThat(MetArtworks.segmentAt(starts, 9000)).isEqualTo(1);
        assertThat(MetArtworks.segmentAt(starts, 60000)).isEqualTo(5);
        // Partial timing would desynchronise the card: the cadence instead.
        assertThat(MetArtworks.parseStarts("[{\"objectID\":436535,\"startMs\":0}]"))
                .isEqualTo(MetArtworks.parseStarts(null));
        assertThat(MetArtworks.parseStarts("not json")).isEqualTo(MetArtworks.parseStarts(null));
    }

    @Test
    public void voicePhrasesAreNormalisedNotLoosened() {
        assertThat(VoicePhrase.canonical("Reserve entry for 2 at 3 PM."))
                .isEqualTo("reserve entry for two at three");
        assertThat(VoicePhrase.canonical("Show the highlights, please!"))
                .isEqualTo("show the highlights");
        // Words are not invented or dropped beyond clock suffixes and courtesy.
        assertThat(VoicePhrase.canonical("reserve entry for two at 3 today"))
                .isEqualTo("reserve entry for two at three today");
    }
}
