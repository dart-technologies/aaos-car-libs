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
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSpeechRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The platform binding under Robolectric: {@link ShadowSpeechRecognizer} delivers {@code onResults}
 * with a populated {@link Bundle}, which the stub android.jar cannot. Real speech input remains
 * untested; that needs audio hardware.
 */
@RunWith(RobolectricTestRunner.class)
public class SpeechRecognizerBindingTest {

    private SpeechRecognizerBinding mBinding;
    private RecordingEvents mEvents;

    @Before
    public void setUp() {
        mBinding = new SpeechRecognizerBinding(RuntimeEnvironment.getApplication());
        mEvents = new RecordingEvents();
    }

    @After
    public void tearDown() {
        ShadowSpeechRecognizer.reset();
    }

    // Results the device could not produce.

    @Test
    public void aTranscriptFromThePlatformArrivesAsAPhrase() {
        mBinding.start(false, mEvents);

        shadow().triggerOnResults(resultsBundle("play the album", "play the outcome"));

        assertThat(mEvents.mAlternatives).containsExactly("play the album", "play the outcome")
                .inOrder();
        assertThat(mEvents.mErrors).isEmpty();
    }

    /** A result bundle without the recognition extra carries no alternatives (FAILED upstream). */
    @Test
    public void aResultBundleWithoutTheExtraCarriesNoAlternatives() {
        mBinding.start(false, mEvents);

        shadow().triggerOnResults(new Bundle());

        assertThat(mEvents.mAlternatives).isNull();
        assertThat(mEvents.mPhraseCalls).isEqualTo(1);
    }

    @Test
    public void anErrorFromThePlatformArrivesAsItsCode() {
        mBinding.start(false, mEvents);

        shadow().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);

        assertThat(mEvents.mErrors).containsExactly(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        assertThat(mEvents.mPhraseCalls).isEqualTo(0);
    }

    /** Partial results are dropped: acting on one would act on an unfinished phrase. */
    @Test
    public void partialResultsAreIgnored() {
        mBinding.start(false, mEvents);

        shadow().triggerOnPartialResults(resultsBundle("play the alb"));

        assertThat(mEvents.mPhraseCalls).isEqualTo(0);
        assertThat(mEvents.mErrors).isEmpty();

        // Positive control: the shadow entry point does reach the listener.
        shadow().triggerOnResults(resultsBundle("play the album"));
        assertThat(mEvents.mAlternatives).containsExactly("play the album");
    }

    // What the recogniser is actually asked for.

    @Test
    public void theRecognizerIntentAsksForOneResultAndPrefersOffline() {
        mBinding.start(false, mEvents);

        Intent intent = shadow().getLastRecognizerIntent();
        assertThat(intent.getAction()).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
                .isEqualTo(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        assertThat(intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1)).isEqualTo(1);
        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)).isTrue();
    }

    // One recogniser at a time.

    /** The lifetime property that {@code PlatformTranscriptSource.cancel} exists to trigger. */
    @Test
    public void cancelDestroysTheRecogniser() {
        mBinding.start(false, mEvents);
        SpeechRecognizer created = ShadowSpeechRecognizer.getLatestSpeechRecognizer();

        mBinding.cancel();

        assertThat(shadowOf(created).isDestroyed()).isTrue();
    }

    @Test
    public void startingAgainReleasesThePreviousRecogniser() {
        mBinding.start(false, mEvents);
        SpeechRecognizer first = ShadowSpeechRecognizer.getLatestSpeechRecognizer();

        mBinding.start(false, new RecordingEvents());

        // Only the destroy discriminates.
        assertThat(shadowOf(first).isDestroyed()).isTrue();
    }

    @Test
    public void cancelWithNothingStartedTouchesNoRecogniser() {
        mBinding.cancel();
        mBinding.cancel();

        assertThat(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isNull();
    }

    /** And a second cancel after a real one must not resurrect or re-destroy anything. */
    @Test
    public void cancellingTwiceLeavesTheRecogniserDestroyedAndReleased() {
        mBinding.start(false, mEvents);
        SpeechRecognizer created = ShadowSpeechRecognizer.getLatestSpeechRecognizer();

        mBinding.cancel();
        mBinding.cancel();

        assertThat(shadowOf(created).isDestroyed()).isTrue();
    }

    // The platform facts.

    /**
     * The API 33 guard, at sdk 30: without it the query is a NoSuchMethodError on API 30-32, which
     * the default SDK cannot see.
     */
    @Test
    @Config(sdk = 30)
    public void belowApi33OnDeviceRecognitionIsReportedUnavailable_whateverThePlatformWouldSay() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);

        assertThat(new SpeechRecognizerBinding(RuntimeEnvironment.getApplication())
                .isOnDeviceAvailable()).isFalse();
    }

    /**
     * The on-device branch of {@code start} runs and delivers. Which factory was called is not
     * observable through the shadow.
     */
    @Test
    public void theOnDeviceBranchStartsAndDelivers() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);

        mBinding.start(true, mEvents);
        shadow().triggerOnResults(resultsBundle("play"));

        assertThat(mEvents.mAlternatives).containsExactly("play");
    }

    @Test
    public void onDeviceAvailabilityIsWhateverThePlatformSays() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);
        assertThat(mBinding.isOnDeviceAvailable()).isTrue();

        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false);
        assertThat(mBinding.isOnDeviceAvailable()).isFalse();
    }

    @Test
    public void theRecordAudioGrantIsReadFromThePlatform() {
        assertThat(mBinding.isRecordAudioGranted()).isFalse();

        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.RECORD_AUDIO);

        assertThat(mBinding.isRecordAudioGranted()).isTrue();
    }

    /** Starting off the main thread is refused, rather than failing later as a recognition error. */
    @Test
    public void startingOffTheMainThreadIsRefused() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            thrown.set(assertThrows(IllegalStateException.class,
                    () -> mBinding.start(false, mEvents)));
        });
        other.start();
        other.join();

        assertThat(thrown.get()).hasMessageThat().contains("main thread");
    }

    // ------------------------------------------------------------------------------------

    private static ShadowSpeechRecognizer shadow() {
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());
    }

    private static Bundle resultsBundle(String... alternatives) {
        ArrayList<String> list = new ArrayList<>();
        for (String a : alternatives) {
            list.add(a);
        }
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
        return bundle;
    }

    private static final class RecordingEvents implements PlatformTranscriptSource.Events {
        int mPhraseCalls;
        @Nullable List<String> mAlternatives;
        final List<Integer> mErrors = new ArrayList<>();

        @Override
        public void onPhrase(@Nullable List<String> alternatives) {
            mPhraseCalls++;
            mAlternatives = alternatives;
        }

        @Override
        public void onError(int speechRecognizerErrorCode) {
            mErrors.add(speechRecognizerErrorCode);
        }
    }
}
