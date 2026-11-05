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
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.provider.Settings;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Driver media that joins the passenger's clip: the passenger's live position, and where a copy of
 * the same clip starts on the driver's display.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiDriverMediaTest {

    private Application mApp;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        CarUiInstaller.register(mApp);
        CarUxRestrictionsUtil.clearInstancesForTesting();
        Settings.Global.putFloat(mApp.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    @After
    public void tearDown() {
        shadowOf(Looper.getMainLooper()).idle();
        PassengerDisplay.resetForTesting();
    }

    /** A passenger surface reporting {@code media} at a settable live position. */
    private static PassengerDisplay.Surface passengerAt(int[] positionMs) {
        return new PassengerDisplay.Surface() {
            @Override
            public void play(PassengerMedia media) {
            }

            @Override
            public void stopMedia() {
            }

            @Override
            public int positionMs() {
                return positionMs[0];
            }
        };
    }

    @Test
    public void theDriversCopyStartsWhereThePassengerIsOnlyInTheSameClip() {
        // The passenger plays Big Buck Bunny (its portrait cut, a crop of this file) at 7.2 s.
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 7200, "bbb_passenger",
                19300)).isEqualTo(7200);
        // Into one loop, as both loop; and as it is while the length is unknown.
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 19800, "bbb_passenger",
                19300)).isEqualTo(500);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 7200, "bbb_passenger",
                0)).isEqualTo(7200);
        // Not playing, another clip, nothing at a position or an unknown name: from the top.
        assertThat(PassengerDisplay.startPositionMs(null, 7200, "bbb_passenger", 19300))
                .isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, 7200,
                "demo_placeholder_video", 19300)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs(PassengerMedia.VIDEO, -1, "bbb_passenger",
                19300)).isEqualTo(0);
        assertThat(PassengerDisplay.startPositionMs("nope", 7200, "bbb_passenger", 19300))
                .isEqualTo(0);
    }

    @Test
    public void thePassengersPositionIsReadLiveFromItsSurface() {
        int[] position = {4100};
        PassengerDisplay.Surface surface = passengerAt(position);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
        PassengerDisplay.attach(surface);
        // Attached but playing nothing: no position.
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
        PassengerDisplay.report(PassengerMedia.VIDEO, 0);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(4100);
        position[0] = 4600;
        assertThat(PassengerDisplay.positionMs()).isEqualTo(4600);
        PassengerDisplay.detach(surface);
        assertThat(PassengerDisplay.positionMs()).isEqualTo(-1);
    }
}
