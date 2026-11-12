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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.navigation.NavigationManager;
import androidx.car.app.navigation.NavigationManagerCallback;

import org.mockito.ArgumentCaptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The surface lifecycle: register and announce on start, report ready/gone, end on stop. */
@RunWith(RobolectricTestRunner.class)
public class NavSurfaceControllerTest {

    private static final class RecordingListener implements NavSurfaceController.Listener {
        int mReady;
        int mGone;

        @Override
        public void onSurfaceReady(Surface surface, int width, int height) {
            mReady++;
        }

        @Override
        public void onSurfaceGone() {
            mGone++;
        }
    }

    @Test
    public void startRegistersAndAnnouncesStopEnds() {
        AppManager appManager = mock(AppManager.class);
        NavigationManager navigationManager = mock(NavigationManager.class);
        NavSurfaceController controller =
                new NavSurfaceController(appManager, navigationManager, new RecordingListener());

        controller.start();
        controller.start();

        assertThat(controller.isStarted()).isTrue();
        verify(appManager).setSurfaceCallback(controller);
        // navigationStarted throws on device without a manager callback set first.
        verify(navigationManager).setNavigationManagerCallback(
                any(NavigationManagerCallback.class));
        verify(navigationManager).navigationStarted();

        controller.stop();
        controller.stop();

        assertThat(controller.isStarted()).isFalse();
        verify(navigationManager).clearNavigationManagerCallback();
        verify(navigationManager).navigationEnded();
    }

    @Test
    public void hostSideStopEndsAnnouncements() {
        NavigationManager navigationManager = mock(NavigationManager.class);
        NavSurfaceController controller = new NavSurfaceController(
                mock(AppManager.class), navigationManager, new RecordingListener());
        controller.start();
        ArgumentCaptor<NavigationManagerCallback> captor =
                ArgumentCaptor.forClass(NavigationManagerCallback.class);
        verify(navigationManager).setNavigationManagerCallback(captor.capture());

        captor.getValue().onStopNavigation();

        assertThat(controller.isStarted()).isFalse();
        verify(navigationManager).navigationEnded();
    }

    @Test
    public void surfaceReadyAndGoneReachTheListener() {
        RecordingListener listener = new RecordingListener();
        NavSurfaceController controller = new NavSurfaceController(
                mock(AppManager.class), mock(NavigationManager.class), listener);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        SurfaceContainer container = mock(SurfaceContainer.class);
        when(container.getSurface()).thenReturn(surface);
        when(container.getWidth()).thenReturn(1080);
        when(container.getHeight()).thenReturn(600);

        controller.surfaceAvailableForTest(container);

        assertThat(controller.isSurfaceAvailable()).isTrue();
        assertThat(listener.mReady).isEqualTo(1);

        controller.surfaceDestroyedForTest(container);

        assertThat(controller.isSurfaceAvailable()).isFalse();
        assertThat(listener.mGone).isEqualTo(1);
    }

    @Test
    public void invalidSurfaceIsIgnoredAndStrayDestroyIsSilent() {
        RecordingListener listener = new RecordingListener();
        NavSurfaceController controller = new NavSurfaceController(
                mock(AppManager.class), mock(NavigationManager.class), listener);
        SurfaceContainer container = mock(SurfaceContainer.class);
        when(container.getSurface()).thenReturn(mock(Surface.class));

        // isValid() defaults to false on the mock: no listener call, no state.
        controller.surfaceAvailableForTest(container);
        assertThat(controller.isSurfaceAvailable()).isFalse();
        assertThat(listener.mReady).isEqualTo(0);

        // Destroyed with nothing held: silent, so a double callback cannot desync.
        controller.surfaceDestroyedForTest(container);
        assertThat(listener.mGone).isEqualTo(0);
        verify(container, never()).getWidth();
    }

    @Test
    public void stopClearsASurfaceWithoutTellingTheListener() {
        RecordingListener listener = new RecordingListener();
        NavSurfaceController controller = new NavSurfaceController(
                mock(AppManager.class), mock(NavigationManager.class), listener);
        controller.surfaceAvailableForTest(validContainer());
        controller.start();

        controller.stop();

        assertThat(controller.isSurfaceAvailable()).isFalse();
        // The host owns the destroy callback; stop() must not invent a second gone.
        assertThat(listener.mGone).isEqualTo(0);
    }

    private static SurfaceContainer validContainer() {
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        SurfaceContainer container = mock(SurfaceContainer.class);
        when(container.getSurface()).thenReturn(surface);
        return container;
    }
}
