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
package art.dart.a2ui.carapp;

import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.navigation.NavigationManager;

import art.dart.a2ui.nav.NavSurfaceController;

/**
 * Builds the navigation surface controller from a bound context. The listener is a no-op until
 * the tile renderer lands: the surface is owned and its lifecycle announced, but nothing draws
 * on it yet.
 */
public final class NavSurfaces {

    private NavSurfaces() {
    }

    /** A controller bound to this context's managers, or null when the car service is missing. */
    @NonNull
    public static NavSurfaceController of(@NonNull CarContext carContext) {
        AppManager appManager = carContext.getCarService(AppManager.class);
        NavigationManager navigationManager = carContext.getCarService(NavigationManager.class);
        return new NavSurfaceController(appManager, navigationManager,
                new NavSurfaceController.Listener() {
                    @Override
                    public void onSurfaceReady(@NonNull Surface surface, int width, int height) {
                        // No tile renderer yet; the surface is owned but undrawn.
                    }

                    @Override
                    public void onSurfaceGone() {
                        // No tile renderer yet; nothing to detach.
                    }
                });
    }
}
