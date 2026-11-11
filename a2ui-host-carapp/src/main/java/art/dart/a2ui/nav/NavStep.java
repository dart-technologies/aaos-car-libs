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
import androidx.car.app.navigation.model.Maneuver;

/**
 * One turn-by-turn step: a glanceable instruction, its maneuver icon type (a
 * {@code Maneuver.TYPE_*}, typed so lint proves it at the builder), and the distance in meters
 * the step covers.
 */
public final class NavStep {

    private final String mInstruction;
    @Maneuver.Type private final int mManeuverType;
    private final int mDistanceMeters;

    public NavStep(@NonNull String instruction, @Maneuver.Type int maneuverType,
            int distanceMeters) {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("step distance must be non-negative");
        }
        mInstruction = instruction;
        mManeuverType = maneuverType;
        mDistanceMeters = distanceMeters;
    }

    @NonNull public String getInstruction() { return mInstruction; }
    @Maneuver.Type public int getManeuverType() { return mManeuverType; }
    public int getDistanceMeters() { return mDistanceMeters; }
}
