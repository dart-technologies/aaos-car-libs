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

import java.util.List;
import java.util.Objects;

/**
 * A {@link TranscriptSource} backed by the platform recogniser behind a seam:
 * {@link SpeechRecognizerBinding} makes the {@code android.speech} calls, and this class makes every
 * decision, testable on the JVM. This file imports nothing from {@code android.*}.
 *
 * <p>No measured AAOS image offers on-device recognition (docs/ARCHITECTURE.md), so a false
 * {@link Recognition#isOnDeviceAvailable} falls back to the general recogniser. Single-threaded:
 * {@code SpeechRecognizer} lives on the main thread.
 */
public final class PlatformTranscriptSource implements TranscriptSource {

    /** The platform facts this class needs, and the one platform call it makes. */
    public interface Recognition {

        /** Whether any {@code RecognitionService} is installed and selected. */
        boolean isAvailable();

        /** Whether recognition can run without a network; every measured image says no. */
        boolean isOnDeviceAvailable();

        /** Whether {@code RECORD_AUDIO} has been granted to this app. */
        boolean isRecordAudioGranted();

        /**
         * Starts listening, reporting to {@code events}, after releasing any previous recogniser.
         *
         * @param onDevice ask the on-device recogniser rather than the general one.
         */
        @SuppressWarnings("ExecutorRegistration")
        void start(boolean onDevice, @NonNull Events events);

        /** Stops and releases the recogniser. Must be safe when nothing is listening. */
        void cancel();
    }

    /**
     * What a recogniser reports back. Not {@code RecognitionListener}: its Bundle cannot be
     * populated in a JVM test, so {@link SpeechRecognizerBinding} reads it.
     */
    public interface Events {

        /**
         * The recogniser produced a result.
         *
         * @param alternatives best guess first; null or empty means it produced none, which is a
         *     different thing from producing an empty utterance.
         */
        void onPhrase(@Nullable List<String> alternatives);

        /** The recogniser failed. The code is one of {@code SpeechRecognizer.ERROR_*}. */
        void onError(int speechRecognizerErrorCode);
    }

    private final Recognition mRecognition;

    /** The callback owed an outcome: non-null exactly between a started listen and its outcome. */
    @Nullable
    private Callback mPending;

    /**
     * The {@link Events} of the current listen; anything else is a ghost. Destroying a recogniser
     * makes the platform send ERROR_CLIENT to it after the next listen has started.
     */
    @Nullable
    private Events mActive;

    public PlatformTranscriptSource(@NonNull Recognition recognition) {
        mRecognition = Objects.requireNonNull(recognition, "recognition");
    }

    /**
     * Listens once. A missing service is reported before a missing permission, since granting it
     * would not help. ExecutorRegistration is suppressed as on {@link TranscriptSource#listen}.
     */
    @Override
    @SuppressWarnings("ExecutorRegistration")
    public void listen(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "callback");

        // Refused here: the platform's ERROR_RECOGNIZER_BUSY arrives after the mic is open.
        if (mPending != null) {
            callback.onUnavailable(Unavailable.CANCELLED, "already listening");
            return;
        }
        if (!mRecognition.isAvailable()) {
            callback.onUnavailable(Unavailable.NO_RECOGNITION_SERVICE, null);
            return;
        }
        if (!mRecognition.isRecordAudioGranted()) {
            callback.onUnavailable(Unavailable.PERMISSION_DENIED, null);
            return;
        }

        mPending = callback;
        Events events = new Events() {
            @Override
            public void onPhrase(@Nullable List<String> alternatives) {
                Callback owed = settle(this);
                if (owed == null) {
                    return;
                }
                // [""] is an empty utterance, a phrase; no result at all is a failure.
                if (alternatives == null || alternatives.isEmpty() || alternatives.get(0) == null) {
                    owed.onUnavailable(Unavailable.FAILED, "recogniser returned no result");
                    return;
                }
                owed.onTranscript(alternatives.get(0));
            }

            @Override
            public void onError(int speechRecognizerErrorCode) {
                Callback owed = settle(this);
                if (owed == null) {
                    return;
                }
                owed.onUnavailable(unavailableFor(speechRecognizerErrorCode),
                        describeError(speechRecognizerErrorCode));
            }
        };
        // Installed BEFORE start(), because start() releases the previous recogniser and the
        // resulting ERROR_CLIENT arrives synchronously from inside that call.
        mActive = events;
        try {
            mRecognition.start(mRecognition.isOnDeviceAvailable(), events);
        } catch (RuntimeException e) {
            // A throw would otherwise deliver no outcome and leave mPending set forever.
            Callback owed = settle(events);
            if (owed == null) {
                // Nothing owed: the exception came from the callback chain, so rethrow.
                throw e;
            }
            owed.onUnavailable(Unavailable.FAILED, "recogniser did not start: " + e);
        }
    }

    /**
     * Stops listening and abandons the outcome. Always reaches the recogniser, which stays
     * allocated after a successful recognition.
     */
    @Override
    public void cancel() {
        mPending = null;
        mActive = null;
        mRecognition.cancel();
    }

    /**
     * Claims the outcome for {@code generation}, or null if it is not owed one: already delivered,
     * cancelled, or from a replaced recogniser.
     */
    @Nullable
    private Callback settle(@NonNull Events generation) {
        if (generation != mActive) {
            return null;
        }
        mActive = null;
        Callback owed = mPending;
        mPending = null;
        return owed;
    }

    /**
     * Maps a {@code SpeechRecognizer.ERROR_*} code onto the reasons a host can act on: only
     * ERROR_INSUFFICIENT_PERMISSIONS is not FAILED (detail survives in {@link #describeError}).
     * ERROR_CLIENT is not CANCELLED, since cancel() takes the outcome first.
     */
    static Unavailable unavailableFor(int speechRecognizerErrorCode) {
        if (speechRecognizerErrorCode == ERROR_INSUFFICIENT_PERMISSIONS) {
            return Unavailable.PERMISSION_DENIED;
        }
        return Unavailable.FAILED;
    }

    /** A short human name for an error code; it may be shown to a driver. */
    @NonNull
    static String describeError(int speechRecognizerErrorCode) {
        final String name;
        switch (speechRecognizerErrorCode) {
            case 1: name = "network timeout"; break;
            case 2: name = "network error"; break;
            case 3: name = "audio error"; break;
            case 4: name = "server error"; break;
            case 5: name = "client error"; break;
            case 6: name = "no speech heard"; break;
            case 7: name = "no match"; break;
            case 8: name = "recogniser busy"; break;
            case ERROR_INSUFFICIENT_PERMISSIONS: name = "microphone permission not granted"; break;
            case 10: name = "too many requests"; break;
            case 11: name = "server disconnected"; break;
            case 12: name = "language not supported"; break;
            case 13: name = "language unavailable"; break;
            case 14: name = "cannot check support"; break;
            case 15: name = "cannot listen to download events"; break;
            default: name = "unknown error"; break;
        }
        return name + " (" + speechRecognizerErrorCode + ")";
    }

    /**
     * {@code SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS}, restated to keep this file free of
     * {@code android.*} imports; the test pins it to the SDK's value.
     */
    private static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;
}
