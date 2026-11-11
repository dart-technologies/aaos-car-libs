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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * One guided trip: the turn-by-turn data contract the navigation screens render.
 * {@code IDLE → PREVIEW → ACTIVE → ARRIVED}; {@code cancel()} lands back at {@code IDLE} from
 * anywhere. Advancing past the last step arrives; anything else out of order throws rather than
 * guessing — a trip that silently restarts mid-drive is worse than one that refuses.
 */
public final class NavTrip {

    /** The trip lifecycle, in order. */
    // metalava api-lint: Enum. Suppressed, not converted to @IntDef: callers switch on the
    // lifecycle and an int def would trade exhaustiveness for nothing at this scale.
    @SuppressWarnings("Enum")
    public enum State {
        IDLE, PREVIEW, ACTIVE, ARRIVED
    }

    private final NavRoute mRoute;
    private State mState = State.IDLE;
    private int mStepIndex;

    public NavTrip(@NonNull NavRoute route) {
        mRoute = route;
    }

    @NonNull public NavRoute getRoute() { return mRoute; }
    @NonNull public State getState() { return mState; }

    /** The route preview screen's trip: legal only before anything has started. */
    public void preview() {
        require(State.IDLE, "preview");
        mState = State.PREVIEW;
    }

    /** Guidance starts at the first step, from preview or straight from idle. */
    public void start() {
        if (mState != State.IDLE && mState != State.PREVIEW) {
            throw new IllegalStateException("start() needs IDLE or PREVIEW, was " + mState);
        }
        mState = State.ACTIVE;
        mStepIndex = 0;
    }

    /** The step being driven now; null unless active. */
    @Nullable
    public NavStep currentStep() {
        return mState == State.ACTIVE ? mRoute.getSteps().get(mStepIndex) : null;
    }

    /** The step after this one; null on the last step or unless active. */
    @Nullable
    public NavStep nextStep() {
        if (mState != State.ACTIVE) {
            return null;
        }
        List<NavStep> steps = mRoute.getSteps();
        return mStepIndex + 1 < steps.size() ? steps.get(mStepIndex + 1) : null;
    }

    /**
     * Completes the current step. Advancing past the last step arrives instead of wrapping or
     * throwing: the arrival is the point, not an edge case.
     */
    public void advance() {
        require(State.ACTIVE, "advance");
        if (mStepIndex + 1 >= mRoute.getSteps().size()) {
            mState = State.ARRIVED;
        } else {
            mStepIndex++;
        }
    }

    /** Meters left from the current step on; zero unless active. */
    public int remainingDistanceMeters() {
        if (mState != State.ACTIVE) {
            return 0;
        }
        int remaining = 0;
        List<NavStep> steps = mRoute.getSteps();
        for (int i = mStepIndex; i < steps.size(); i++) {
            remaining += steps.get(i).getDistanceMeters();
        }
        return remaining;
    }

    /** Ends guidance from any state. */
    public void cancel() {
        mState = State.IDLE;
    }

    private void require(State want, String op) {
        if (mState != want) {
            throw new IllegalStateException(op + "() needs " + want + ", was " + mState);
        }
    }
}
