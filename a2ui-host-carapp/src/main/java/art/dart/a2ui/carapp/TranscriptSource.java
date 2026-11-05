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

/**
 * Turns speech into a phrase, or says why it cannot. The seam keeps the one step that needs a
 * microphone apart from the testable logic in {@link A2uiVoiceCapture}; a vendor recogniser can
 * replace the platform one ({@link PlatformTranscriptSource}).
 */
public interface TranscriptSource {

    /** Why a phrase could not be produced. Never "it failed"; always which way. */
    // metalava api-lint: Enum, suppressed: a closed five-member set that is already public API.
    @SuppressWarnings("Enum")
    enum Unavailable {
        /**
         * No {@code RecognitionService} is installed: a normal answer on some images, not an error.
         */
        NO_RECOGNITION_SERVICE,
        /** {@code RECORD_AUDIO} was not granted. */
        PERMISSION_DENIED,
        /** Driving restrictions currently forbid it. */
        RESTRICTED,
        /** The user or the host stopped it. */
        CANCELLED,
        /** The recogniser was reached and returned an error. */
        FAILED
    }

    /** Receives exactly one outcome per {@link #listen} call. */
    interface Callback {
        /** A phrase was heard. May be empty; the caller decides what empty means. */
        void onTranscript(@NonNull String spoken);

        /** No phrase, and this is why. */
        void onUnavailable(@NonNull Unavailable reason, @Nullable String detail);
    }

    /**
     * Begins listening. Exactly one outcome per call, unless {@link #cancel} takes it first, then
     * none: a destroyed recogniser's ERROR_CLIENT must not report a failure the host caused.
     *
     * <p>The callback fires on the implementation's thread; callers post as needed. No
     * {@code listen(Executor, Callback)} overload (metalava ExecutorRegistration): one caller.
     */
    @SuppressWarnings("ExecutorRegistration")
    void listen(@NonNull Callback callback);

    /**
     * Stops listening and abandons the outcome. Safe when not listening, and also releases a
     * finished recogniser.
     */
    void cancel();
}
