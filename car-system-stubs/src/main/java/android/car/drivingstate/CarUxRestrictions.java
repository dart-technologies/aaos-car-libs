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

package android.car.drivingstate;

/**
 * COMPILE-TIME STUB. Never packaged, never executed. See {@code FORK.md}.
 *
 * <p>This class IS public API — it is reproduced only because {@code car-media-common} no longer
 * declares {@code useLibrary 'android.car'} (see {@link android.car.Car} for why). Only the two
 * constants that module references are declared.
 *
 * <p>Values read from {@code javap -constants} on the public {@code android.car.jar}, NOT from
 * documentation: a widely-copied table of these flags in circulation is wrong in most rows. Note
 * these are the PLURAL {@code UX_RESTRICTIONS_*} names, and that
 * {@code UX_RESTRICTIONS_FULLY_RESTRICTED} (0x1ff, deliberately not declared here) is a COMPOSITE
 * of the individual bits rather than a distinct flag — test it with
 * {@code (active & FULLY_RESTRICTED) == FULLY_RESTRICTED}, never with {@code !=&nbsp;0} and never
 * with {@code ==} on the whole field.
 */
public final class CarUxRestrictions {

    /** Matches platform: {@code 0}. */
    public static final int UX_RESTRICTIONS_BASELINE = 0;

    /** Matches platform: {@code 64} (0x40). */
    public static final int UX_RESTRICTIONS_NO_SETUP = 64;

    private CarUxRestrictions() {
        throw new UnsupportedOperationException("stub");
    }
}
