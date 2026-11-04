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

package com.android.car.ui.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runtime validation of per-display UX restrictions, using REAL {@link Context} objects on a real
 * device.
 *
 * <p>This exists because the local JVM tests are structurally blind to the thing most likely to
 * break: they construct {@code CarUxRestrictionsUtil} through a test-only constructor with synthetic
 * display ids, and never call {@link CarUxRestrictionsUtil#getInstance}. The one bug this fork's
 * per-display change actually shipped — {@code Context.getDisplay()} throwing
 * {@code UnsupportedOperationException} on a non-visual context, which killed every activity using
 * the car-ui toolbar — lived precisely in that untested gap and was only caught by launching
 * PaintBooth on an emulator.
 *
 * <p>Run against a multi-display AAOS emulator:
 * {@code emulator -avd <aaos-avd> -multidisplay 1,1408,792,160,1} (commas, not spaces — the
 * emulator's own {@code -help-multidisplay} shows both and only accepts commas).
 *
 * <p><b>The multi-display cases FAIL, not skip, when only one display is reachable.</b> That is
 * deliberate. An earlier version guarded them with {@code assumeTrue}, and on this very emulator
 * they silently passed while testing nothing: {@code dumpsys display} listed three displays, but
 * {@code DisplayManager.getDisplays()} returned one, because private displays are hidden from
 * ordinary apps. Six green tests, three of which never ran. A skip that looks like a pass is worse
 * than no test.
 *
 * <p>To run on a genuinely single-display head unit, opt out explicitly:
 * {@code -e allowSingleDisplay true}.
 */
@RunWith(AndroidJUnit4.class)
public class CarUxRestrictionsPerDisplayTest {

    private Context mAppContext;
    private DisplayManager mDisplayManager;

    @Before
    public void setUp() {
        mAppContext = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        mDisplayManager = mAppContext.getSystemService(DisplayManager.class);
        CarUxRestrictionsUtil.clearInstancesForTesting();
    }


    /**
     * Returns the reachable displays, failing loudly unless there are at least two.
     *
     * <p>Only downgrades to a skip when the caller explicitly passes
     * {@code -e allowSingleDisplay true}, so an environment that quietly stops being multi-display
     * cannot masquerade as a pass.
     */
    private Display[] requireMultipleDisplays() {
        Display[] displays = mDisplayManager.getDisplays();
        Bundle args = InstrumentationRegistry.getArguments();
        boolean optedOut = Boolean.parseBoolean(args.getString("allowSingleDisplay", "false"));
        if (displays.length < 2 && optedOut) {
            assumeTrue("single-display device; skipped via -e allowSingleDisplay true", false);
        }
        assertThat(displays.length).isAtLeast(2);
        return displays;
    }

    /**
     * The regression that PaintBooth caught. An application context is not a visual context, so
     * {@code getDisplay()} throws on it; {@code MenuItem} reaches {@code getInstance} with exactly
     * this kind of context while the toolbar is being built. Must resolve to the default display
     * rather than propagate.
     */
    @Test
    public void applicationContext_resolvesToDefaultDisplay_withoutThrowing() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.getInstance(mAppContext);

        assertThat(util).isNotNull();
        assertThat(util.getDisplayId()).isEqualTo(Display.DEFAULT_DISPLAY);
    }

    /** Whatever happens with car service, a real device must never report an absent signal. */
    @Test
    public void restrictionsAreNeverNull_andSourceIsAlwaysKnown() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.getInstance(mAppContext);

        assertThat(util.getCurrentRestrictions()).isNotNull();
        assertThat(util.getRestrictionsSource()).isNotNull();
    }

    /** The same display must always yield the same cached instance. */
    @Test
    public void sameDisplay_yieldsSameInstance() {
        CarUxRestrictionsUtil first = CarUxRestrictionsUtil.getInstance(mAppContext);
        CarUxRestrictionsUtil second = CarUxRestrictionsUtil.getInstance(mAppContext);

        assertThat(first).isSameInstanceAs(second);
    }

    /**
     * {@link CarUxRestrictionsUtil#disconnect()} evicts, so the next caller gets a live instance.
     *
     * <p>This belongs here rather than in the JVM suite for a specific reason: the per-display cache
     * is a {@link android.util.SparseArray}, and the JVM suite runs against the mockable
     * {@code android.jar} with {@code returnDefaultValues = true}, where every {@code SparseArray}
     * method returns a default. {@code get()} would answer null, {@code put()} would do nothing, and
     * an eviction assertion would pass without any caching having happened at all — the shape of
     * green-but-blind this suite exists to avoid. On a device the container is real.
     *
     * <p>Upstream's {@code disconnect()} left the released instance in its map, so every subsequent
     * {@code getInstance()} for that context returned an object whose connection was already gone.
     */
    @Test
    public void disconnect_evictsSoTheNextGetInstanceReconnects() {
        CarUxRestrictionsUtil first = CarUxRestrictionsUtil.getInstance(mAppContext);
        assertThat(CarUxRestrictionsUtil.getInstance(mAppContext)).isSameInstanceAs(first);

        first.disconnect();

        CarUxRestrictionsUtil rebuilt = CarUxRestrictionsUtil.getInstance(mAppContext);
        assertThat(rebuilt).isNotSameInstanceAs(first);
        assertThat(rebuilt.getDisplayId()).isEqualTo(first.getDisplayId());
        // The released instance stays fail-closed rather than latching its last reading.
        assertThat(first.getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
        assertThat(first.getCurrentRestrictions().isRequiresDistractionOptimization()).isTrue();
    }

    /**
     * Disconnecting a second display must not evict or disturb the driver's instance. The failure
     * this guards against is a teardown path that reaches for the cache by identity and gets the
     * wrong entry — which on a real head unit means removing a passenger screen silently releases
     * the driver's restriction binding.
     */
    @Test
    public void disconnect_onOneDisplay_leavesTheOtherCached() {
        Display[] displays = requireMultipleDisplays();
        Context firstContext = mAppContext.createDisplayContext(displays[0]);
        Context secondContext = mAppContext.createDisplayContext(displays[1]);
        CarUxRestrictionsUtil keep = CarUxRestrictionsUtil.getInstance(firstContext);
        CarUxRestrictionsUtil drop = CarUxRestrictionsUtil.getInstance(secondContext);
        assertThat(keep).isNotSameInstanceAs(drop);

        drop.disconnect();

        assertThat(CarUxRestrictionsUtil.getInstance(firstContext)).isSameInstanceAs(keep);
        assertThat(CarUxRestrictionsUtil.getInstance(secondContext)).isNotSameInstanceAs(drop);
    }

    /**
     * The core claim: two displays get two independent instances. Upstream held one process-wide
     * singleton, so both of these would have been the same object and the passenger display would
     * have reported the driver's restrictions.
     */
    @Test
    public void distinctDisplays_yieldDistinctInstances() {
        Display[] displays = requireMultipleDisplays();

        Context first = mAppContext.createDisplayContext(displays[0]);
        Context second = mAppContext.createDisplayContext(displays[1]);

        CarUxRestrictionsUtil a = CarUxRestrictionsUtil.getInstance(first);
        CarUxRestrictionsUtil b = CarUxRestrictionsUtil.getInstance(second);

        assertThat(a.getDisplayId()).isEqualTo(displays[0].getDisplayId());
        assertThat(b.getDisplayId()).isEqualTo(displays[1].getDisplayId());
        assertThat(a.getDisplayId()).isNotEqualTo(b.getDisplayId());
        assertThat(a).isNotSameInstanceAs(b);
    }

    /**
     * Restriction state must not leak between displays. Driving one instance's state must leave
     * every other display untouched — the passenger screen staying legal while the driver's is
     * restricted is the entire point.
     */
    @Test
    public void restrictionStateDoesNotLeakBetweenDisplays() {
        Display[] displays = requireMultipleDisplays();

        CarUxRestrictionsUtil driver = CarUxRestrictionsUtil.getInstance(
                mAppContext.createDisplayContext(displays[0]));
        CarUxRestrictionsUtil passenger = CarUxRestrictionsUtil.getInstance(
                mAppContext.createDisplayContext(displays[1]));

        int driverBefore = driver.getCurrentRestrictions().getActiveRestrictions();

        passenger.onCarServiceLost(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);

        assertThat(passenger.getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
        assertThat(driver.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(driverBefore);
        assertThat(driver.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
    }

    /**
     * Every display context must be usable, not just the first two — a head unit can carry driver,
     * front passenger, two rear screens and a cluster.
     */
    @Test
    public void everyDisplayResolvesToItsOwnInstance() {
        Display[] displays = requireMultipleDisplays();

        for (Display display : displays) {
            CarUxRestrictionsUtil util = CarUxRestrictionsUtil.getInstance(
                    mAppContext.createDisplayContext(display));
            assertThat(util.getDisplayId()).isEqualTo(display.getDisplayId());
            assertThat(util.getCurrentRestrictions()).isNotNull();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-display OWNERSHIP.
    //
    // sInstances grows one entry per display id ever seen, and every entry holds a live car
    // binding. disconnect() existed to release one, but nothing called it: the responsibility was
    // documented as the caller's, which is another way of saying nobody's. CarUxRestrictionsUtil
    // now registers a DisplayManager.DisplayListener and releases an instance when its display is
    // removed — the platform was already announcing it.
    //
    // These live here, not in the JVM suite, for the reason recorded on
    // disconnect_evictsSoTheNextGetInstanceReconnects: the cache is a SparseArray, and the JVM
    // suite runs against the mockable android.jar where every SparseArray method returns a default,
    // so a cache assertion there passes without any caching having happened. Two of these were
    // written as JVM tests first and failed for exactly that reason.
    // ---------------------------------------------------------------------------------------

    /**
     * Creating an instance registers the display owner.
     *
     * <p>Deliberately asserts no "absent beforehand" precondition. The owner is a PROCESS-WIDE
     * listener registered once and never unregistered — the correct lifetime for a process-wide
     * cache — so it survives {@code clearInstancesForTesting()} and any earlier test in this class
     * will already have created it. A first draft asserted it was absent first and failed for
     * exactly that reason: the test's precondition was wrong, not the design.
     *
     * <p>What this still catches is the case that matters: an instance existing with no owner to
     * release it, which is the state the whole mechanism was added to remove.
     */
    @Test
    public void creatingAnInstanceRegistersTheDisplayOwner() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.getInstance(mAppContext);

        assertThat(util).isNotNull();
        assertThat(CarUxRestrictionsUtil.hasDisplayOwnerForTesting()).isTrue();
    }

    /**
     * The property the owner exists to guarantee, driven through the real code path rather than by
     * calling disconnect() directly: a removal notification releases that display's instance and
     * evicts it, so nothing can hand out a dead object for a display that is gone.
     */
    @Test
    public void displayRemoval_releasesAndEvictsThatDisplaysInstance() {
        Display[] displays = requireMultipleDisplays();
        Context secondary = mAppContext.createDisplayContext(displays[1]);
        CarUxRestrictionsUtil instance = CarUxRestrictionsUtil.getInstance(secondary);
        int displayId = instance.getDisplayId();

        CarUxRestrictionsUtil.notifyDisplayRemovedForTesting(displayId);

        assertThat(CarUxRestrictionsUtil.peekInstanceForTesting(displayId)).isNull();
        assertThat(instance.getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
        assertThat(instance.getCurrentRestrictions().isRequiresDistractionOptimization()).isTrue();
    }

    /**
     * Removing one display must not touch another. The failure this guards is a teardown that
     * reaches the cache by identity and gets the wrong entry — on a head unit, removing a passenger
     * screen silently releasing the driver's binding.
     */
    @Test
    public void displayRemoval_leavesOtherDisplaysAlone() {
        Display[] displays = requireMultipleDisplays();
        Context first = mAppContext.createDisplayContext(displays[0]);
        Context second = mAppContext.createDisplayContext(displays[1]);
        CarUxRestrictionsUtil keep = CarUxRestrictionsUtil.getInstance(first);
        CarUxRestrictionsUtil drop = CarUxRestrictionsUtil.getInstance(second);

        CarUxRestrictionsUtil.notifyDisplayRemovedForTesting(drop.getDisplayId());

        assertThat(CarUxRestrictionsUtil.peekInstanceForTesting(keep.getDisplayId()))
                .isSameInstanceAs(keep);
        assertThat(keep.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
    }

    /**
     * DisplayManager notifies every listener about every display, so a removal for a display this
     * process never cached is the ordinary case rather than the edge one. It must be silent.
     */
    @Test
    public void displayRemoval_forAnUncachedDisplay_isSilent() {
        CarUxRestrictionsUtil.clearInstancesForTesting();

        CarUxRestrictionsUtil.notifyDisplayRemovedForTesting(4242);

        assertThat(CarUxRestrictionsUtil.peekInstanceForTesting(4242)).isNull();
    }

    /**
     * {@code clearInstancesForTesting} DISCONNECTS what it drops — it does not strand live holders
     * on a permissive reading with the correction channel cut.
     *
     * <p>Here rather than in the JVM suite because the defect is about instances that are IN
     * {@code sInstances}, and only {@code getInstance(Context)} puts them there. A JVM test using
     * the package-private constructor cannot reach it — the first version of this test called
     * {@code disconnect()} directly and therefore asserted the method the fix DELEGATES to rather
     * than the fix, which is the same "protection in the wrong place" mistake this repository has
     * now made four times.
     *
     * <p>What it used to do: null {@code mCar} and release the binding while leaving
     * {@code mDisconnected} false and {@code mRestrictionsSource} DELIVERED. Called while parked,
     * that made a genuine BASELINE reading permanent for every already-registered observer, still
     * passing every provenance check by construction — needing no forgery, through a PUBLIC method.
     */
    @Test
    public void clearInstances_disconnectsTheDroppedInstance_ratherThanLatchingItPermissive() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.getInstance(mAppContext);
        util.setUxRestrictions(new CarUxRestrictions.Builder(
                false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 0).build());
        assertThat(util.getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);

        CarUxRestrictionsUtil.clearInstancesForTesting();

        // The dropped instance is CORRECTED, not merely orphaned. A holder that kept this reference
        // — every UxrButton, every MenuItem, every UxrContentLimiter — now sees fail-closed rather
        // than a stale permissive reading nothing can ever move.
        assertThat(util.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
        assertThat(util.getCurrentRestrictions().isRequiresDistractionOptimization()).isTrue();
    }
}
