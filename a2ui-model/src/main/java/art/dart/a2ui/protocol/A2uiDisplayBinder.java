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
package art.dart.a2ui.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiActionPolicy;

/**
 * An {@link A2uiActionBinder} bound to one display's vehicle state, for both questions a renderer
 * asks: may this be offered, and may it now be run.
 *
 * <p>The reader is a constructor argument, so a renderer cannot reach another display's state.
 * {@link #decisionFor} decides what to draw; {@link #onInvoke} and {@link #confirm} re-read the
 * reader at touch time, because a draw-time or transfer-time snapshot is not authorization.
 */
public final class A2uiDisplayBinder implements A2uiActionBinder {

    /**
     * What the host does once the policy has answered, kept out of the binder so the deciding half
     * is testable in a local JVM.
     */
    public interface Outcome {
        void onDecided(@NonNull A2uiComponent component,
                @NonNull A2uiActionPolicy.Decision decision);
    }

    private final A2uiMessageProcessor mProcessor;
    private final String mSurfaceId;
    private final A2uiMessageProcessor.RestrictionReader mReader;
    private final Outcome mOutcome;

    /**
     * @param reader this display's live vehicle state, called again by every method here; a stored
     *        value would reintroduce the snapshot this class avoids
     */
    public A2uiDisplayBinder(@NonNull A2uiMessageProcessor processor, @NonNull String surfaceId,
            @NonNull A2uiMessageProcessor.RestrictionReader reader, @NonNull Outcome outcome) {
        mProcessor = processor;
        mSurfaceId = surfaceId;
        mReader = reader;
        mOutcome = outcome;
    }

    /** The display this binder speaks for, for logging. */
    @NonNull
    public A2uiMessageProcessor.RestrictionReader reader() {
        return mReader;
    }

    @Override
    @Nullable
    public A2uiActionPolicy.Decision decisionFor(@NonNull A2uiComponent component) {
        if (!component.isInteractive()) {
            return null;
        }
        A2uiMessageProcessor.RestrictionReading reading = mReader.currentReading();
        return A2uiActionPolicy.decide(
                A2uiActionPolicy.classify(component.getAction(), component.getActionClass()),
                reading.getActiveRestrictions(), reading.isTrustworthy());
    }

    @Override
    public void onInvoke(@NonNull A2uiComponent component) {
        // Through the processor, which alone checks that the component owns the action.
        mOutcome.onDecided(component, mProcessor.dispatchAction(
                mSurfaceId, String.valueOf(component.getId()), component.getAction(), null,
                component.getActionClass(), mReader));
    }

    /** Answers a confirmation against this display, re-read now. */
    @NonNull
    public A2uiActionPolicy.Decision confirm(@NonNull A2uiComponent component) {
        return mProcessor.confirmAction(mSurfaceId, String.valueOf(component.getId()),
                component.getAction(), null, component.getActionClass(), mReader);
    }
}
