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
import static art.dart.a2ui.carui.A2uiHostCardsTest.row;
import static art.dart.a2ui.carui.A2uiHostCardsTest.started;
import static art.dart.a2ui.carui.A2uiHostCardsTest.titles;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/** B6: parked video is full-bleed in the activity, and NO_VIDEO takes it away at once. */
@RunWith(RobolectricTestRunner.class)
public class A2uiVideoStageTest {

    @Before
    public void setUp() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
        CarUxRestrictionsUtil.clearInstancesForTesting();
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void noVideoTearsTheFullBleedSurfaceDownWhileAudioStays() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        openVideo(activity);

        FrameLayout stage = activity.videoStageForTesting();
        assertThat(stage.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(activity.videoViewForTesting().getParent()).isSameInstanceAs(stage);
        assertThat(activity.videoViewForTesting().getLayoutParams().width)
                .isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT);
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull();

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);

        assertThat(stage.getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.videoViewForTesting()).isNull();
        // The surface left with its view: only the caption and the close button remain.
        assertThat(stage.getChildCount()).isEqualTo(2);
        assertThat(titles(activity)).doesNotContain("Fifth House city note · Big Buck Bunny");
        assertThat(row(activity, "Play audio / pause").getOnClickListener()).isNotNull();
    }

    @Test
    public void cancelAndBackCloseIt() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        openVideo(activity);
        activity.findViewById(R.id.video_close).performClick();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);

        openVideo(activity);
        activity.onBackPressed();
        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(activity.isFinishing()).isFalse();
    }

    @Test
    public void videoIsRefusedOutrightWhileMoving() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        activity.selectCase("big_buck_bunny");

        activity.onHeard("open Big Buck Bunny");
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.videoViewForTesting()).isNull();
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    /** Audio replaces the video; its stage must not stay up, black and dead, over the list. */
    @Test
    public void playingAudioByVoiceClosesTheVideoStage() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        openVideo(activity);

        activity.onHeard("play audio");
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.videoStageForTesting().getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.videoViewForTesting()).isNull();
    }

    /** The session's album art follows the same reading as everything else on screen. */
    @Test
    public void theMediaSessionIsToldWhetherImagesAreAllowed() {
        A2uiCarUiActivity activity =
                started(A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(activity.mDemoMedia.imagesAllowed()).isFalse();

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.mDemoMedia.imagesAllowed()).isTrue();

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(activity.mDemoMedia.imagesAllowed()).isFalse();
    }

    private static void openVideo(A2uiCarUiActivity activity) {
        activity.selectCase("big_buck_bunny");
        CarUiContentListItem watch = row(activity, "Watch video");
        watch.getOnClickListener().onClick(watch);
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(activity.videoViewForTesting()).isNotNull();
    }
}
