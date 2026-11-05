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

import android.car.drivingstate.CarUxRestrictions;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiPruner;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

/** The vehicle-state chip and the "what changed" line, both read from the policy's output. */
@RunWith(RobolectricTestRunner.class)
public class A2uiDriverStateTest {

    private static final int DRIVE = 0xff; // what this AVD's car service reports in motion

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
    public void bannerNamesTheDeliveredStateAndNeverClaimsOneItWasNotTold() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(banner(activity)).isEqualTo("Parked");
        // The longer line is spoken with the chip, not drawn in it.
        assertThat(activity.bannerDetailForTesting().getText().toString())
                .isEqualTo("Full experience available");
        assertThat(activity.bannerDetailForTesting().getVisibility()).isEqualTo(View.GONE);
        View chip = activity.findViewById(R.id.drive_banner);
        assertThat(chip.getContentDescription().toString())
                .isEqualTo("Parked. Full experience available.");
        int parkedFill = chip.getBackgroundTintList().getDefaultColor();

        drive(activity, DRIVE);
        assertThat(banner(activity)).isEqualTo("Driving");
        // The state reads before the word does: a different fill, the driving token.
        assertThat(chip.getBackgroundTintList().getDefaultColor()).isNotEqualTo(parkedFill);
        assertThat(chip.getBackgroundTintList().getDefaultColor()).isEqualTo(
                activity.getColor(R.color.a2ui_brand_state_driving));
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        assertThat(banner(activity)).isEqualTo("Driving");

        // Injected: Simulated, never Driving, whatever the mask.
        CarUxRestrictionsUtil.getInstance(activity).setDemoUxRestrictions(reading(DRIVE));
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(banner(activity)).isEqualTo("Simulated");

        // Never delivered: Safe mode, and the fail-closed prune stands behind it.
        CarUxRestrictionsUtil.clearInstancesForTesting();
        A2uiCarUiActivity unknown =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        assertThat(banner(unknown)).isEqualTo("Safe mode");
    }

    @Test
    public void bannerKindIsTheProvenanceRuleNotTheMaskAlone() {
        CarUxRestrictionsUtil.RestrictionsSource delivered =
                CarUxRestrictionsUtil.RestrictionsSource.DELIVERED;
        assertThat(DriverState.kindFor(delivered, 0)).isEqualTo(DriverState.Kind.PARKED);
        assertThat(DriverState.kindFor(delivered, 0x10)).isEqualTo(DriverState.Kind.DRIVING);
        assertThat(DriverState.kindFor(CarUxRestrictionsUtil.RestrictionsSource.DEMO, 0))
                .isEqualTo(DriverState.Kind.DEMO);
        for (CarUxRestrictionsUtil.RestrictionsSource untrusted : Arrays.asList(
                CarUxRestrictionsUtil.RestrictionsSource.NEVER_DELIVERED,
                CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED)) {
            // A never-delivered reading is byte-identical to BASELINE; it must not read PARKED.
            assertThat(DriverState.kindFor(untrusted, 0)).isEqualTo(DriverState.Kind.SAFE_MODE);
        }
    }

    @Test
    public void theChangeLineIsTheDiffOfTheTwoPrunedTrees() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(activity.selectCase("big_buck_bunny")).isTrue();
        View strip = activity.changeStripForTesting();
        // A case switch is not a restriction change.
        assertThat(strip.getVisibility()).isEqualTo(View.GONE);

        drive(activity, DRIVE);

        // Recomputed here from the policy, not read back from the activity.
        A2uiComponent before = pruned("big_buck_bunny", reading(0));
        A2uiComponent after = pruned("big_buck_bunny", reading(DRIVE));
        String expected = ChangeSummary.between(before, after, DriverState.Kind.DRIVING, false,
                c -> DemoNativeContract.changeLabel("big_buck_bunny", c)).text();
        assertThat(strip.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(text(activity)).isEqualTo(expected);
        // Anti-vacuity: the policy did remove search and video, and the line says so. No row
        // count: the cards on screen are the count.
        assertThat(text(activity)).isEqualTo("Put away for the drive: search, video");
        assertThat(strip.isClickable()).isFalse();
        assertThat(strip.isFocusable()).isFalse();
        // In the header row beside the chip, holding the context title's place: never over a
        // card.
        assertThat(strip.getParent()).isSameInstanceAs(
                activity.findViewById(R.id.surface_heading).getParent());
        assertThat(activity.findViewById(R.id.surface_heading).getVisibility())
                .isEqualTo(View.INVISIBLE);

        shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(A2uiCarUiActivity.CHANGE_STRIP_MS + 50));
        assertThat(strip.getVisibility()).isEqualTo(View.GONE);
        assertThat(activity.findViewById(R.id.surface_heading).getVisibility())
                .isEqualTo(View.VISIBLE);

        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(text(activity)).isEqualTo("Parked: full detail is back");
    }

    @Test
    public void aDifferentReadingProducesADifferentLine() {
        A2uiCarUiActivity activity = started(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.selectCase("big_buck_bunny");
        drive(activity, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        // NO_VIDEO alone keeps search: a hardcoded line could not say this.
        assertThat(text(activity)).isEqualTo("Put away for the drive: video");
    }

    @Test
    public void summaryNamesOnlyTopmostRemovalsAndJoinsThemReadably() {
        A2uiComponent child = new A2uiComponent.Builder(
                art.dart.a2ui.model.ComponentType.IMAGE).setId("art").build();
        A2uiComponent modal = new A2uiComponent.Builder(art.dart.a2ui.model.ComponentType.MODAL)
                .setId("gallery").addChild(child).build();
        A2uiComponent root = new A2uiComponent.Builder(art.dart.a2ui.model.ComponentType.COLUMN)
                .setId("root").addChild(modal).build();
        A2uiComponent bare = new A2uiComponent.Builder(art.dart.a2ui.model.ComponentType.COLUMN)
                .setId("root").build();

        ChangeSummary hidden = ChangeSummary.between(root, bare, DriverState.Kind.DRIVING, true,
                c -> null);
        assertThat(hidden.mHidden).containsExactly("Pop-ups");
        assertThat(hidden.text()).isEqualTo("Put away for the drive: pop-ups · Audio continues");
        assertThat(ChangeSummary.between(root, root, DriverState.Kind.DRIVING, false, c -> null)
                .text()).isEqualTo("Nothing put away");
        // Host cards join the same list; a name is said once.
        assertThat(ChangeSummary.between(root, bare, DriverState.Kind.DRIVING, false, c -> null,
                Arrays.asList("Order", "Pop-ups")).text())
                .isEqualTo("Put away for the drive: pop-ups, order");
        assertThat(ChangeSummary.between(bare, root, DriverState.Kind.DEMO, false, c -> null)
                .text()).isEqualTo("Back: pop-ups");
        assertThat(ChangeSummary.between(root, bare, DriverState.Kind.SAFE_MODE, false, c -> null)
                .text()).isEqualTo("Put away until the car reports: pop-ups");
        // A parked reading prunes nothing: whatever left, left with the journey.
        assertThat(ChangeSummary.between(bare, root, DriverState.Kind.PARKED, true, c -> null,
                Collections.singletonList("Suggestion")).text())
                .isEqualTo("Parked: full detail is back");
        assertThat(ChangeSummary.list(Arrays.asList("Map", "Sources", "Artwork", "Brief",
                "Video"))).isEqualTo("map, sources, artwork, brief +1 more");
        assertThat(ChangeSummary.list(Collections.singletonList("Map"))).isEqualTo("map");
    }

    // ---------------------------------------------------------------------------------------

    private static String banner(A2uiCarUiActivity activity) {
        return activity.bannerTitleForTesting().getText().toString();
    }

    private static String text(A2uiCarUiActivity activity) {
        return activity.changeStripForTesting().getText().toString();
    }

    /** The policy's tree for a corpus case under one reading, built without the activity. */
    static A2uiComponent pruned(String caseId, CarUxRestrictions reading) {
        A2uiMessageProcessor processor = DemoTrace.newProcessor();
        processor.processAll(UseCaseCorpus.byId(caseId).getMessages());
        A2uiComponent projected = processor.getSurface(caseId).project(DemoTrace.ROOT_ID);
        CarUxRestrictionsUtil.RestrictionsSource delivered =
                CarUxRestrictionsUtil.RestrictionsSource.DELIVERED;
        A2uiComponent pruned = A2uiPruner.prune(projected,
                A2uiCarUiActivity.allowedFor(delivered, reading));
        return pruned == null ? null : A2uiPruner.applyLimits(pruned,
                A2uiCarUiActivity.limitsFor(delivered, reading));
    }
}
