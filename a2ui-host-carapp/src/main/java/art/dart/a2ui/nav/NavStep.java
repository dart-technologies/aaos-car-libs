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

/**
 * One turn-by-turn step: a glanceable instruction, its maneuver icon type (one of
 * {@code Maneuver.TYPE_*}, kept as an int so this backend compiles without car-app model
 * imports), and the distance in meters the step covers.
 */
public final class NavStep {

    private final String mInstruction;
    private final int mManeuverType;
    private final int mDistanceMeters;

    public NavStep(@NonNull String instruction, int maneuverType, int distanceMeters) {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("step distance must be non-negative");
        }
        mInstruction = instruction;
        mManeuverType = maneuverType;
        mDistanceMeters = distanceMeters;
    }

    @NonNull public String getInstruction() { return mInstruction; }
    public int getManeuverType() { return mManeuverType; }
    public int getDistanceMeters() { return mDistanceMeters; }
}
