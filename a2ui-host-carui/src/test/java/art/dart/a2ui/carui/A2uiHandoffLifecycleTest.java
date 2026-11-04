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

import android.car.drivingstate.CarUxRestrictions;
import android.os.Looper;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import android.app.Application;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDisplayManager;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.demo.DemoRestrictions;

import java.util.ArrayList;
import java.util.List;

/**
 * The handoff lifecycle in the Activity: which scope is rendered, and registering and releasing the
 * destination's restriction listener. {@code A2uiHostWiringTest} covers the pipeline.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiHandoffLifecycleTest {

    @Test
    public void debugBusRequiresDebuggableAppOnANonUserBuild() {
        ApplicationInfo info = new ApplicationInfo();
        info.flags = ApplicationInfo.FLAG_DEBUGGABLE;
        assertThat(A2uiCarUiActivity.debugBusAllowed(info, "userdebug")).isTrue();
        assertThat(A2uiCarUiActivity.debugBusAllowed(info, "user")).isFalse();
        info.flags = 0;
        assertThat(A2uiCarUiActivity.debugBusAllowed(info, "eng")).isFalse();
    }

    /**
     * Installs car-ui-lib's LayoutInflater factory, which {@code CarUiInstaller} (a ContentProvider
     * Robolectric does not run) would; without it the CarUiRecyclerView tag, an interface, fails to
     * inflate. The handoff Presentation hit the same defect on device.
     */
    @Before
    public void installCarUiFactory() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
    }

    /** The static per-display instance cache outlives a test; clear it so state cannot leak. */
    @Before
    public void clearRestrictionInstances() {
        CarUxRestrictionsUtil.clearInstancesForTesting();
        // The capture is static (it must outlive Activity recreation), so clear it per test too.
        A2uiCarUiActivity.clearGenuineDestinationRestrictionsForTesting();
    }

    /** Does the Activity even come up under Robolectric? Everything else depends on this. */
    @Test
    public void theActivityCanBeCreated() {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        assertThat(controller.get()).isNotNull();
    }

    /** And its own display's restriction instance is reachable, fail-closed, without car service. */
    @Test
    public void theActivityHasAFailClosedRestrictionInstance() {
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().get();
        CarUxRestrictionsUtil restrictions = CarUxRestrictionsUtil.getInstance(activity);
        assertThat(restrictions.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    }

    // Watching and releasing the destination.

    @Test
    public void handingOffWatchesTheDestination_andItsRowsFollowThatDisplay() {
        int destinationId = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        A2uiCarUiActivity activity = started();

        activity.demonstrateHandoff(destinationId);

        assertThat(activity.isWatchingDestinationForTesting()).isTrue();
        assertThat(activity.handoffRowsForTesting()).isNotEmpty();
        // No car service in this environment, so the destination is fail-closed and the
        // consequential control is not offered there.
        assertThat(offeredTitles(activity)).doesNotContain("Buy album");

        // Only a registered listener turns this into a re-render; the assertion is on the effect.
        CarUxRestrictionsUtil destination =
                CarUxRestrictionsUtil.getInstanceForDisplay(activity, destinationId);
        destination.setUxRestrictions(baseline());

        assertThat(offeredTitles(activity)).contains("Buy album");
    }

    @Test
    public void stoppingTheActivityReleasesTheDestination_soItsChangesStopArriving() {
        int destinationId = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        ActivityController<A2uiCarUiActivity> controller = controller();
        A2uiCarUiActivity activity = controller.get();
        activity.demonstrateHandoff(destinationId);
        assertThat(activity.isWatchingDestinationForTesting()).isTrue();

        CarUxRestrictionsUtil destination =
                CarUxRestrictionsUtil.getInstanceForDisplay(activity, destinationId);
        int watchedWhileHandedOff = destination.observerCountForTesting();
        assertThat(watchedWhileHandedOff).isAtLeast(1);

        controller.pause().stop();

        assertThat(activity.isWatchingDestinationForTesting()).isFalse();
        assertThat(activity.handoffRowsForTesting()).isEmpty();

        // Asserted on the observer set: a leaked listener is inert once mHandoffDestination is
        // null, so rendered output cannot reveal it.
        assertThat(destination.observerCountForTesting()).isLessThan(watchedWhileHandedOff);

        // And the inert half is still worth stating: nothing repopulates either.
        destination.setUxRestrictions(baseline());
        assertThat(activity.handoffRowsForTesting()).isEmpty();
    }

    @Test
    public void asecondHandoffReplacesTheFirstRatherThanStackingListeners() {
        int destinationId = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        A2uiCarUiActivity activity = started();

        activity.demonstrateHandoff(destinationId);
        activity.demonstrateHandoff(destinationId);

        assertThat(activity.isWatchingDestinationForTesting()).isTrue();
        int rowsAfterTwo = activity.handoffRowsForTesting().size();

        // One refresh, not two appended. mHandoffItems is cleared and refilled per render, so a
        // doubled list would mean the release-then-register sequence had been skipped.
        activity.demonstrateHandoff(destinationId);
        assertThat(activity.handoffRowsForTesting()).hasSize(rowsAfterTwo);
    }

    /**
     * A dismissal this activity did not initiate (a display removed) still releases the
     * destination; onStop releases explicitly, so it proves nothing about the dismiss listener.
     */
    @Test
    public void aSystemDismissedPresentationStillReleasesTheDestination() {
        int destinationId = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        A2uiCarUiActivity activity = started();
        activity.demonstrateHandoff(destinationId);

        // The presentation really is up under Robolectric — otherwise this test would be asserting
        // about a code path that never ran, which is the failure mode it exists to prevent.
        assertThat(activity.handoffPresentationForTesting()).isNotNull();
        assertThat(activity.isWatchingDestinationForTesting()).isTrue();
        CarUxRestrictionsUtil destination =
                CarUxRestrictionsUtil.getInstanceForDisplay(activity, destinationId);
        int watched = destination.observerCountForTesting();
        assertThat(watched).isAtLeast(1);

        // Dismiss it the way the system does: straight at the dialog, not through the activity.
        activity.handoffPresentationForTesting().dismiss();
        // Dialog posts its dismiss callback, as production does, so idle before asserting.
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.isWatchingDestinationForTesting()).isFalse();
        assertThat(destination.observerCountForTesting()).isLessThan(watched);
    }

    // Multi-display sequencing: both defects need two displays.

    @Test
    public void asupersededPresentationsLateDismissalDoesNotTearDownItsReplacement() {
        // dismiss() is async, so A's callback lands after B is installed and must not release B.
        int displayA = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        int displayB = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        A2uiCarUiActivity activity = started();

        activity.demonstrateHandoff(displayA);
        activity.demonstrateHandoff(displayB);
        // Let A's posted dismissal arrive, which is the whole point: before the fix this is the
        // moment B was destroyed.
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(activity.isWatchingDestinationForTesting()).isTrue();
        assertThat(activity.handoffRowsForTesting()).isNotEmpty();
        assertThat(CarUxRestrictionsUtil.getInstanceForDisplay(activity, displayB)
                .observerCountForTesting()).isAtLeast(1);
    }

    @Test
    public void overridingASECONDDisplayDoesNotLoseTheFIRSTDisplaysGenuineReading() {
        // One saved slot loses display A the moment display B is overridden. The restore then
        // reports "nothing captured" and A stays on the synthetic reading permanently.
        int displayA = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        int displayB = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        A2uiCarUiActivity activity = started();

        CarUxRestrictions genuineA = restrictions(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);
        CarUxRestrictionsUtil a = CarUxRestrictionsUtil.getInstanceForDisplay(activity, displayA);
        CarUxRestrictionsUtil b = CarUxRestrictionsUtil.getInstanceForDisplay(activity, displayB);
        // A genuine reading each, so provenance is DELIVERED and the capture can happen.
        a.setUxRestrictions(genuineA);
        b.setUxRestrictions(restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_KEYBOARD));

        activity.demonstrateHandoff(displayA);
        activity.overrideDestination(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        activity.demonstrateHandoff(displayB);
        activity.overrideDestination(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        // Now go back and restore A. A single slot has already forgotten it.
        activity.demonstrateHandoff(displayA);
        activity.overrideDestination(-1);

        assertThat(a.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(genuineA.getActiveRestrictions());
    }

    @Test
    public void arecreatedActivityCanStillRestoreAnOverriddenDisplay() {
        // The synthetic reading lives in a static cache that survives recreation, so the capture
        // that undoes it must too.
        int destinationId = ShadowDisplayManager.addDisplay("w1408dp-h792dp-mdpi");
        CarUxRestrictions genuine = restrictions(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);

        ActivityController<A2uiCarUiActivity> firstController = controller();
        A2uiCarUiActivity first = firstController.get();
        CarUxRestrictionsUtil destination =
                CarUxRestrictionsUtil.getInstanceForDisplay(first, destinationId);
        destination.setUxRestrictions(genuine);
        first.demonstrateHandoff(destinationId);
        first.overrideDestination(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        assertThat(destination.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);

        // Actually tear the first Activity down. Without this the test would pass against a capture
        // that dies with the Activity, because nothing ever killed one.
        firstController.pause().stop().destroy();

        // A brand-new Activity, as a configuration change produces. The synthetic reading is still
        // in place because it never belonged to the Activity.
        A2uiCarUiActivity recreated = started();
        recreated.demonstrateHandoff(destinationId);
        recreated.overrideDestination(-1);

        assertThat(destination.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(genuine.getActiveRestrictions());
    }

    // -------------------------------------------------------------------------------------------

    private static CarUxRestrictions restrictions(int active) {
        return new CarUxRestrictions.Builder(
                active != A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, active, 0).build();
    }

    private static ActivityController<A2uiCarUiActivity> controller() {
        return Robolectric.buildActivity(A2uiCarUiActivity.class).create().start();
    }

    private static A2uiCarUiActivity started() {
        return controller().get();
    }

    private static CarUxRestrictions baseline() {
        return new CarUxRestrictions.Builder(
                false, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build();
    }

    /** Titles of destination rows that still carry a click listener. */
    private static List<String> offeredTitles(A2uiCarUiActivity activity) {
        List<String> titles = new ArrayList<>();
        for (CarUiListItem row : activity.handoffRowsForTesting()) {
            if (row instanceof CarUiContentListItem) {
                CarUiContentListItem item = (CarUiContentListItem) row;
                if (item.getOnClickListener() != null && item.getTitle() != null) {
                    titles.add(item.getTitle().getPreferredText().toString());
                }
            }
        }
        return titles;
    }

    /**
     * The exported, permissionless debug receivers exist only on a debuggable build: a
     * non-debuggable application registers none.
     */
    @Test
    public void debugReceiversAreNotRegisteredOnAReleaseBuild() {
        Application app = org.robolectric.RuntimeEnvironment.getApplication();
        ApplicationInfo info = app.getApplicationInfo();

        info.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();
        assertThat(registeredDebugActions(app)).isEmpty();

        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();

        // Anti-vacuity: if the debuggable path registered nothing either, the assertion above would
        // hold for the wrong reason -- an Activity that never registers anything at all.
        assertThat(registeredDebugActions(app))
                .containsAtLeast(DemoRestrictions.ACTION_SET, DebugBroadcasts.ACTION_CASE,
                        DebugBroadcasts.ACTION_SPEAK, DebugBroadcasts.ACTION_HANDOFF,
                        DebugBroadcasts.ACTION_AGENT);
    }

    /** The debug actions currently registered by any receiver on this application. */
    private static List<String> registeredDebugActions(Application app) {
        List<String> found = new ArrayList<>();
        for (ShadowApplication.Wrapper w : shadowOf(app).getRegisteredReceivers()) {
            IntentFilter filter = w.getIntentFilter();
            if (filter == null) {
                continue;
            }
            for (int i = 0; i < filter.countActions(); i++) {
                String action = filter.getAction(i);
                if (action.equals(DemoRestrictions.ACTION_SET)
                        || action.equals(DebugBroadcasts.ACTION_CASE)
                        || action.equals(DebugBroadcasts.ACTION_SPEAK)
                        || action.equals(DebugBroadcasts.ACTION_HANDOFF)
                        || action.equals(DebugBroadcasts.ACTION_AGENT)) {
                    found.add(action);
                }
            }
        }
        return found;
    }
}
