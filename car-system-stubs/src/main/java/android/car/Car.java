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

package android.car;

import android.content.Context;

/**
 * COMPILE-TIME STUB. Never packaged, never executed. See {@code FORK.md}.
 *
 * <p>Only {@code CAR_MEDIA_SERVICE} is actually missing from the public {@code android.car.jar};
 * the rest of this class is reproduced so {@code car-media-common} can compile against a single
 * consistent {@code android.car.Car} instead of half-shadowing the platform one. (A stub cannot
 * merely add a field: {@code useLibrary 'android.car'} puts the real jar on the bootclasspath,
 * which javac searches before the classpath, so the platform class would always win.)
 *
 * <p>Values verified two ways, not guessed:
 * <ul>
 *   <li>{@code CAR_INTENT_ACTION_MEDIA_TEMPLATE}, {@code PACKAGE_SERVICE} and all signatures —
 *       {@code javap -constants} against
 *       {@code $ANDROID_HOME/platforms/android-34/optional/android.car.jar}.
 *   <li>{@code CAR_MEDIA_SERVICE = "car_media"} — {@code @SystemApi}, absent from every public
 *       jar; read from AOSP {@code packages/services/Car}, {@code car-lib/src/android/car/Car.java}
 *       at {@code refs/heads/main}.
 * </ul>
 *
 * <p>These constants are compile-time inlined by javac, so a wrong value here is baked
 * permanently into consumer bytecode and fails silently on device. Do not edit without
 * re-verifying against the platform.
 */
public final class Car {

    /** Matches platform: {@code "android.car.intent.action.MEDIA_TEMPLATE"}. */
    public static final String CAR_INTENT_ACTION_MEDIA_TEMPLATE =
            "android.car.intent.action.MEDIA_TEMPLATE";

    /** Matches platform: {@code "package"}. */
    public static final String PACKAGE_SERVICE = "package";

    /** {@code @SystemApi}. Matches platform: {@code "car_media"}. */
    public static final String CAR_MEDIA_SERVICE = "car_media";

    private Car() {
        throw new UnsupportedOperationException("stub");
    }

    /** Matches platform: {@code public static Car createCar(Context)}. */
    public static Car createCar(Context context) {
        throw new UnsupportedOperationException("stub");
    }

    /** Matches platform: {@code public void disconnect()}. */
    public void disconnect() {
        throw new UnsupportedOperationException("stub");
    }

    /** Matches platform: {@code public Object getCarManager(String)}. */
    public Object getCarManager(String serviceName) {
        throw new UnsupportedOperationException("stub");
    }
}
