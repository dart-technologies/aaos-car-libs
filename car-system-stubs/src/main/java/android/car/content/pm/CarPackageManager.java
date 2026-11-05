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

package android.car.content.pm;

/**
 * COMPILE-TIME STUB. Never packaged, never executed. See {@code FORK.md}.
 *
 * <p>This class IS public API — it is reproduced only because {@code car-media-common} no longer
 * declares {@code useLibrary 'android.car'} (see {@link android.car.Car} for why).
 *
 * <p>{@code car-media-common} uses it purely as a type: it casts the result of
 * {@code Car.getCarManager(Car.PACKAGE_SERVICE)} to this type and hands it to
 * {@code CarPackageManagerUtils.isDistractionOptimized(...)} in {@code car-apps-common}. No member
 * of this class is called from this repo, so none is declared. {@code car-apps-common} compiles
 * that helper against the REAL platform class; the two agree because linkage is by binary name,
 * and at runtime the platform class is the only one present.
 *
 * <p>Declared {@code final} to match the platform.
 */
public final class CarPackageManager {

    private CarPackageManager() {
        throw new UnsupportedOperationException("stub");
    }
}
