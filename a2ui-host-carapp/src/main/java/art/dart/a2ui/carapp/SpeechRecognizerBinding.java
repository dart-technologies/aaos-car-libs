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

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * The {@code android.speech} calls and nothing else: platform types in,
 * {@link PlatformTranscriptSource.Events} out, one recogniser alive at a time. Decisions live in
 * {@link PlatformTranscriptSource}; {@code SpeechRecognizerBindingTest} drives this under
 * Robolectric, so only real speech input is unexercised.
 *
 * <p>Main thread only: {@link #start} throws elsewhere rather than fail later as a recognition
 * error.
 */
public final class SpeechRecognizerBinding implements PlatformTranscriptSource.Recognition {

    private final Context mContext;

    /** The live recogniser, or null. At most one, released before the next starts. */
    @Nullable
    private SpeechRecognizer mRecognizer;

    public SpeechRecognizerBinding(@NonNull Context context) {
        // Application context: this outlives any screen, and holding an Activity here would tie a
        // recogniser's lifetime to a view that the templates host owns and can recreate.
        mContext = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    @Override
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(mContext);
    }

    /**
     * Whether recognition runs without a network. The query needs API 33 (minSdk is 30); below it,
     * false falls back to the general recogniser, as every measured image does anyway.
     */
    @Override
    public boolean isOnDeviceAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(mContext);
    }

    @Override
    public boolean isRecordAudioGranted() {
        return mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    @SuppressWarnings("ExecutorRegistration")
    public void start(boolean onDevice, @NonNull PlatformTranscriptSource.Events events) {
        Objects.requireNonNull(events, "events");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "SpeechRecognizer must be created and driven on the main thread");
        }

        // Release first. Two live recognisers would open the microphone twice for one intent, and
        // the platform reports that as ERROR_RECOGNIZER_BUSY on whichever loses.
        cancel();

        // The API 33 guard is repeated so the version check is visible here, not across a seam.
        mRecognizer = onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(mContext)
                : SpeechRecognizer.createSpeechRecognizer(mContext);
        mRecognizer.setRecognitionListener(new Bridge(events));

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Only one alternative is used.
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // A hint no measured image honours, but on one with an on-device model it keeps
                // cabin audio in the car.
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        mRecognizer.startListening(intent);
    }

    /**
     * Releases the recogniser, posting to the main thread from elsewhere: unlike start(), cancel()
     * is reached from the restriction listener, which runs on a binder thread.
     */
    @Override
    public void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(this::cancel);
            return;
        }
        if (mRecognizer == null) {
            return;
        }
        SpeechRecognizer recognizer = mRecognizer;
        // Cleared BEFORE destroy(), because destroy() makes the platform emit ERROR_CLIENT to the
        // listener, which re-enters this object.
        mRecognizer = null;
        recognizer.cancel();
        recognizer.destroy();
    }

    /** {@link RecognitionListener} to {@link PlatformTranscriptSource.Events}. */
    private static final class Bridge implements RecognitionListener {

        private final PlatformTranscriptSource.Events mEvents;

        Bridge(PlatformTranscriptSource.Events events) {
            mEvents = events;
        }

        @Override
        public void onResults(Bundle results) {
            // The Bundle is read here, so everything downstream takes a List.
            mEvents.onPhrase(results == null
                    ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        }

        @Override
        public void onError(int error) {
            mEvents.onError(error);
        }

        // Progress callbacks, not outcomes; only onResults and onError end a listen.

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        /**
         * Ignored: a partial result is a guess, and acting on it would act on an unfinished phrase.
         */
        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }
}
