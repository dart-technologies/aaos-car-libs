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
package art.dart.a2ui.weather;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.Screen;

import java.util.Objects;

/**
 * The weather flow's two rules: how it launches, and how deep it goes. One screen today; the
 * same four-screen cap as the other flows refuses a fifth push rather than stacking it.
 */
public final class WeatherFlow {

    /** Set on the launch intent to root the session at the weather list. */
    public static final String EXTRA_WEATHER_LAUNCH = "art.dart.a2ui.weather.extra.WEATHER_LAUNCH";

    /** One screen today — capped at the same four as POI and nav. */
    // metalava api-lint: MinMaxConstant, suppressed: the cap is the feature, not a tuning value.
    @SuppressWarnings("MinMaxConstant")
    public static final int MAX_SCREENS = 4;

    private WeatherFlow() {
    }

    /** Whether this launch roots at the weather list. A null intent roots at the showcase. */
    public static boolean isWeatherLaunch(@Nullable Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_WEATHER_LAUNCH, false);
    }

    /**
     * Pushes {@code next} unless {@code from} is already the last allowed screen.
     *
     * @return true if the screen was pushed, false if the cap refused it
     */
    public static boolean push(@NonNull Screen from, @NonNull Screen next) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(next);
        int depth = from instanceof WeatherScreen ? ((WeatherScreen) from).weatherDepth() : 0;
        if (depth >= MAX_SCREENS) {
            return false;
        }
        from.getScreenManager().push(next);
        return true;
    }
}
