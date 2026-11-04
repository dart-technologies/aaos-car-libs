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
package art.dart.a2ui.demo;

import androidx.annotation.Nullable;

/**
 * A demonstration-only override for the restriction bitmask, shared by both hosts' debug
 * broadcast. Each host owns and validates its accepted override.
 *
 * <p>Needed because the androidx.car.app host renders on a private virtual display, which car
 * service reports as the unknown state (0x1ff), so only the override can show the unrestricted
 * end. Surfaces and logs always label an override; the receiver exists only on debuggable,
 * non-user builds.
 */
public final class DemoRestrictions {

    /**
     * Broadcast action, debuggable builds only, named {@code <package>.action.<NAME>} per
     * metalava's ActionValue rule.
     */
    public static final String ACTION_SET = "art.dart.a2ui.demo.action.SET";

    /**
     * Int extra: the active-restrictions bitmask, or a negative value to clear the override. The
     * hosts' exported receivers are debuggable-only; a consumer registering one must add that guard.
     */
    public static final String EXTRA_ACTIVE = "art.dart.a2ui.demo.extra.ACTIVE";

    @Nullable
    private static volatile Integer sOverride;

    private DemoRestrictions() {
    }

    /**
     * The override, or null when the live reading is in force. metalava api-lint: AutoBoxing,
     * suppressed: null is the documented "no override" state.
     *
     * @deprecated Hosts must own a private, dual-gated override. This process-global value is
     *     retained only for binary compatibility.
     */
    @SuppressWarnings("AutoBoxing")
    @Nullable
    @Deprecated
    public static Integer get() {
        return sOverride;
    }

    /**
     * Sets the override; a null or negative value clears it. The AutoBoxing suppression sits on the
     * parameter because metalava reports it there.
     *
     * @deprecated Hosts must own a private, dual-gated override. This process-global value is
     *     retained only for binary compatibility.
     */
    @Deprecated
    public static void set(
            @SuppressWarnings("AutoBoxing") @Nullable Integer active) {
        sOverride = (active == null || active < 0) ? null : active;
    }
}
