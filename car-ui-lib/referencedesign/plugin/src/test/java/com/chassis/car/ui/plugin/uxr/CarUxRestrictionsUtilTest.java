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

package com.chassis.car.ui.plugin.uxr;

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the reference plugin's own {@link CarUxRestrictionsUtil}.
 *
 * <p><b>This class is the second copy of the fail-open defect</b> catalogued as instance 1 in
 * {@code docs/ARCHITECTURE.md}. Upstream's version caught {@code SecurityException}
 * specifically — the exact trigger — and then logged "assuming unrestricted" while reporting
 * {@code UX_RESTRICTIONS_BASELINE}: no restrictions, while driving, because car service could not be
 * reached. The fork fixed it here as well as in `car-ui-lib`, and until now the fix here had
 * <b>no test at all</b>, in a module with no tests at all.
 *
 * <p>It is also the copy that matters most for OEMs, because this is the file they clone.
 *
 * <p>Testable only after two seams were added to the production class — a replaceable fail-closed
 * supplier (the stub {@code android.car.jar} throws from {@code CarUxRestrictions.Builder}) and a
 * no-connection constructor. Both are documented at their definitions. The seams came before the
 * tests, which is the rule this project derived and then applied.
 */
@RunWith(RobolectricTestRunner.class)
public class CarUxRestrictionsUtilTest {

    private CarUxRestrictions mFailClosed;

    private static CarUxRestrictions restrictions(int activeFlags) {
        CarUxRestrictions r = mock(CarUxRestrictions.class);
        when(r.getActiveRestrictions()).thenReturn(activeFlags);
        return r;
    }

    @Before
    public void setUp() {
        mFailClosed = restrictions(UX_RESTRICTIONS_FULLY_RESTRICTED);
        CarUxRestrictionsUtil.setFailClosedFactoryForTesting(() -> mFailClosed);
    }

    // ------------------------------------------------------------------ the production constant

    /**
     * The one assertion in this file that survives the test seam.
     *
     * <p>Every other test here injects a fail-closed value through
     * {@code setFailClosedFactoryForTesting}, which means they assert on the injected constant and
     * <b>not</b> on what production uses. A positive control proved it: restoring the original
     * fail-open value inside the supplier was undetectable by the rest of this class.
     *
     * <p>These two assertions read the production constants directly, so mutating them fails here.
     * That is the difference between testing the behaviour and testing the test double.
     */
    @Test
    public void productionFailClosedValue_isFullyRestricted_notBaseline() {
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_ACTIVE_RESTRICTIONS)
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_ACTIVE_RESTRICTIONS)
                .isNotEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    @Test
    public void productionFailClosedValue_requiresDistractionOptimization() {
        assertThat(CarUxRestrictionsUtil.FAIL_CLOSED_REQUIRES_DISTRACTION_OPTIMIZATION).isTrue();
    }

    // ------------------------------------------------------------------ the default

    /**
     * The single most important assertion in this file: before car service says anything, the
     * reported value is FULLY_RESTRICTED, not BASELINE.
     */
    @Test
    public void beforeAnyDelivery_reportsFullyRestricted() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> seen = new ArrayList<>();

        util.register(seen::add);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * REGRESSION PIN for instance 1, in this copy.
     *
     * <p>A null reading — which is what upstream's {@code SecurityException} handler produces, and
     * what a disconnect produces — must resolve to fail-closed. If this ever reports BASELINE the
     * original defect is back.
     */
    @Test
    public void nullDelivery_failsClosed_notBaseline() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> seen = new ArrayList<>();
        util.register(seen::add);
        seen.clear();

        util.deliverForTesting(null);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertThat(seen.get(0).getActiveRestrictions()).isNotEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    /**
     * A real BASELINE reading must still be honoured. Without this, "always report fully
     * restricted" would pass every other test in this file — the mutation that turns a fail-closed
     * default into a permanently-restricted library.
     */
    @Test
    public void genuineBaselineDelivery_isHonoured() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> seen = new ArrayList<>();
        util.register(seen::add);
        seen.clear();

        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getActiveRestrictions()).isEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    // ------------------------------------------------------------------ the observer contract

    /**
     * {@code register} must seed synchronously.
     *
     * <p>This is what protects every consumer that reads restrictions at bind time: without the
     * immediate callback, a client registering while already restricted hears nothing until the
     * next change — instance 3 in the taxonomy.
     */
    @Test
    public void register_seedsSynchronously() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));
        List<CarUxRestrictions> seen = new ArrayList<>();

        util.register(seen::add);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getActiveRestrictions()).isEqualTo(UX_RESTRICTIONS_BASELINE);
    }

    @Test
    public void allRegisteredObserversAreNotified() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> a = new ArrayList<>();
        List<CarUxRestrictions> b = new ArrayList<>();
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener la = a::add;
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener lb = b::add;
        util.register(la);
        util.register(lb);
        a.clear();
        b.clear();

        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
    }

    @Test
    public void unregisteredObserverStopsReceiving() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> seen = new ArrayList<>();
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener listener = seen::add;
        util.register(listener);
        seen.clear();

        util.unregister(listener);
        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(seen).isEmpty();
    }

    /**
     * Observers are held in a {@code WeakHashMap}-backed set, so {@code unregister} matches by
     * identity. A listener that is equal-but-not-identical must not be removed — otherwise
     * unregistering one lambda could silently detach another.
     */
    @Test
    public void unregisterMatchesByIdentity() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        List<CarUxRestrictions> seen = new ArrayList<>();
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener kept = seen::add;
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener other = r -> { };
        util.register(kept);
        seen.clear();

        util.unregister(other);
        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        assertThat(seen).hasSize(1);
    }

    @Test
    public void instancesRetainTheDisplayTheyWereCreatedFor() {
        CarUxRestrictionsUtil driver = CarUxRestrictionsUtil.newForTesting(0);
        CarUxRestrictionsUtil passenger = CarUxRestrictionsUtil.newForTesting(7);

        assertThat(driver.getDisplayId()).isEqualTo(0);
        assertThat(passenger.getDisplayId()).isEqualTo(7);
    }

    @Test
    public void disconnectIsTerminalAndLateDeliveryCannotCommit() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        util.disconnect();
        util.deliverForTesting(restrictions(UX_RESTRICTIONS_BASELINE));

        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot = util.getRestrictionsSnapshot();
        assertThat(snapshot.getSource())
                .isEqualTo(CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED);
        assertThat(snapshot.getRestrictions().getActiveRestrictions())
                .isEqualTo(UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Test
    public void snapshotKeepsReadingAndProvenanceFromOneTransition() {
        CarUxRestrictionsUtil util = CarUxRestrictionsUtil.newForTesting();
        CarUxRestrictions baseline = restrictions(UX_RESTRICTIONS_BASELINE);
        util.deliverForTesting(baseline);

        CarUxRestrictionsUtil.RestrictionsSnapshot snapshot = util.getRestrictionsSnapshot();

        assertThat(snapshot.getRestrictions()).isSameInstanceAs(baseline);
        assertThat(snapshot.getSource()).isEqualTo(
                CarUxRestrictionsUtil.RestrictionsSource.DELIVERED);
    }
}
