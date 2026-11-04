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

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;

import com.android.car.ui.utils.CarUxRestrictionsUtil.RestrictionsSource;

import org.junit.Before;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the four failure modes driver-distraction gating gets wrong most often, plus the
 * composite-mask and per-display cases.
 *
 * <p>These are local JVM tests: the UX-restriction state machine is pure logic and should not need
 * an emulator to verify. Upstream had no unit test source set at all, so none of this was covered.
 */
public class CarUxRestrictionsUtilTest {

    private static final int DRIVER_DISPLAY = 0;
    private static final int PASSENGER_DISPLAY = 2;

    /** Records everything a listener is told, in order. */
    private static final class RecordingListener
            implements CarUxRestrictionsUtil.OnUxRestrictionsChangedListener {
        final List<CarUxRestrictions> received = new ArrayList<>();

        @Override
        public void onRestrictionsChanged(CarUxRestrictions carUxRestrictions) {
            received.add(carUxRestrictions);
        }
    }

    private static CarUxRestrictions restrictions(int activeFlags) {
        CarUxRestrictions uxr = mock(CarUxRestrictions.class);
        when(uxr.getActiveRestrictions()).thenReturn(activeFlags);
        when(uxr.isRequiresDistractionOptimization())
                .thenReturn(activeFlags != UX_RESTRICTIONS_BASELINE);
        return uxr;
    }

    @Before
    public void resetSingletons() {
        CarUxRestrictionsUtil.clearInstancesForTesting();
        // android.car.jar is a stub jar: CarUxRestrictions.Builder throws
        // RuntimeException("Stub!") in a local JVM test, and AGP's returnDefaultValues only
        // rewrites android.jar. Supply the fail-closed value as a mock instead.
        CarUxRestrictionsUtil.setFailClosedFactoryForTesting(
                () -> restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED));
    }

    // ---------------------------------------------------------------------------------------
    // 1. Fail closed when car service is unreachable.
    //
    // Regression test for a real upstream bug: the constructor's catch(RuntimeException) logged
    // "assuming unrestricted" and set UX_RESTRICTIONS_BASELINE, releasing all gating exactly when
    // the signal could not be trusted.
    // ---------------------------------------------------------------------------------------

    @Test
    public void carServiceUnreachable_failsClosed_notOpen() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        util.onCarServiceLost(RestrictionsSource.NEVER_DELIVERED);

        int active = util.getCurrentRestrictions().getActiveRestrictions();
        assertThat(active).isNotEqualTo(UX_RESTRICTIONS_BASELINE);
        assertThat(active).isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(util.getCurrentRestrictions().isRequiresDistractionOptimization()).isTrue();
    }

    @Test
    public void carServiceDeliversNull_failsClosed() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        util.onRestrictionsDelivered(null);

        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    // ---------------------------------------------------------------------------------------
    // 2. "Never delivered" must be distinguishable from a genuine reading.
    //
    // A fail-closed default is byte-identical to a real FULLY_RESTRICTED reading, so without an
    // explicit source there is no way to tell "we have heard nothing" from "the vehicle is
    // moving".
    // ---------------------------------------------------------------------------------------

    @Test
    public void beforeAnyDelivery_sourceIsNeverDelivered_andStateIsRestricted() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.NEVER_DELIVERED);
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Test
    public void genuineFullyRestrictedReading_isDistinguishableFromNeverDelivered() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED));

        // Same restriction value as the never-delivered default...
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        // ...but now attributable to a real reading.
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DELIVERED);
    }

    @Test
    public void genuineBaselineReading_isNotConfusedWithFailure() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DELIVERED);
    }

    // ---------------------------------------------------------------------------------------
    // 3. Car-service death, and re-seeding on rebind.
    // ---------------------------------------------------------------------------------------

    @Test
    public void serviceDeathAfterUnrestricted_revertsToRestricted() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);

        util.onCarServiceLost(RestrictionsSource.DISCONNECTED);

        // Must NOT latch the last-known-good unrestricted value.
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
    }

    @Test
    public void rebindAfterDeath_reSeedsAndNotifies() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        RecordingListener listener = new RecordingListener();
        util.register(listener);
        int afterRegister = listener.received.size();

        util.onCarServiceLost(RestrictionsSource.DISCONNECTED);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(listener.received.size()).isEqualTo(afterRegister + 2);
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DELIVERED);
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    // ---------------------------------------------------------------------------------------
    // 4. FULLY_RESTRICTED is a COMPOSITE (0x1ff), not a flag.
    // ---------------------------------------------------------------------------------------

    @Test
    public void fullyRestricted_isTheUnionOfTheIndividualBits() {
        assertThat(UX_RESTRICTIONS_FULLY_RESTRICTED).isEqualTo(0x1ff);
        // Sanity-check a couple of individual bits against the platform constants, since a
        // widely-copied table of these values in circulation is wrong in most rows.
        assertThat(UX_RESTRICTIONS_NO_VIDEO).isEqualTo(0x10);
        assertThat(UX_RESTRICTIONS_NO_SETUP).isEqualTo(0x40);
        assertThat(UX_RESTRICTIONS_FULLY_RESTRICTED & UX_RESTRICTIONS_NO_VIDEO)
                .isEqualTo(UX_RESTRICTIONS_NO_VIDEO);
    }

    @Test
    public void hasAllRestrictions_singleBitDoesNotSatisfyTheComposite() {
        CarUxRestrictions onlyNoVideo = restrictions(UX_RESTRICTIONS_NO_VIDEO);

        // The whole point: one unrelated bit must NOT read as "fully restricted".
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(
                UX_RESTRICTIONS_FULLY_RESTRICTED, onlyNoVideo)).isFalse();
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(
                UX_RESTRICTIONS_NO_VIDEO, onlyNoVideo)).isTrue();
    }

    @Test
    public void hasAllRestrictions_trueOnlyWhenEveryBitIsSet() {
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(UX_RESTRICTIONS_FULLY_RESTRICTED,
                restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED))).isTrue();

        // One bit short of the composite.
        int almost = UX_RESTRICTIONS_FULLY_RESTRICTED & ~UX_RESTRICTIONS_NO_KEYBOARD;
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(UX_RESTRICTIONS_FULLY_RESTRICTED,
                restrictions(almost))).isFalse();
    }

    @Test
    public void hasAllRestrictions_survivesFutureBitsThatEqualityWouldNotFail() {
        // A platform newer than this one may report FULLY_RESTRICTED plus an additional bit.
        // "active == FULLY_RESTRICTED" would wrongly say "not fully restricted"; the masked
        // comparison stays correct.
        int futureBit = 0x200;
        CarUxRestrictions future = restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED | futureBit);

        assertThat(future.getActiveRestrictions() == UX_RESTRICTIONS_FULLY_RESTRICTED).isFalse();
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(
                UX_RESTRICTIONS_FULLY_RESTRICTED, future)).isTrue();
    }

    /**
     * Pins the exact distinction the composite bug turns on, so it cannot silently regress.
     *
     * <p>The left-hand expression is what upstream's {@code isRestricted} computes. Asked "is the
     * vehicle fully restricted?" with only {@code NO_VIDEO} active, it answers <b>true</b> — wrong.
     * The masked comparison answers false.
     */
    @Test
    public void anyOfVersusAllOf_divergeOnTheCompositeMask() {
        CarUxRestrictions onlyNoVideo = restrictions(UX_RESTRICTIONS_NO_VIDEO);
        int active = onlyNoVideo.getActiveRestrictions();

        boolean anyOf = (active & UX_RESTRICTIONS_FULLY_RESTRICTED) != 0;   // upstream's test
        boolean allOf = (active & UX_RESTRICTIONS_FULLY_RESTRICTED)
                == UX_RESTRICTIONS_FULLY_RESTRICTED;                        // the correct test

        assertThat(anyOf).isTrue();
        assertThat(allOf).isFalse();
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(
                UX_RESTRICTIONS_FULLY_RESTRICTED, onlyNoVideo)).isEqualTo(allOf);
    }

    /**
     * Regression guard for the upstream fail-OPEN bug. Upstream's catch block set
     * {@code UX_RESTRICTIONS_BASELINE} — the value asserted against here — so this test fails
     * against the original implementation and passes against the fixed one.
     */
    @Test
    public void unreachableCarService_mustNotReportBaseline() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        util.onCarServiceLost(RestrictionsSource.NEVER_DELIVERED);

        boolean wouldHaveBeenUnrestricted =
                util.getCurrentRestrictions().getActiveRestrictions() == UX_RESTRICTIONS_BASELINE;
        assertThat(wouldHaveBeenUnrestricted).isFalse();
    }

    @Test
    public void hasAllRestrictions_nullFailsClosed() {
        assertThat(CarUxRestrictionsUtil.hasAllRestrictions(
                UX_RESTRICTIONS_FULLY_RESTRICTED, null)).isTrue();
    }

    @Test
    public void isRestricted_keepsItsAnyOfContract() {
        // Documented behaviour, relied on by UxrButton: any one of the requested flags is enough.
        CarUxRestrictions onlyNoSetup = restrictions(UX_RESTRICTIONS_NO_SETUP);

        assertThat(CarUxRestrictionsUtil.isRestricted(
                UX_RESTRICTIONS_NO_SETUP | UX_RESTRICTIONS_NO_KEYBOARD, onlyNoSetup)).isTrue();
        assertThat(CarUxRestrictionsUtil.isRestricted(
                UX_RESTRICTIONS_LIMIT_CONTENT, onlyNoSetup)).isFalse();
        assertThat(CarUxRestrictionsUtil.isRestricted(UX_RESTRICTIONS_NO_SETUP, null)).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // 5. Listeners are seeded at registration.
    //
    // CarUxRestrictionsManager's listener is change-only, so a client that binds while already
    // restricted would never be told unless registration itself delivers current state.
    // ---------------------------------------------------------------------------------------

    @Test
    public void register_immediatelySeedsListenerWithCurrentState() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_NO_VIDEO));

        RecordingListener listener = new RecordingListener();
        util.register(listener);

        assertThat(listener.received).hasSize(1);
        assertThat(listener.received.get(0).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_NO_VIDEO);
    }

    /**
     * Pins a coupling that is otherwise invisible.
     *
     * <p>{@code SearchView.mIsRestricted} and the reference plugin's
     * {@code SearchController.mIsRestricted} both have no initialiser, so both default to
     * {@code false} — i.e. NOT restricted, the permissive value. Neither is a defect today, and the
     * only reason is this method: {@code register()} delivers the current state synchronously, and
     * the current state starts fail-closed, so the permissive default is overwritten before anything
     * can observe it.
     *
     * <p>That makes those two a <b>conditional clear</b> in the audit, where the condition lives in
     * a different file and nothing said so. Someone reviewing the seed in isolation could reasonably
     * argue it is redundant — "the listener will fire on the next change anyway, why duplicate?" —
     * and would be arming a live fail-open in two components they never opened.
     *
     * <p>So the condition is pinned here rather than written in a comment. Delete the seed and this
     * test fails, naming the components that depended on it.
     */
    @Test
    public void seedOnRegister_isWhatProtectsSearchViewsPermissiveDefault() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        RecordingListener listener = new RecordingListener();

        util.register(listener);

        // Synchronously, before register() returns — not on the next change.
        assertThat(listener.received).hasSize(1);
        // And the seeded value must be the restrictive one, or the seed protects nothing.
        assertThat(listener.received.get(0).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        // Which is what flips a listener whose own field defaults to "not restricted".
        assertThat(CarUxRestrictionsUtil.isRestricted(
                UX_RESTRICTIONS_NO_KEYBOARD, listener.received.get(0))).isTrue();
    }

    @Test
    public void register_beforeAnyDelivery_seedsWithFailClosedDefault() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);

        RecordingListener listener = new RecordingListener();
        util.register(listener);

        assertThat(listener.received).hasSize(1);
        assertThat(listener.received.get(0).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Test
    public void unregister_stopsDelivery() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        RecordingListener listener = new RecordingListener();
        util.register(listener);
        int afterRegister = listener.received.size();

        util.unregister(listener);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(listener.received).hasSize(afterRegister);
    }

    // ---------------------------------------------------------------------------------------
    // 6. Restrictions are PER-DISPLAY.
    //
    // A front passenger screen may legitimately be unrestricted while the driver's is not.
    // Upstream held one process-wide instance, which collapsed both onto one value.
    // ---------------------------------------------------------------------------------------

    @Test
    public void displaysHoldIndependentRestrictions() {
        CarUxRestrictionsUtil driver = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        CarUxRestrictionsUtil passenger = new CarUxRestrictionsUtil(PASSENGER_DISPLAY);

        driver.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED));
        passenger.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(driver.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(passenger.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);
        assertThat(driver.getDisplayId()).isNotEqualTo(passenger.getDisplayId());
    }

    @Test
    public void perDisplayListenersDoNotCrossTalk() {
        CarUxRestrictionsUtil driver = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        CarUxRestrictionsUtil passenger = new CarUxRestrictionsUtil(PASSENGER_DISPLAY);
        RecordingListener driverListener = new RecordingListener();
        RecordingListener passengerListener = new RecordingListener();
        driver.register(driverListener);
        passenger.register(passengerListener);
        int driverSeen = driverListener.received.size();
        int passengerSeen = passengerListener.received.size();

        // Video starts on the passenger screen while the car is moving. The driver display must
        // not be told anything, and must stay restricted.
        passenger.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(passengerListener.received).hasSize(passengerSeen + 1);
        assertThat(driverListener.received).hasSize(driverSeen);
        assertThat(driver.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * Regression test for a crash this fork introduced and then caught on a real AAOS emulator:
     * {@code Context.getDisplay()} throws {@link UnsupportedOperationException} for any non-visual
     * context, and {@code MenuItem} reaches {@code getInstance} with exactly such a context during
     * {@code ToolbarControllerImpl} construction. PaintBooth died on launch until this fell back to
     * the default display instead of propagating.
     */
    @Test
    public void nonVisualContext_fallsBackToDefaultDisplay() {
        android.content.Context nonVisual = mock(android.content.Context.class);
        when(nonVisual.getDisplay()).thenThrow(new UnsupportedOperationException(
                "Tried to obtain display from a Context not associated with one."));

        assertThat(CarUxRestrictionsUtil.displayIdOrDefault(nonVisual))
                .isEqualTo(android.view.Display.DEFAULT_DISPLAY);
    }

    @Test
    public void visualContext_resolvesItsOwnDisplay() {
        android.view.Display display = mock(android.view.Display.class);
        when(display.getDisplayId()).thenReturn(PASSENGER_DISPLAY);
        android.content.Context visual = mock(android.content.Context.class);
        when(visual.getDisplay()).thenReturn(display);

        assertThat(CarUxRestrictionsUtil.displayIdOrDefault(visual)).isEqualTo(PASSENGER_DISPLAY);
    }

    @Test
    public void contextWithNoDisplay_fallsBackToDefaultDisplay() {
        android.content.Context noDisplay = mock(android.content.Context.class);
        when(noDisplay.getDisplay()).thenReturn(null);

        assertThat(CarUxRestrictionsUtil.displayIdOrDefault(noDisplay))
                .isEqualTo(android.view.Display.DEFAULT_DISPLAY);
    }

    @Test
    public void serviceLossOnOneDisplayDoesNotAffectAnother() {
        CarUxRestrictionsUtil driver = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        CarUxRestrictionsUtil passenger = new CarUxRestrictionsUtil(PASSENGER_DISPLAY);
        passenger.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        driver.onCarServiceLost(RestrictionsSource.DISCONNECTED);

        assertThat(driver.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
        assertThat(passenger.getRestrictionsSource()).isEqualTo(RestrictionsSource.DELIVERED);
        assertThat(passenger.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    // ---------------------------------------------------------------------------------------
    // 7. disconnect() — restored from AOSP a92cdc2ed, which this fork's per-display rewrite
    //    dropped along with the WeakHashMap<Context, …> keying it was written against.
    //
    // The binding itself cannot be asserted on in a local JVM test: android.car.jar is a stub, so
    // Car.createCar throws before any connection exists. What these pin is the part that is pure
    // state — that releasing the binding also moves the reported restrictions to a fail-closed
    // DISCONNECTED rather than latching whatever was last delivered, and that a released instance
    // is evicted rather than left in the cache as a permanently dead object. Both are deliberate
    // departures from upstream's version of this method.
    // ---------------------------------------------------------------------------------------

    /**
     * The departure that matters. Upstream's {@code disconnect()} released the binding and left
     * {@code mCarUxRestrictions} untouched, so an instance disconnected while the vehicle happened
     * to be parked went on reporting {@code BASELINE} — <b>no restrictions</b> — for the rest of the
     * process, with the connection that could have corrected it now gone.
     *
     * <p>That is the same latched-permissive shape as the fail-open catch block this class was
     * rewritten to remove, reached by a different route.
     */
    @Test
    public void disconnect_doesNotLatchTheLastPermissiveReading() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);

        util.disconnect();

        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
    }

    /**
     * Provenance after teardown must read as DISCONNECTED, not NEVER_DELIVERED: a caller
     * distinguishing "we have never heard from car service" from "we have stopped listening" gets
     * different remediation from each.
     */
    @Test
    public void disconnect_recordsDisconnected_evenIfNothingWasEverDelivered() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.NEVER_DELIVERED);

        util.disconnect();

        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
    }

    /**
     * Registered listeners are told. A view still on screen when its display is torn down would
     * otherwise keep rendering against the last reading it was given.
     */
    @Test
    public void disconnect_notifiesObserversWithTheFailClosedValue() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        RecordingListener listener = new RecordingListener();
        util.register(listener);
        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));
        int beforeDisconnect = listener.received.size();

        util.disconnect();

        assertThat(listener.received).hasSize(beforeDisconnect + 1);
        assertThat(listener.received.get(listener.received.size() - 1).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * Teardown paths run twice more often than anyone plans for. Calling this on an instance that
     * never connected — the test-seam constructor's state, and the state of any instance whose
     * {@code Car.createCar} threw — must also be a no-op rather than an NPE.
     */
    @Test
    public void disconnect_isIdempotentAndSafeWithoutAConnection() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(PASSENGER_DISPLAY);

        util.disconnect();
        util.disconnect();

        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * A disconnected instance is <b>terminal</b>: a late delivery must not revive it.
     *
     * <p>This is a real race, not a hypothetical one. {@code Car.createCar(..., DO_NOT_WAIT,
     * listener)} keeps invoking its lifecycle listener on a binder thread, so a {@code ready=true}
     * callback can land after {@code disconnect()} and re-seed the state. It surfaced as an
     * intermittent failure of
     * {@code CarUxRestrictionsPerDisplayTest#disconnect_evictsSoTheNextGetInstanceReconnects} on a
     * real device — the instrumented test caught it, and this pins it where it cannot depend on
     * timing.
     *
     * <p>The consequence if it were allowed: a released instance goes back to reporting
     * {@code DELIVERED} for a display that may no longer exist, and re-registers with a manager
     * that accepts only one listener at a time — taking that slot from whichever instance now
     * legitimately holds it.
     */
    @Test
    public void afterDisconnect_lateDeliveryDoesNotRevive() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        util.disconnect();

        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
        assertThat(util.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /** Nor does a late service-loss notification move it off DISCONNECTED. */
    @Test
    public void afterDisconnect_lateServiceLossDoesNotChangeTheSource() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        util.disconnect();

        util.onCarServiceLost(RestrictionsSource.NEVER_DELIVERED);

        assertThat(util.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
    }

    /** A late delivery must not reach observers either, not merely fail to change the field. */
    @Test
    public void afterDisconnect_lateDeliveryDoesNotNotifyObservers() {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        RecordingListener listener = new RecordingListener();
        util.register(listener);
        util.disconnect();
        int afterDisconnect = listener.received.size();

        util.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(listener.received).hasSize(afterDisconnect);
    }

    /**
     * Disconnecting one display leaves the others alone. This is the per-display property applied
     * to teardown: removing a passenger screen must not silently disarm the driver's gating, which
     * is exactly what a process-wide singleton with a {@code disconnect()} would have done.
     */
    @Test
    public void disconnect_isScopedToItsOwnDisplay() {
        CarUxRestrictionsUtil driver = new CarUxRestrictionsUtil(DRIVER_DISPLAY);
        CarUxRestrictionsUtil passenger = new CarUxRestrictionsUtil(PASSENGER_DISPLAY);
        driver.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_NO_VIDEO));

        passenger.disconnect();

        assertThat(passenger.getRestrictionsSource()).isEqualTo(RestrictionsSource.DISCONNECTED);
        assertThat(driver.getRestrictionsSource()).isEqualTo(RestrictionsSource.DELIVERED);
        assertThat(driver.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_NO_VIDEO);
    }

    // ---------------------------------------------------------------------------------------
    // 8. The fail-closed DECISION itself — asserted without the seam.
    //
    // Every test above replaces the fail-closed factory in @Before, so none of them can see the
    // production value. check-gate-sensitivity.py proved that: mutating the default to
    // (false, BASELINE, 0) — fail OPEN — was detected by nothing. These assert the decision
    // directly, which is possible only because it is now a constant rather than a constructor call.
    // ---------------------------------------------------------------------------------------

    @Test
    public void failClosedDefault_requiresDistractionOptimization() {
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_REQUIRES_DISTRACTION_OPTIMIZATION).isTrue();
    }

    @Test
    public void failClosedDefault_isFullyRestricted_notBaseline() {
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_ACTIVE_RESTRICTIONS)
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_ACTIVE_RESTRICTIONS)
                .isNotEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    // ---------------------------------------------------------------------------------------
    // 9. complyString's arithmetic.
    //
    // Found by scripts/check-test-reachability.py, which reported complyString as named by no test.
    // Reading it to decide whether it deserved one is what surfaced the defect: upstream returned
    // substring(0, maxLength) + ellipsis, which is maxLength + 1 characters. The method that exists
    // to enforce a driver-distraction limit exceeded it, on the live UxrTextView path, in every
    // string it ever shortened.
    //
    // Tested through truncateToComply rather than complyString itself: the latter needs a Context
    // for resources, and the arithmetic — the part that was wrong — needs no Android at all.
    // ---------------------------------------------------------------------------------------

    private static final String ELL = "\u2026";

    @Test
    public void truncateToComply_countsTheEllipsisInsideTheLimit() {
        String out = CarUxRestrictionsUtil.truncateToComply("abcdefghij", 5, ELL);

        // Upstream returned 6 here. Not a rounding difference — a limit, exceeded.
        assertThat(out).hasLength(5);
        assertThat(out).isEqualTo("abcd" + ELL);
    }

    @Test
    public void truncateToComply_neverExceedsTheLimit_atAnyLength() {
        String text = "0123456789abcdefghijklmnopqrstuvwxyz";
        for (int max = 0; max <= text.length() + 2; max++) {
            assertThat(CarUxRestrictionsUtil.truncateToComply(text, max, ELL).length())
                    .isAtMost(Math.max(0, max));
        }
    }

    @Test
    public void truncateToComply_leavesCompliantStringsAlone() {
        assertThat(CarUxRestrictionsUtil.truncateToComply("short", 99, ELL)).isEqualTo("short");
        assertThat(CarUxRestrictionsUtil.truncateToComply("exactly-ten", 11, ELL))
                .isEqualTo("exactly-ten");
        assertThat(CarUxRestrictionsUtil.truncateToComply(null, 5, ELL)).isNull();
    }

    @Test
    public void truncateToComply_degenerateLimits() {
        assertThat(CarUxRestrictionsUtil.truncateToComply("abcdef", 0, ELL)).isEmpty();
        assertThat(CarUxRestrictionsUtil.truncateToComply("abcdef", 1, ELL)).isEqualTo(ELL);
        assertThat(CarUxRestrictionsUtil.truncateToComply("abcdef", -4, ELL)).isEmpty();
    }

    @Test
    public void truncateToComply_neverSplitsASurrogatePair() {
        // "ab" + musical G-clef (one code point, two chars) + "cd". Cutting at index 3 lands
        // between the surrogates; a TextView handed an unpaired one draws a replacement glyph.
        String out = CarUxRestrictionsUtil.truncateToComply("ab\uD834\uDD1Ecd", 4, ELL);

        assertThat(out).isEqualTo("ab" + ELL);
        for (int i = 0; i < out.length(); i++) {
            assertThat(Character.isSurrogate(out.charAt(i))).isFalse();
        }
    }

    @Test
    public void truncateToComply_agreesWithTheA2uiRuleItWasWrittenAgainst() {
        // A2uiPruner.truncate documents the same trap and was written later. The two live in
        // different modules and cannot share code — car-ui-lib is the lower layer — so the only
        // thing keeping them consistent is that both are asserted to produce a result within the
        // limit. If one is ever "fixed" back, this and its counterpart both go red.
        for (int max = 1; max <= 12; max++) {
            String out = CarUxRestrictionsUtil.truncateToComply("0123456789abcdef", max, ELL);
            assertThat(out.length()).isEqualTo(max);
            assertThat(out.endsWith(ELL)).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------
    // 9. The test seam is a test seam on a release build.
    // ---------------------------------------------------------------------------------------

    /**
     * {@code setUxRestrictions} is ignored when the hosting app is not debuggable.
     *
     * <p>It shipped in the published AAR and routes to {@code onRestrictionsDelivered}, which sets
     * {@code RestrictionsSource.DELIVERED} — the one value every provenance check in this fork keys
     * on. So it does not merely change the restrictions, it manufactures a reading indistinguishable
     * from car service's, and {@code @VisibleForTesting} does nothing about that at runtime.
     *
     * <p>The exposure is per-process: {@code sInstances} is a private static cache and this class
     * publishes nothing over Binder, so a caller can only lie to itself. That is still worth
     * closing — the realistic case is not a hostile app but an unaudited dependency inside an app
     * certified on the basis that it uses this gating.
     */
    @Test
    public void setUxRestrictionsIsIgnoredOnANonDebuggableBuild() {
        CarUxRestrictionsUtil release = new CarUxRestrictionsUtil(0, false);
        RecordingListener listener = new RecordingListener();
        release.register(listener);
        listener.received.clear();

        release.setUxRestrictions(restrictions(UX_RESTRICTIONS_BASELINE));

        // Unmoved: still fail-closed, still NOT delivered, and no observer was told otherwise.
        assertThat(release.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
        assertThat(release.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(listener.received).isEmpty();
    }

    /**
     * ...and it still works on a debuggable one, or the test above passes for the wrong reason —
     * an implementation that ignored the call unconditionally would satisfy it.
     */
    @Test
    public void setUxRestrictionsStillWorksOnADebuggableBuild() {
        CarUxRestrictionsUtil debuggable = new CarUxRestrictionsUtil(0, true);

        debuggable.setUxRestrictions(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(debuggable.getRestrictionsSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
        assertThat(debuggable.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    /**
     * The PACKAGE-PRIVATE seam is gated too — gating only the public method guarded the wrong door.
     *
     * <p>{@code onRestrictionsDelivered} is where {@code DELIVERED} is actually assigned, and
     * {@code javap -p} on the published {@code classes.jar} lists it as a default-access method. An
     * app's dex and the AAR's share one {@code PathClassLoader} and there is no sealed manifest, so
     * any consumer that declares {@code package com.android.car.ui.utils;} reaches it on the
     * instance {@code getInstance} handed them — no reflection required.
     */
    @Test
    public void onRestrictionsDeliveredIsIgnoredOnARelease_notJustSetUxRestrictions() {
        CarUxRestrictionsUtil release = new CarUxRestrictionsUtil(0, false);

        release.onRestrictionsDelivered(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(release.getRestrictionsSource())
                .isNotEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
        assertThat(release.getCurrentRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }


    // ---------------------------------------------------------------------------------------
    // 10. The fan-out is safe across threads.
    // ---------------------------------------------------------------------------------------

    /**
     * Registering and unregistering while readings are delivered from another thread does not throw.
     *
     * <p>{@code mObservers} is a {@code WeakHashMap}-backed set. It used to be iterated by
     * {@code notifyObservers} with no lock while {@code register}/{@code unregister} mutated it
     * with no lock — and the two happen on different threads by design: the file's own comment
     * records that the {@code CarServiceLifecycleListener} runs on a BINDER thread, while
     * {@code UxrButton} registers in {@code onAttachedToWindow} and unregisters in
     * {@code onDetachedFromWindow} on the UI thread.
     *
     * <p>So a screen attaching at the moment the vehicle starts moving could throw
     * {@code ConcurrentModificationException} inside the driver-distraction fan-out, or corrupt the
     * backing map. Every downstream consumer — {@code UxrButton}, {@code UxrTextView},
     * {@code ContentLimitingAdapter}, every OEM {@code UxRestrictablePreference} — inherits its
     * correctness from this one method.
     *
     * <p>No device needed: two plain threads and the real object.
     */
    @Test
    public void theFanOutSurvivesConcurrentRegistrationAndDelivery() throws Exception {
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        // A POPULATED set, and this is what makes the test able to see the defect at all.
        //
        // The first version registered a listener and immediately unregistered it, so the set was
        // near-empty and notifyObservers' iteration finished before the mutator could interleave.
        // It passed three times against the unsynchronised implementation -- a race test that
        // cannot open the race proves nothing, and this one is recorded because it took a
        // deliberate check to notice.
        //
        // Strong references throughout: the set is weak, so listeners the test does not hold would
        // be collected and the churn would quietly stop.
        List<RecordingListener> resident = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            RecordingListener listener = new RecordingListener();
            resident.add(listener);
            util.register(listener);
        }
        List<RecordingListener> churn = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            churn.add(new RecordingListener());
        }

        Thread mutator = new Thread(() -> {
            try {
                start.await();
                for (RecordingListener listener : churn) {
                    util.register(listener);
                    util.unregister(listener);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        Thread deliverer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 4000; i++) {
                    util.onRestrictionsDelivered(restrictions(
                            i % 2 == 0 ? UX_RESTRICTIONS_BASELINE
                                    : UX_RESTRICTIONS_FULLY_RESTRICTED));
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });

        mutator.start();
        deliverer.start();
        start.countDown();
        mutator.join(60_000);
        deliverer.join(60_000);

        // Held to the end so the weak set cannot drop them mid-run.
        assertThat(resident).hasSize(400);
        assertThat(failure.get()).isNull();
    }

    /**
     * One delivery gives every observer the SAME reading.
     *
     * <p>{@code notifyObservers} used to read {@code mCarUxRestrictions} once per observer inside
     * the loop, so a delivery landing mid-iteration handed different observers different answers
     * for one event — the shape where two views on one screen disagree about whether the vehicle is
     * moving. It now reads the field once into a local.
     */
    @Test
    public void oneDeliveryGivesEveryObserverTheSameReading() {
        // DETERMINISTIC, via a re-entrant observer rather than a second thread.
        //
        // The property is that notifyObservers reads mCarUxRestrictions ONCE into a local, so one
        // event cannot be split across two answers. Proving that needs the field to change DURING
        // the fan-out — and an observer that delivers a new reading from inside its own callback
        // does exactly that, with no timing to get lucky about.
        //
        // NO MUTATION IS REGISTERED FOR THIS PROPERTY, and that is recorded rather than hidden.
        //
        // Two designs were tried and both came back MISSED against a per-observer field read: two
        // passive observers (nothing changes the field mid-loop, so the same instance is produced
        // either way), and this re-entrant one. The re-entrant version does make the field change
        // during the fan-out, but the observed outcome is identical with and without the defect --
        // measured, not assumed, by applying the mutation by hand and running this class alone.
        //
        // So what this test documents is INTENT: notifyObservers reads the field once so one event
        // cannot be split across two answers. It is not evidence that the suite would notice if
        // that stopped being true. Registering a mutation known to be MISSED would put a false
        // entry in a registry whose value is that every entry has been shown to bite.
        CarUxRestrictionsUtil util = new CarUxRestrictionsUtil(0);
        CarUxRestrictions original = restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED);
        CarUxRestrictions interrupting = restrictions(UX_RESTRICTIONS_BASELINE);

        RecordingListener later = new RecordingListener();
        AtomicBoolean reentered = new AtomicBoolean(false);
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener earlier = uxr -> {
            // One shot: a second re-entry would recurse without bound.
            if (reentered.compareAndSet(false, true)) {
                util.onRestrictionsDelivered(interrupting);
            }
        };

        util.register(earlier);
        util.register(later);
        later.received.clear();

        util.onRestrictionsDelivered(original);

        // `later` must see the reading THIS fan-out began with, not the one the re-entrant delivery
        // installed part-way through. It also receives the interrupting value from the nested
        // fan-out, so the assertion is on the FIRST delivery it saw during the outer pass.
        assertThat(reentered.get()).isTrue();
        assertThat(later.received).isNotEmpty();
        assertThat(later.received.get(later.received.size() - 1)).isSameInstanceAs(original);
    }
}
