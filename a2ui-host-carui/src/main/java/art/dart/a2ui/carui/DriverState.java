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

import androidx.annotation.NonNull;

import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiRestrictionPolicy;

/**
 * The consumer-facing vehicle state the header chip shows, from the same (source, mask) pair the
 * prune uses. Only a delivered reading is Parked or Driving; an injected (DEMO) one says Simulated,
 * and an untrusted one Safe mode, so the chip can never claim motion or rest the vehicle did not
 * report.
 */
final class DriverState {

    enum Kind { PARKED, DRIVING, SAFE_MODE, DEMO }

    private DriverState() {
    }

    @NonNull
    static Kind kindFor(@NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            int activeRestrictions) {
        switch (source) {
            case DELIVERED:
                return activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE
                        ? Kind.PARKED : Kind.DRIVING;
            case DEMO:
                return Kind.DEMO;
            case NEVER_DELIVERED:
            case DISCONNECTED:
            default:
                return Kind.SAFE_MODE;
        }
    }

    /** The chip's word. */
    static int titleRes(@NonNull Kind kind) {
        switch (kind) {
            case PARKED:
                return R.string.a2ui_state_parked;
            case DRIVING:
                return R.string.a2ui_state_driving;
            case DEMO:
                return R.string.a2ui_state_demo;
            case SAFE_MODE:
            default:
                return R.string.a2ui_state_safe_mode;
        }
    }

    /** The longer line, spoken with the chip (its content description), not drawn. */
    static int detailRes(@NonNull CarUxRestrictionsUtil.RestrictionsSource source,
            int activeRestrictions) {
        switch (kindFor(source, activeRestrictions)) {
            case PARKED:
                return R.string.a2ui_state_parked_detail;
            case DRIVING:
                return R.string.a2ui_state_driving_detail;
            case DEMO:
                return activeRestrictions == A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE
                        ? R.string.a2ui_state_demo_open_detail
                        : R.string.a2ui_state_demo_restricted_detail;
            case SAFE_MODE:
            default:
                return source == CarUxRestrictionsUtil.RestrictionsSource.DISCONNECTED
                        ? R.string.a2ui_state_safe_offline_detail
                        : R.string.a2ui_state_safe_waiting_detail;
        }
    }

    /** The chip's fill, a brand token per state. */
    static int colorRes(@NonNull Kind kind) {
        switch (kind) {
            case PARKED:
                return R.color.a2ui_brand_state_parked;
            case DRIVING:
                return R.color.a2ui_brand_state_driving;
            case DEMO:
                return R.color.a2ui_brand_state_demo;
            case SAFE_MODE:
            default:
                return R.color.a2ui_brand_state_safe;
        }
    }

    /** The chip's dot: the fill's hue, brighter. */
    static int dotColorRes(@NonNull Kind kind) {
        switch (kind) {
            case PARKED:
                return R.color.a2ui_brand_state_parked_dot;
            case DRIVING:
                return R.color.a2ui_brand_state_driving_dot;
            case DEMO:
                return R.color.a2ui_brand_state_demo_dot;
            case SAFE_MODE:
            default:
                return R.color.a2ui_brand_state_safe_dot;
        }
    }
}
