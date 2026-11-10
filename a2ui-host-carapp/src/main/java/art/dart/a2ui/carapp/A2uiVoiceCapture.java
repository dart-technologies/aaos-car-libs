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
package art.dart.a2ui.carapp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiMessageProcessor.VoiceOutcome;
import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Host A's voice path: listen, then dispatch the phrase against what is on screen.
 *
 * <p>The rendered tree is a {@link Supplier} read when the phrase arrives, not at listen time, so a
 * phrase cannot reach a control pruned while the driver was speaking. Each listen delivers exactly
 * one outcome even from a misbehaving {@link TranscriptSource}, because {@code dispatchByVoice}
 * performs the action it resolves. Exercised by {@code A2uiVoiceCaptureTest}; producing a real
 * transcript needs a microphone.
 */
public final class A2uiVoiceCapture {

    /** Whether voice input is permitted right now, independent of whether it is possible. */
    public interface Gate {
        /**
         * False when the host's policy (driving restrictions above all) forbids listening. Policy
         * only; capability belongs to the {@link TranscriptSource}.
         */
        boolean allowsListening();

        /** Why it is not allowed. Only consulted when {@link #allowsListening} is false. */
        @NonNull
        TranscriptSource.Unavailable reason();
    }

    /** What the host is told when a listen completes. */
    public interface Listener {
        /** A phrase was heard and put through the protocol's voice dispatch. */
        void onDispatched(@NonNull String spoken, @NonNull VoiceOutcome outcome);

        /** No phrase. The host should say so rather than appear to be listening. */
        void onUnavailable(@NonNull TranscriptSource.Unavailable reason, @Nullable String detail);
    }

    private final A2uiMessageProcessor mProcessor;
    private final TranscriptSource mSource;
    private final Gate mGate;

    /**
     * The outstanding listen, or null: one attempt, held as its own outcome latch. Atomic because
     * starting a listen and delivering its outcome are both read-then-write steps, and the source
     * completes on a thread of its choosing. Not a lock, which would be held across host code.
     */
    private final AtomicReference<AtomicBoolean> mOutstanding = new AtomicReference<>();

    public A2uiVoiceCapture(@NonNull A2uiMessageProcessor processor,
            @NonNull TranscriptSource source, @NonNull Gate gate) {
        mProcessor = Objects.requireNonNull(processor, "processor");
        mSource = Objects.requireNonNull(source, "source");
        mGate = Objects.requireNonNull(gate, "gate");
    }

    /** True between a successful {@link #listen} and its outcome. */
    public boolean isListening() {
        return mOutstanding.get() != null;
    }

    /**
     * Listens once, then dispatches whatever is heard against the surface as rendered then. At
     * most one listen is outstanding and each yields exactly one outcome; the listener runs on the
     * source's thread, so main-thread work must post.
     *
     * <p>metalava api-lint: ExecutorRegistration (one implementation, one caller) and
     * SamShouldBeLast (both trailing parameters are SAM types) are suppressed.
     *
     * @param renderedRoot read when the phrase arrives, not now
     */
    @SuppressWarnings({"ExecutorRegistration", "SamShouldBeLast"})
    public void listen(@NonNull String surfaceId,
            @NonNull Supplier<A2uiComponent> renderedRoot, @NonNull Listener listener) {
        Objects.requireNonNull(surfaceId, "surfaceId");
        Objects.requireNonNull(renderedRoot, "renderedRoot");
        Objects.requireNonNull(listener, "listener");

        // The gate is asked before the microphone opens; afterwards would already have recorded.
        // One decision event per listen(): the mid-listen re-check below reuses the outcome
        // listener rather than emitting a second decision.
        if (!mGate.allowsListening()) {
            TranscriptSource.Unavailable reason = mGate.reason();
            A2uiTelemetry.event(TelemetryEvent.VOICE_DECISION, "outcome", "refused", "reason",
                    reason.name());
            listener.onUnavailable(reason, null);
            return;
        }
        A2uiTelemetry.event(TelemetryEvent.VOICE_DECISION, "outcome", "allowed", "reason", "none");

        // Created before the guard, because installing it is the guard.
        AtomicBoolean attempt = new AtomicBoolean(false);
        TranscriptSource.Callback callback = new TranscriptSource.Callback() {
            @Override
            public void onTranscript(@NonNull String spoken) {
                // Claimed before the phrase is used: a duplicate reaching dispatchByVoice would
                // invoke the control twice for one utterance, and the listener never
                // hearing about it would make that quieter, not less true.
                if (!claim(attempt)) {
                    return;
                }
                // Asked again: the reading that authorised the listen may have changed by the time
                // the transcript arrives.
                if (!mGate.allowsListening()) {
                    listener.onUnavailable(mGate.reason(), null);
                    return;
                }
                VoiceOutcome outcome =
                        mProcessor.dispatchByVoice(surfaceId, spoken, renderedRoot.get());
                listener.onDispatched(spoken, outcome);
            }

            @Override
            public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                    @Nullable String detail) {
                if (!claim(attempt)) {
                    return;
                }
                listener.onUnavailable(reason, detail);
            }
        };

        // A second listen while one is outstanding is refused, in one compare-and-set: two live
        // recognisers would yield two phrases for one intent.
        if (!mOutstanding.compareAndSet(null, attempt)) {
            listener.onUnavailable(TranscriptSource.Unavailable.CANCELLED, "already listening");
            return;
        }

        try {
            mSource.listen(callback);
        } catch (RuntimeException e) {
            // The attempt is already installed, so a throwing source must not wedge it. A refused
            // claim means the outcome was delivered and this came from the listener chain:
            // rethrow. A granted claim takes the outcome, so a late completion cannot add a second.
            // The claim is by identity, so it cannot end a different listen.
            if (!claim(attempt)) {
                throw e;
            }
            listener.onUnavailable(TranscriptSource.Unavailable.FAILED,
                    "transcript source threw: " + e);
        }
    }

    /**
     * Claims {@code attempt}'s one outcome, or false if it was already delivered (not cancelled).
     * Clears the slot only if it still holds this attempt: a new listen may already own it.
     */
    private boolean claim(@NonNull AtomicBoolean attempt) {
        if (!attempt.compareAndSet(false, true)) {
            return false;
        }
        mOutstanding.compareAndSet(attempt, null);
        return true;
    }

    /**
     * Stops listening and always reaches the source, which releases the recogniser even after a
     * completed recognition.
     */
    public void cancel() {
        // Ends the listen without latching the attempt, so a source that reports anyway is still
        // delivered once; A2uiShowcaseScreenCaseTest relies on that to catch a worse bug.
        mOutstanding.set(null);
        mSource.cancel();
    }
}
