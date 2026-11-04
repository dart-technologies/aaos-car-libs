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
package art.dart.a2ui.policy;

import androidx.annotation.NonNull;

/**
 * What an action does, in the terms a restriction policy can reason about: a glanceable Button can
 * still carry {@code delete_all}. Ordered by consequence, so policy is "nothing above this class".
 */
// metalava api-lint: Enum. Suppressed, not converted to @IntDef: it is already public API
// (docs/api-surface/a2ui-model.txt), valueOf() rejects unknown agent values, and severity() and
// isAtLeast() are instance methods.
@SuppressWarnings("Enum")
public enum A2uiActionClass {

    /** Reversible, local, no outward effect. Pausing playback is undoable by pressing play. */
    SAFE(0),

    /** Moves between surfaces. Bounded already by what pruning left reachable. */
    NAVIGATION(1),

    /** Requires the keyboard. Gated by {@code NO_KEYBOARD} rather than by consequence. */
    TEXT_ENTRY(2),

    /** Outward-facing or irreversible: sends, calls, spends, shares. Cannot be taken back. */
    CONSEQUENTIAL(3),

    /** Destroys data: irreversible and silent, so the driver may not notice until much later. */
    DESTRUCTIVE(4);

    /** Explicit, not {@link #ordinal()}: reordering the declarations must not invert a comparison. */
    private final int mSeverity;

    A2uiActionClass(int severity) {
        mSeverity = severity;
    }

    public int severity() {
        return mSeverity;
    }

    /** True if this class is at least as consequential as {@code other}. */
    public boolean isAtLeast(@NonNull A2uiActionClass other) {
        return mSeverity >= other.mSeverity;
    }

    /** The more consequential of two classifications; disagreement resolves upward, never down. */
    @NonNull
    public static A2uiActionClass mostRestrictive(
            @NonNull A2uiActionClass a, @NonNull A2uiActionClass b) {
        return a.mSeverity >= b.mSeverity ? a : b;
    }
}
