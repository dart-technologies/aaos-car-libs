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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.carapp.TranscriptSource.Callback;
import art.dart.a2ui.carapp.TranscriptSource.Unavailable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the platform recogniser path decides, tested without a platform. SpeechRecognizer.ERROR_*
 * are compile-time constants inlined by javac, so no stub method is called.
 */
@RunWith(JUnit4.class)
public class PlatformTranscriptSourceTest {

    // Refusals, before the microphone opens.

    @Test
    public void noRecognitionService_isReportedWithoutOpeningTheMicrophone() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mAvailable = false;
        RecordingCallback callback = new RecordingCallback();

        new PlatformTranscriptSource(recognition).listen(callback);

        assertThat(callback.mReasons).containsExactly(Unavailable.NO_RECOGNITION_SERVICE);
        assertThat(recognition.mStartCalls).isEqualTo(0);
    }

    @Test
    public void aDeniedPermission_isReportedWithoutOpeningTheMicrophone() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mRecordAudioGranted = false;
        RecordingCallback callback = new RecordingCallback();

        new PlatformTranscriptSource(recognition).listen(callback);

        assertThat(callback.mReasons).containsExactly(Unavailable.PERMISSION_DENIED);
        assertThat(recognition.mStartCalls).isEqualTo(0);
    }

    /** With both missing, report the one a permission grant cannot fix. */
    @Test
    public void whenNeitherIsPresent_theMissingServiceIsReportedAheadOfTheMissingPermission() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mAvailable = false;
        recognition.mRecordAudioGranted = false;
        RecordingCallback callback = new RecordingCallback();

        new PlatformTranscriptSource(recognition).listen(callback);

        assertThat(callback.mReasons).containsExactly(Unavailable.NO_RECOGNITION_SERVICE);
    }

    // Which recogniser is asked.

    @Test
    public void theOnDeviceRecogniserIsPreferredWhenItIsAvailable() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mOnDeviceAvailable = true;

        new PlatformTranscriptSource(recognition).listen(new RecordingCallback());

        assertThat(recognition.mStartedOnDevice).isTrue();
    }

    /** No measured AAOS image offers on-device recognition, so this branch must still listen. */
    @Test
    public void withoutOnDeviceRecognitionItStillListens_usingTheGeneralRecogniser() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mOnDeviceAvailable = false;

        new PlatformTranscriptSource(recognition).listen(new RecordingCallback());

        assertThat(recognition.mStartCalls).isEqualTo(1);
        assertThat(recognition.mStartedOnDevice).isFalse();
    }

    // Results.

    @Test
    public void theFirstAlternativeIsTheTranscript() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        new PlatformTranscriptSource(recognition).listen(callback);

        recognition.report(Arrays.asList("play the album", "play the outcome"));

        assertThat(callback.mTranscripts).containsExactly("play the album");
        assertThat(callback.mReasons).isEmpty();
    }

    /** An empty utterance is a transcript, because TranscriptSource says the caller decides. */
    @Test
    public void anEmptyAlternativeIsATranscript_notAFailure() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        new PlatformTranscriptSource(recognition).listen(callback);

        recognition.report(Arrays.asList(""));

        assertThat(callback.mTranscripts).containsExactly("");
        assertThat(callback.mReasons).isEmpty();
    }

    /** No alternatives at all is a different thing: the recogniser did not answer. */
    @Test
    public void noAlternativesAtAllIsAFailure() {
        for (List<String> nothing : Arrays.asList(null, new ArrayList<String>(),
                Arrays.asList((String) null))) {
            FakeRecognition recognition = new FakeRecognition();
            RecordingCallback callback = new RecordingCallback();
            new PlatformTranscriptSource(recognition).listen(callback);

            recognition.report(nothing);

            assertThat(callback.mTranscripts).isEmpty();
            assertThat(callback.mReasons).containsExactly(Unavailable.FAILED);
        }
    }

    // Error mapping.

    @Test
    public void insufficientPermissionsFromTheRecogniserIsPERMISSION_DENIED() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        new PlatformTranscriptSource(recognition).listen(callback);

        recognition.fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);

        assertThat(callback.mReasons).containsExactly(Unavailable.PERMISSION_DENIED);
    }

    /**
     * Every other documented code is FAILED, including ERROR_CLIENT; a genuine cancel takes the
     * outcome first. The loop asserts it covered all fifteen codes.
     */
    @Test
    public void everyOtherDocumentedErrorCodeIsFAILED() {
        Set<Integer> covered = new LinkedHashSet<>();
        for (int code : DOCUMENTED_ERROR_CODES) {
            covered.add(code);
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                continue;
            }
            FakeRecognition recognition = new FakeRecognition();
            RecordingCallback callback = new RecordingCallback();
            new PlatformTranscriptSource(recognition).listen(callback);

            recognition.fail(code);

            assertThat(callback.mReasons).containsExactly(Unavailable.FAILED);
        }
        assertThat(covered).hasSize(15);
        assertThat(covered).contains(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
    }

    /** An unknown code — a future SDK's — must still produce an outcome rather than an exception. */
    @Test
    public void anUndocumentedErrorCodeIsStillReported() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        new PlatformTranscriptSource(recognition).listen(callback);

        recognition.fail(4242);

        assertThat(callback.mReasons).containsExactly(Unavailable.FAILED);
        assertThat(callback.mDetails).containsExactly("unknown error (4242)");
    }

    /** The detail is what carries the distinction the five-member enum deliberately does not. */
    @Test
    public void theDetailNamesTheErrorAndKeepsItsCode() {
        assertThat(PlatformTranscriptSource.describeError(SpeechRecognizer.ERROR_NO_MATCH))
                .isEqualTo("no match (7)");
        assertThat(PlatformTranscriptSource.describeError(SpeechRecognizer.ERROR_NETWORK))
                .isEqualTo("network error (2)");
    }

    /** The restated platform constant matches the SDK. */
    @Test
    public void theRestatedPermissionErrorCodeMatchesTheSdk() {
        assertThat(PlatformTranscriptSource.unavailableFor(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
                .isEqualTo(Unavailable.PERMISSION_DENIED);
        assertThat(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).isEqualTo(9);
    }

    // Exactly one outcome per listen.

    @Test
    public void aSecondReportAfterAnOutcomeReachesNobody() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        new PlatformTranscriptSource(recognition).listen(callback);

        recognition.report(Arrays.asList("play"));
        recognition.fail(SpeechRecognizer.ERROR_CLIENT);
        recognition.report(Arrays.asList("pause"));

        assertThat(callback.mTranscripts).containsExactly("play");
        assertThat(callback.mReasons).isEmpty();
    }

    /** ERROR_CLIENT after a cancel must not tell the host recognition failed. */
    @Test
    public void anErrorArrivingAfterCancelReachesNobody() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback callback = new RecordingCallback();
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);
        source.listen(callback);

        source.cancel();
        recognition.fail(SpeechRecognizer.ERROR_CLIENT);

        assertThat(callback.mReasons).isEmpty();
        assertThat(callback.mTranscripts).isEmpty();
    }

    @Test
    public void aSecondListenWhileOneIsOutstandingIsRefusedAndStartsNoSecondRecogniser() {
        FakeRecognition recognition = new FakeRecognition();
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);

        source.listen(first);
        source.listen(second);

        assertThat(second.mReasons).containsExactly(Unavailable.CANCELLED);
        assertThat(second.mDetails).containsExactly("already listening");
        assertThat(recognition.mStartCalls).isEqualTo(1);
        // The first is still owed its outcome -- refusing the second must not settle the first.
        recognition.report(Arrays.asList("play"));
        assertThat(first.mTranscripts).containsExactly("play");
    }

    @Test
    public void afterAnOutcomeTheNextListenStartsNormally() {
        FakeRecognition recognition = new FakeRecognition();
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);
        source.listen(new RecordingCallback());
        recognition.report(Arrays.asList("play"));

        RecordingCallback second = new RecordingCallback();
        source.listen(second);
        recognition.report(Arrays.asList("pause"));

        assertThat(recognition.mStartCalls).isEqualTo(2);
        assertThat(second.mTranscripts).containsExactly("pause");
    }

    // Cancel.

    /** Cancel always reaches the recogniser, releasing a finished one. */
    @Test
    public void cancelReachesTheRecogniserEvenWithNoListenOutstanding() {
        FakeRecognition recognition = new FakeRecognition();
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);

        source.cancel();
        source.listen(new RecordingCallback());
        recognition.report(Arrays.asList("play"));
        source.cancel();

        assertThat(recognition.mCancelCalls).isEqualTo(2);
    }

    @Test
    public void aNullRecognitionIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new PlatformTranscriptSource(null));
    }

    // ---------------------------------------------------------------------------------------

    // Generations: a callback from a recogniser that has already been replaced.

    /**
     * The ERROR_CLIENT a destroyed recogniser emits, synchronously from inside the next start,
     * must not settle the new listen.
     */
    @Test
    public void anErrorFromTheRecogniserThatWasJustReplacedSettlesNothing() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mEmitClientErrorOnRestart = true;
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);

        RecordingCallback first = new RecordingCallback();
        source.listen(first);
        recognition.fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);

        RecordingCallback second = new RecordingCallback();
        source.listen(second);

        // The ghost error fired during that listen. It must not have settled the new callback.
        assertThat(second.mReasons).isEmpty();
        assertThat(second.mTranscripts).isEmpty();

        recognition.report(Arrays.asList("play the album"));

        assertThat(second.mTranscripts).containsExactly("play the album");
    }

    /** And the ghost must not consume a later generation's outcome either. */
    @Test
    public void aGhostErrorDoesNotStopTheThirdListenEither() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mEmitClientErrorOnRestart = true;
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);
        source.listen(new RecordingCallback());
        recognition.fail(SpeechRecognizer.ERROR_NO_MATCH);
        source.listen(new RecordingCallback());
        recognition.fail(SpeechRecognizer.ERROR_NO_MATCH);

        RecordingCallback third = new RecordingCallback();
        source.listen(third);
        recognition.report(Arrays.asList("pause"));

        assertThat(third.mTranscripts).containsExactly("pause");
    }

    // A recogniser that cannot be started.

    /** A throw out of start() still produces one outcome, and the next listen works. */
    @Test
    public void aRecogniserThatWillNotStartIsAFailedListen_andTheNextListenStillWorks() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mStartThrows = new IllegalStateException("wrong thread");
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);

        RecordingCallback failed = new RecordingCallback();
        source.listen(failed);

        assertThat(failed.mReasons).containsExactly(Unavailable.FAILED);

        recognition.mStartThrows = null;
        RecordingCallback next = new RecordingCallback();
        source.listen(next);
        recognition.report(Arrays.asList("play"));

        assertThat(next.mTranscripts).containsExactly("play");
    }

    /**
     * A crash in the callback propagates rather than being swallowed by the guard around start().
     */
    @Test
    public void anExceptionFromTheCallbackPropagatesRatherThanBeingSwallowed() {
        FakeRecognition recognition = new FakeRecognition();
        recognition.mDeliverDuringStart = Arrays.asList("play the album");
        PlatformTranscriptSource source = new PlatformTranscriptSource(recognition);

        assertThrows(IllegalStateException.class, () -> source.listen(new Callback() {
            @Override
            public void onTranscript(@NonNull String spoken) {
                throw new IllegalStateException("dispatch blew up");
            }

            @Override
            public void onUnavailable(@NonNull Unavailable reason, @Nullable String detail) {
            }
        }));
    }

    /** Every documented SpeechRecognizer error code, newest last. */
    private static final int[] DOCUMENTED_ERROR_CODES = {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
    };

    /** Stands in for the device. Defaults are the happy path so each test states its own one fact. */
    private static final class FakeRecognition implements PlatformTranscriptSource.Recognition {
        boolean mAvailable = true;
        boolean mOnDeviceAvailable = false;
        boolean mRecordAudioGranted = true;

        int mStartCalls;
        int mCancelCalls;
        boolean mStartedOnDevice;
        @Nullable PlatformTranscriptSource.Events mEvents;

        @Override
        public boolean isAvailable() {
            return mAvailable;
        }

        @Override
        public boolean isOnDeviceAvailable() {
            return mOnDeviceAvailable;
        }

        @Override
        public boolean isRecordAudioGranted() {
            return mRecordAudioGranted;
        }

        /**
         * Emulates the platform's restart: starting releases the previous recogniser, which emits
         * ERROR_CLIENT to its listener.
         */
        boolean mEmitClientErrorOnRestart;
        @Nullable RuntimeException mStartThrows;
        @Nullable List<String> mDeliverDuringStart;

        @Override
        public void start(boolean onDevice, @NonNull PlatformTranscriptSource.Events events) {
            mStartCalls++;
            mStartedOnDevice = onDevice;
            PlatformTranscriptSource.Events previous = mEvents;
            mEvents = events;
            if (mStartThrows != null) {
                throw mStartThrows;
            }
            if (mEmitClientErrorOnRestart && previous != null) {
                previous.onError(SpeechRecognizer.ERROR_CLIENT);
            }
            if (mDeliverDuringStart != null) {
                events.onPhrase(mDeliverDuringStart);
            }
        }

        @Override
        public void cancel() {
            mCancelCalls++;
        }

        void report(@Nullable List<String> alternatives) {
            mEvents.onPhrase(alternatives);
        }

        void fail(int code) {
            mEvents.onError(code);
        }
    }

    private static final class RecordingCallback implements TranscriptSource.Callback {
        final List<String> mTranscripts = new ArrayList<>();
        final List<Unavailable> mReasons = new ArrayList<>();
        final List<String> mDetails = new ArrayList<>();

        @Override
        public void onTranscript(@NonNull String spoken) {
            mTranscripts.add(spoken);
        }

        @Override
        public void onUnavailable(@NonNull Unavailable reason, @Nullable String detail) {
            mReasons.add(reason);
            if (detail != null) {
                mDetails.add(detail);
            }
        }
    }
}
