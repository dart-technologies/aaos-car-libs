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

package android.car.media;

import android.content.ComponentName;

import java.util.List;

/**
 * COMPILE-TIME STUB. Never packaged, never executed.
 *
 * <p>{@code android.car.media.CarMediaManager} is {@code @SystemApi} and is therefore absent from
 * the public {@code android.car.jar} at every SDK level (verified against API 30, 31, 34, 35, 36
 * and 37). AOSP builds {@code car-media-common} against {@code android.car-system-stubs.jar} from
 * an internal {@code prebuilts/} tree, which no standalone checkout has.
 *
 * <p>This class exists so {@code car-media-common} compiles without that tree. It is consumed
 * {@code compileOnly}, so it is absent from every AAR and APK this repo produces; at runtime the
 * real implementation is supplied by car service on the device.
 *
 * <p><b>Signatures are copied verbatim from AOSP</b>
 * ({@code platform/packages/services/Car}, {@code car-lib/src/android/car/media/CarMediaManager.java},
 * {@code refs/heads/main}). They must stay byte-compatible with the platform class — a mismatch
 * compiles cleanly here and then fails on device with {@code NoSuchMethodError}. Only the members
 * this repo actually uses are declared; adding more is safe, changing a signature is not.
 *
 * <p>If you have a genuine {@code android.car-system-stubs.jar}, drop it at the path configured by
 * {@code gradle.ext.lib_car_system_stubs} in {@code settings.gradle} and it takes precedence over
 * this module. See {@code FORK.md}.
 */
public final class CarMediaManager {

    /** Matches AOSP: {@code public static final int MEDIA_SOURCE_MODE_PLAYBACK = 0;} */
    public static final int MEDIA_SOURCE_MODE_PLAYBACK = 0;

    /** Matches AOSP: {@code public static final int MEDIA_SOURCE_MODE_BROWSE = 1;} */
    public static final int MEDIA_SOURCE_MODE_BROWSE = 1;

    /** Listener for updates to the primary media source. */
    public interface MediaSourceChangedListener {
        /** Called when the primary media source is changed. */
        void onMediaSourceChanged(ComponentName componentName);
    }

    private CarMediaManager() {
        throw new UnsupportedOperationException("stub");
    }

    /** Gets the currently active media source for the provided mode. */
    public ComponentName getMediaSource(int mode) {
        throw new UnsupportedOperationException("stub");
    }

    /** Sets the currently active media source for the provided mode. */
    public void setMediaSource(ComponentName componentName, int mode) {
        throw new UnsupportedOperationException("stub");
    }

    /** Registers a callback that receives updates to the active media source. */
    public void addMediaSourceListener(MediaSourceChangedListener callback, int mode) {
        throw new UnsupportedOperationException("stub");
    }

    /** Unregisters a previously registered media source callback. */
    public void removeMediaSourceListener(MediaSourceChangedListener callback, int mode) {
        throw new UnsupportedOperationException("stub");
    }

    /** Retrieves a list of media sources, ordered by most recently used. */
    public List<ComponentName> getLastMediaSources(int mode) {
        throw new UnsupportedOperationException("stub");
    }

    /** Whether independent playback configuration is enabled. */
    public boolean isIndependentPlaybackConfig() {
        throw new UnsupportedOperationException("stub");
    }

    /** Sets whether independent playback configuration is enabled. */
    public void setIndependentPlaybackConfig(boolean independent) {
        throw new UnsupportedOperationException("stub");
    }
}
