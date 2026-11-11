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

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.Screen;

import java.util.Objects;

/**
 * The navigation flow's two rules: how it launches, and how deep it goes. Preview, navigating
 * is two screens; the same four-screen cap as POI refuses a fifth push rather than stacking it.
 * A dedicated NAV_* telemetry vocabulary is deferred to a model PR; the cap refusal here is
 * observable through the return value and the tests.
 */
public final class NavFlow {

    /** Set on the launch intent to root the session at route preview instead of the showcase. */
    public static final String EXTRA_NAV_LAUNCH = "art.dart.a2ui.nav.extra.NAV_LAUNCH";

    /** Preview, navigating — capped at the same four as POI. */
    // metalava api-lint: MinMaxConstant, suppressed: the cap is the feature, not a tuning value.
    @SuppressWarnings("MinMaxConstant")
    public static final int MAX_SCREENS = 4;

    private NavFlow() {
    }

    /** Whether this launch roots at route preview. A null intent roots at the showcase. */
    public static boolean isNavLaunch(@Nullable Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_NAV_LAUNCH, false);
    }

    /**
     * Pushes {@code next} unless {@code from} is already the last allowed screen.
     *
     * @return true if the screen was pushed, false if the cap refused it
     */
    public static boolean push(@NonNull Screen from, @NonNull Screen next) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(next);
        int depth = from instanceof NavScreen ? ((NavScreen) from).navDepth() : 0;
        if (depth >= MAX_SCREENS) {
            return false;
        }
        from.getScreenManager().push(next);
        return true;
    }
}
