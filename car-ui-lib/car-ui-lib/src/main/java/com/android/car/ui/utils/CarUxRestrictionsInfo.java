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

package com.android.car.ui.utils;

import android.car.drivingstate.CarUxRestrictions;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Type annotation for a bitmask of {@code CarUxRestrictions.UX_RESTRICTIONS_*} flags.
 *
 * <p>Dart fork addition, replacing {@code android.car.drivingstate.CarUxRestrictions
 * .CarUxRestrictionsInfo}. That nested annotation is present in the public {@code android.car.jar}
 * for API 30, 31 and 34, and <b>absent from 35, 36 and 37</b> — which pinned this repo's
 * {@code compileSdk} to 34 purely to keep a compile-time lint annotation resolvable.
 *
 * <p>Its removal was deliberate, not accidental. AOSP
 * ({@code packages/services/Car}, {@code car-lib/src/android/car/drivingstate/CarUxRestrictions.java})
 * marks the declaration:
 *
 * <pre>{@code /** @removed accidentally exposed previously *}{@code /}</pre>
 *
 * <p>So the platform never intended it as API, and depending on it was always borrowed time.
 * Declaring an equivalent here keeps the static analysis while decoupling the fork's SDK ceiling
 * from an annotation Google withdrew.
 *
 * <p>The flag list is copied verbatim from that AOSP declaration, including its deliberate
 * omission of {@code UX_RESTRICTIONS_FULLY_RESTRICTED}: that value (0x1ff) is the bitwise union of
 * the ten flags below, not a distinct flag, and listing it in an {@code @IntDef} would invite
 * exactly the equality test that must never be used on this field. Use
 * {@link CarUxRestrictionsUtil#hasAllRestrictions} for the composite.
 *
 * <p>Constant values verified with {@code javap -constants} against the public
 * {@code android.car.jar}, identical across API 34, 35, 36 and 37.
 */
@Retention(RetentionPolicy.SOURCE)
// AOSP's declaration also carries prefix = {"UX_RESTRICTIONS_"}. That attribute exists only on the
// platform-internal android.annotation.IntDef, not on androidx.annotation.IntDef, and is purely a
// lint naming hint — it has no effect on which values are accepted.
@IntDef(flag = true,
        value = {CarUxRestrictions.UX_RESTRICTIONS_BASELINE,
                CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD,
                CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING,
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
                CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD,
                CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO,
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT,
                CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP,
                CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE,
                CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION})
public @interface CarUxRestrictionsInfo {
}
