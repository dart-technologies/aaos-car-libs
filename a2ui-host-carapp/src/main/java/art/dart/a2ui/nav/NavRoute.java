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

import androidx.annotation.NonNull;

import art.dart.a2ui.poi.PoiPlace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A route to an anchored place: ordered steps plus totals. A real routing provider replaces
 * {@link #anchored}, not the screens or the trip.
 */
public final class NavRoute {

    private final PoiPlace mDestination;
    private final List<NavStep> mSteps;
    private final int mTotalDistanceMeters;
    private final int mTotalTimeSeconds;

    public NavRoute(@NonNull PoiPlace destination, @NonNull List<NavStep> steps,
            int totalDistanceMeters, int totalTimeSeconds) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one step");
        }
        if (totalDistanceMeters < 0 || totalTimeSeconds < 0) {
            throw new IllegalArgumentException("route totals must be non-negative");
        }
        mDestination = destination;
        mSteps = Collections.unmodifiableList(new ArrayList<>(steps));
        mTotalDistanceMeters = totalDistanceMeters;
        mTotalTimeSeconds = totalTimeSeconds;
    }

    /**
     * The Sprint 2 anchor route: Grand Central to Joe's Pizza, five downtown steps. The emulator
     * walk and the JVM tests share it, the way Sprint 1 shared the POI anchor set.
     */
    @NonNull
    public static NavRoute anchored() {
        // Maneuver types are androidx.car.app.navigation.model.Maneuver constants, spelled out
        // so this backend stays car-model-free (read with javap -constants from car-app 1.7.0):
        // DEPART 1, TURN_NORMAL_LEFT 7, TURN_NORMAL_RIGHT 8, KEEP_RIGHT 4, DESTINATION 39.
        List<NavStep> steps = new ArrayList<>();
        steps.add(new NavStep("Head south on Park Ave", 1, 400));
        steps.add(new NavStep("Turn right onto E 34th St", 8, 650));
        steps.add(new NavStep("Turn left onto 5th Ave", 7, 1800));
        steps.add(new NavStep("Keep right onto W Broadway", 4, 900));
        steps.add(new NavStep("Arrive at Joe's Pizza", 39, 50));
        PoiPlace destination = new PoiPlace("joes_pizza", "Joe's Pizza", "7 Carmine St", "Food",
                "Greenwich Village slice shop since 1975. Cash moves the line fastest.", 1800);
        return new NavRoute(destination, steps, 3800, 780);
    }

    @NonNull public PoiPlace getDestination() { return mDestination; }
    @NonNull public List<NavStep> getSteps() { return mSteps; }
    public int getTotalDistanceMeters() { return mTotalDistanceMeters; }
    public int getTotalTimeSeconds() { return mTotalTimeSeconds; }
}
