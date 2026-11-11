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
package art.dart.a2ui.nav;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The trip contract: preview, start, advance, arrive, cancel — and a refusal for everything out
 * of order.
 */
@RunWith(RobolectricTestRunner.class)
public class NavTripTest {

    @Test
    public void anchoredRouteHasFiveStepsAndConsistentTotals() {
        NavRoute route = NavRoute.anchored();

        assertThat(route.getSteps()).hasSize(5);
        assertThat(route.getDestination().getName()).isEqualTo("Joe's Pizza");
        int stepped = 0;
        for (NavStep step : route.getSteps()) {
            stepped += step.getDistanceMeters();
        }
        assertThat(route.getTotalDistanceMeters()).isEqualTo(stepped);
        assertThat(route.getTotalTimeSeconds()).isGreaterThan(0);
    }

    @Test
    public void previewThenStartThenAdvanceToArrival() {
        NavTrip trip = new NavTrip(NavRoute.anchored());

        trip.preview();
        assertThat(trip.getState()).isEqualTo(NavTrip.State.PREVIEW);
        assertThat(trip.currentStep()).isNull();

        trip.start();
        assertThat(trip.getState()).isEqualTo(NavTrip.State.ACTIVE);
        assertThat(trip.currentStep().getInstruction()).contains("Park Ave");
        assertThat(trip.nextStep().getInstruction()).contains("34th");
        assertThat(trip.remainingDistanceMeters()).isEqualTo(3800);

        trip.advance();
        assertThat(trip.currentStep().getInstruction()).contains("34th");
        assertThat(trip.remainingDistanceMeters()).isEqualTo(3400);

        trip.advance();
        trip.advance();
        trip.advance();
        assertThat(trip.getState()).isEqualTo(NavTrip.State.ACTIVE);
        assertThat(trip.nextStep()).isNull();

        // Past the last step arrives instead of wrapping or throwing.
        trip.advance();
        assertThat(trip.getState()).isEqualTo(NavTrip.State.ARRIVED);
        assertThat(trip.currentStep()).isNull();
        assertThat(trip.remainingDistanceMeters()).isEqualTo(0);
    }

    @Test
    public void outOfOrderCallsThrowAndCancelLandsAtIdle() {
        NavTrip trip = new NavTrip(NavRoute.anchored());

        assertThrows(IllegalStateException.class, trip::advance);
        trip.start();
        assertThrows(IllegalStateException.class, trip::preview);
        assertThrows(IllegalStateException.class, trip::start);

        trip.cancel();
        assertThat(trip.getState()).isEqualTo(NavTrip.State.IDLE);

        // Preview twice is also out of order.
        trip.preview();
        assertThrows(IllegalStateException.class, trip::preview);
    }
}
