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

import android.graphics.Rect;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.AppManager;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.navigation.NavigationManager;
import androidx.car.app.navigation.NavigationManagerCallback;

/**
 * Owns the navigation map surface: registers the {@code SurfaceCallback} that receives it,
 * tells the host when guidance starts and ends, and reports surface state to one listener. It
 * draws no tiles — a tile renderer attaches to the listener, not to the host managers.
 *
 * <p>Lifecycle is explicit: {@link #start} registers and announces, {@link #stop} announces the
 * end and unregisters. A destroyed surface mid-guidance reports unavailable; the trip (what to
 * say) and the surface (what to draw on) fail independently on purpose.
 */
// metalava api-lint: NotCloseable, suppressed: start/stop is bound to the screen's
// resume/pause, not a held resource; AutoCloseable would promise try-with-resources semantics
// the host callback thread cannot honor.
@SuppressWarnings("NotCloseable")
public final class NavSurfaceController implements SurfaceCallback {

    /** The tile renderer's view of the surface. All calls arrive on the host's callback thread. */
    public interface Listener {
        void onSurfaceReady(@NonNull Surface surface, int width, int height);
        void onSurfaceGone();
    }

    private final AppManager mAppManager;
    private final NavigationManager mNavigationManager;
    private final Listener mListener;
    private boolean mStarted;
    private boolean mSurfaceAvailable;

    // metalava api-lint: ExecutorRegistration, suppressed: AppManager offers no executor
    // overload for setSurfaceCallback, and the listener contract already documents the host
    // callback thread.
    @SuppressWarnings("ExecutorRegistration")
    public NavSurfaceController(@NonNull AppManager appManager,
            @NonNull NavigationManager navigationManager, @NonNull Listener listener) {
        mAppManager = appManager;
        mNavigationManager = navigationManager;
        mListener = listener;
    }

    /**
     * Registers the surface callback and announces guidance. Idempotent.
     * {@code navigationStarted} throws without a manager callback, so one is always set first;
     * a host-side stop ends the announcements, mirroring {@link #stop}.
     */
    public void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mAppManager.setSurfaceCallback(this);
        mNavigationManager.setNavigationManagerCallback(new NavigationManagerCallback() {
            @Override
            public void onStopNavigation() {
                stop();
            }
        });
        mNavigationManager.navigationStarted();
    }

    /** Announces the end of guidance and unregisters. Idempotent. */
    public void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mSurfaceAvailable = false;
        mNavigationManager.clearNavigationManagerCallback();
        mNavigationManager.navigationEnded();
    }

    /** Whether guidance is announced, regardless of surface state. */
    public boolean isStarted() {
        return mStarted;
    }

    /** Whether a live surface is currently held. */
    public boolean isSurfaceAvailable() {
        return mSurfaceAvailable;
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        Surface surface = surfaceContainer.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        mSurfaceAvailable = true;
        mListener.onSurfaceReady(surface, surfaceContainer.getWidth(),
                surfaceContainer.getHeight());
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        if (!mSurfaceAvailable) {
            return;
        }
        mSurfaceAvailable = false;
        mListener.onSurfaceGone();
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        // A tile renderer consumes this; the controller only owns the surface.
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect stableArea) {
        // A tile renderer consumes this; the controller only owns the surface.
    }

    /** Drives the available path in tests without a host-provided container. */
    @VisibleForTesting
    void surfaceAvailableForTest(@NonNull SurfaceContainer surfaceContainer) {
        onSurfaceAvailable(surfaceContainer);
    }

    /** Drives the destroyed path in tests without a host-provided container. */
    @VisibleForTesting
    void surfaceDestroyedForTest(@NonNull SurfaceContainer surfaceContainer) {
        onSurfaceDestroyed(surfaceContainer);
    }

    @Nullable
    @VisibleForTesting
    SurfaceCallback callbackForTest() {
        return mStarted ? this : null;
    }
}
