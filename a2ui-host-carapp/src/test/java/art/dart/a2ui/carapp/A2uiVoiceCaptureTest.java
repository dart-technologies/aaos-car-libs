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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.protocol.A2uiMessageProcessor;
import art.dart.a2ui.protocol.A2uiMessageProcessor.VoiceOutcome;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The voice path, minus the microphone: {@link TranscriptSource} is faked so the test decides when
 * a phrase arrives. Dispatch semantics belong to {@code A2uiMessageProcessorTest}; the processor is
 * mocked here.
 *
 * <p>Several sources below break the "exactly one outcome per call" contract on purpose.
 * {@link PlatformTranscriptSource} does not, but {@link A2uiVoiceCapture} accepts any source.
 */
public class A2uiVoiceCaptureTest {

    private static final String SURFACE = "s1";

    /** A source that throws must not wedge the capture in "already listening". */
    @Test
    public void aThrowingSourceIsReportedAndLeavesTheCaptureUsable() {
        TranscriptSource throwing = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                throw new IllegalStateException("no recogniser");
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mProcessor, throwing,
                new A2uiVoiceCapture.Gate() {
                    @Override
                    public boolean allowsListening() {
                        return true;
                    }

                    @Override
                    @NonNull
                    public TranscriptSource.Unavailable reason() {
                        return TranscriptSource.Unavailable.RESTRICTED;
                    }
                });

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mListener.mUnavailable).containsExactly(TranscriptSource.Unavailable.FAILED);
        assertThat(capture.isListening()).isFalse();
    }

    /** A source that reports and then throws must not produce two outcomes for one utterance. */
    @Test
    public void aSourceThatReportsAndThenThrowsStillProducesOneOutcome() {
        TranscriptSource rude = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                callback.onTranscript("play");
                throw new IllegalStateException("and then this");
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mProcessor, rude,
                new A2uiVoiceCapture.Gate() {
                    @Override
                    public boolean allowsListening() {
                        return true;
                    }

                    @Override
                    @NonNull
                    public TranscriptSource.Unavailable reason() {
                        return TranscriptSource.Unavailable.RESTRICTED;
                    }
                });

        assertThrows(IllegalStateException.class,
                () -> capture.listen(SURFACE, () -> tree("root"), mListener));

        // One outcome, and the throw is not absorbed: PlatformTranscriptSource rethrows on purpose.
        assertThat(mListener.mDispatched).hasSize(1);
        assertThat(mListener.mUnavailable).isEmpty();
        assertThat(capture.isListening()).isFalse();
    }

    /**
     * A source that speaks twice dispatches once: {@code dispatchByVoice} performs the action, so
     * a second report would invoke the control again.
     */
    @Test
    public void aSourceThatSpeaksTwiceDispatchesOnce() {
        A2uiVoiceCapture capture = captureOver(sourceThat(callback -> {
            callback.onTranscript("play");
            callback.onTranscript("play");
        }));

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mListener.mDispatched).containsExactly("play");
        // Asserted on the processor too: de-duplicating only the listener would still dispatch twice.
        verify(mProcessor).dispatchByVoice(eq(SURFACE), eq("play"), any());
        assertThat(capture.isListening()).isFalse();
    }

    /** A phrase followed by a failure delivers the phrase and nothing else. */
    @Test
    public void aSourceThatSpeaksAndThenFailsDeliversOnlyThePhrase() {
        A2uiVoiceCapture capture = captureOver(sourceThat(callback -> {
            callback.onTranscript("play");
            callback.onUnavailable(TranscriptSource.Unavailable.FAILED, "and then it died");
        }));

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mListener.mDispatched).containsExactly("play");
        assertThat(mListener.mUnavailable).isEmpty();
    }

    /**
     * A failure followed by a phrase delivers the failure and nothing else; the platform produces
     * this order when a replaced recogniser emits ERROR_CLIENT.
     */
    @Test
    public void aSourceThatFailsAndThenSpeaksDeliversOnlyTheFailure() {
        A2uiVoiceCapture capture = captureOver(sourceThat(callback -> {
            callback.onUnavailable(TranscriptSource.Unavailable.NO_RECOGNITION_SERVICE, "none");
            callback.onTranscript("play");
        }));

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mListener.mUnavailable)
                .containsExactly(TranscriptSource.Unavailable.NO_RECOGNITION_SERVICE);
        assertThat(mListener.mDispatched).isEmpty();
        verify(mProcessor, never()).dispatchByVoice(any(), any(), any());
    }

    /**
     * A source that throws and then completes late delivers only the failure: reporting FAILED
     * must claim the outcome.
     */
    @Test
    public void aSourceThatThrowsAndThenReportsLateDeliversOnlyTheFailure() {
        TranscriptSource.Callback[] late = new TranscriptSource.Callback[1];
        A2uiVoiceCapture capture = captureOver(sourceThat(callback -> {
            late[0] = callback;
            throw new IllegalStateException("started, then died");
        }));

        capture.listen(SURFACE, () -> tree("root"), mListener);
        assertThat(mListener.mUnavailable).containsExactly(TranscriptSource.Unavailable.FAILED);

        // The listen is over as far as the host is concerned. The source finishes anyway.
        late[0].onTranscript("play");

        assertThat(mListener.mDispatched).isEmpty();
        verify(mProcessor, never()).dispatchByVoice(any(), any(), any());
        assertThat(mListener.mUnavailable).containsExactly(TranscriptSource.Unavailable.FAILED);
        assertThat(capture.isListening()).isFalse();
    }

    /**
     * A source that reports twice after a cancel is delivered exactly once, and the first report
     * still arrives: {@code A2uiShowcaseScreenCaseTest#aLatePhraseIsResolvedAndDispatchedAgainstOneSurface}
     * relies on it. The cancel must not resurrect the listen.
     */
    @Test
    public void aSourceReportingTwiceAfterCancelIsStillDeliveredOnce() {
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);
        capture.listen(SURFACE, () -> tree("root"), mListener);

        capture.cancel();
        mSource.speak("play");
        mSource.speak("play");

        assertThat(mListener.mDispatched).containsExactly("play");
        verify(mProcessor).dispatchByVoice(eq(SURFACE), eq("play"), any());
        assertThat(mListener.mUnavailable).isEmpty();
        assertThat(capture.isListening()).isFalse();
    }

    /**
     * A throw from a listen that already ended must not end the listen the host started inside it.
     * Pins the identity check: an unconditional clear fails only here.
     */
    @Test
    public void aThrowFromAFinishedListenDoesNotEndTheListenStartedInsideIt() {
        int[] listens = {0};
        TranscriptSource.Callback[] rearmed = new TranscriptSource.Callback[1];
        TranscriptSource source = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                if (++listens[0] == 1) {
                    callback.onTranscript("play");
                    throw new IllegalStateException("and then the recogniser died");
                }
                rearmed[0] = callback;
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture[] capture = new A2uiVoiceCapture[1];
        capture[0] = captureOver(source);
        A2uiVoiceCapture.Listener rearming = new A2uiVoiceCapture.Listener() {
            @Override
            public void onDispatched(@NonNull String spoken, @NonNull VoiceOutcome outcome) {
                mListener.onDispatched(spoken, outcome);
                capture[0].listen(SURFACE, () -> tree("root"), mListener);
            }

            @Override
            public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                    @Nullable String detail) {
                mListener.onUnavailable(reason, detail);
            }
        };

        assertThrows(IllegalStateException.class,
                () -> capture[0].listen(SURFACE, () -> tree("root"), rearming));

        assertThat(listens[0]).isEqualTo(2);
        assertThat(capture[0].isListening()).isTrue();

        rearmed[0].onTranscript("stop");

        assertThat(mListener.mDispatched).containsExactly("play", "stop").inOrder();
        assertThat(mListener.mUnavailable).isEmpty();
        assertThat(capture[0].isListening()).isFalse();
    }

    /**
     * A late phrase from a cancelled listen must not end the listen running now. The only path to
     * the identity check in {@code claim}.
     */
    @Test
    public void aLatePhraseFromACancelledListenDoesNotEndTheListenRunningNow() {
        List<TranscriptSource.Callback> callbacks = new ArrayList<>();
        TranscriptSource source = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                callbacks.add(callback);
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture capture = captureOver(source);

        capture.listen(SURFACE, () -> tree("root"), mListener);
        capture.cancel();
        capture.listen(SURFACE, () -> tree("root"), mListener);
        assertThat(callbacks).hasSize(2);

        // Delivered (cancel does not latch), but the slot no longer holds its attempt.
        callbacks.get(0).onTranscript("stale");

        assertThat(capture.isListening()).isTrue();

        // And the listen that IS running still gets to finish.
        callbacks.get(1).onTranscript("current");

        assertThat(mListener.mDispatched).containsExactly("stale", "current").inOrder();
        assertThat(capture.isListening()).isFalse();
    }

    /** A source the test drives by hand: it records the callback and answers on demand. */
    private static final class FakeSource implements TranscriptSource {
        @Nullable Callback mCallback;
        int mListenCalls;
        int mCancelCalls;

        @Override
        public void listen(@NonNull Callback callback) {
            mListenCalls++;
            mCallback = callback;
        }

        @Override
        public void cancel() {
            mCancelCalls++;
        }

        void speak(String phrase) {
            mCallback.onTranscript(phrase);
        }

        void fail(Unavailable reason, String detail) {
            mCallback.onUnavailable(reason, detail);
        }
    }

    private static final class RecordingListener implements A2uiVoiceCapture.Listener {
        final List<String> mDispatched = new ArrayList<>();
        final List<TranscriptSource.Unavailable> mUnavailable = new ArrayList<>();
        @Nullable VoiceOutcome mLastOutcome;
        @Nullable String mLastDetail;

        @Override
        public void onDispatched(@NonNull String spoken, @NonNull VoiceOutcome outcome) {
            mDispatched.add(spoken);
            mLastOutcome = outcome;
        }

        @Override
        public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                @Nullable String detail) {
            mUnavailable.add(reason);
            mLastDetail = detail;
        }
    }

    private A2uiMessageProcessor mProcessor;
    private FakeSource mSource;
    private RecordingListener mListener;

    @Before
    public void setUp() {
        mProcessor = mock(A2uiMessageProcessor.class);
        when(mProcessor.dispatchByVoice(any(), any(), any())).thenReturn(VoiceOutcome.DISPATCHED);
        mSource = new FakeSource();
        mListener = new RecordingListener();
    }

    private A2uiVoiceCapture captureWith(boolean allowed,
            TranscriptSource.Unavailable refusalReason) {
        return new A2uiVoiceCapture(mProcessor, mSource, new A2uiVoiceCapture.Gate() {
            @Override
            public boolean allowsListening() {
                return allowed;
            }

            @Override
            @NonNull
            public TranscriptSource.Unavailable reason() {
                return refusalReason;
            }
        });
    }

    /**
     * A capture over a source the test supplies, with a gate that always allows, for sources that
     * misbehave from inside {@code listen}.
     */
    private A2uiVoiceCapture captureOver(TranscriptSource source) {
        return new A2uiVoiceCapture(mProcessor, source, new A2uiVoiceCapture.Gate() {
            @Override
            public boolean allowsListening() {
                return true;
            }

            @Override
            @NonNull
            public TranscriptSource.Unavailable reason() {
                return TranscriptSource.Unavailable.RESTRICTED;
            }
        });
    }

    /** A source that runs {@code script} against the callback the moment it is asked to listen. */
    private static TranscriptSource sourceThat(Consumer<TranscriptSource.Callback> script) {
        return new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                script.accept(callback);
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static A2uiComponent tree(String id) {
        return new A2uiComponent.Builder(ComponentType.TEXT).setId(id).build();
    }

    /**
     * The rendered tree is read when the phrase arrives, not when listening starts, so a control
     * pruned mid-sentence cannot be reached.
     */
    @Test
    public void theRenderedTreeIsReadWhenThePhraseArrivesNotWhenListeningStarts() {
        A2uiComponent atListenTime = tree("before");
        A2uiComponent atPhraseTime = tree("after");
        A2uiComponent[] current = {atListenTime};

        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);
        capture.listen(SURFACE, () -> current[0], mListener);

        // The screen changes while the user is still speaking.
        current[0] = atPhraseTime;
        mSource.speak("play");

        ArgumentCaptor<A2uiComponent> dispatched = ArgumentCaptor.forClass(A2uiComponent.class);
        verify(mProcessor).dispatchByVoice(eq(SURFACE), eq("play"), dispatched.capture());
        assertThat(dispatched.getValue()).isSameInstanceAs(atPhraseTime);
        assertThat(dispatched.getValue()).isNotSameInstanceAs(atListenTime);
    }

    /**
     * The gate is consulted before the microphone opens: asserted as "listen was never called",
     * since opening and discarding would still record the driver.
     */
    @Test
    public void aRefusedGateNeverOpensTheMicrophone() {
        A2uiVoiceCapture capture = captureWith(false, TranscriptSource.Unavailable.RESTRICTED);

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mSource.mListenCalls).isEqualTo(0);
        assertThat(mListener.mUnavailable)
                .containsExactly(TranscriptSource.Unavailable.RESTRICTED);
        verify(mProcessor, never()).dispatchByVoice(any(), any(), any());
    }

    @Test
    public void theOutcomeOfDispatchIsReportedToTheHost() {
        when(mProcessor.dispatchByVoice(any(), any(), any()))
                .thenReturn(VoiceOutcome.CONFIRMATION_REQUIRED);
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);

        capture.listen(SURFACE, () -> tree("root"), mListener);
        mSource.speak("buy album");

        assertThat(mListener.mDispatched).containsExactly("buy album");
        assertThat(mListener.mLastOutcome).isEqualTo(VoiceOutcome.CONFIRMATION_REQUIRED);
    }

    /** A source failure reaches the host as its own reason, not as silence. */
    @Test
    public void anUnavailableSourceIsReportedWithItsReasonAndDetail() {
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);

        capture.listen(SURFACE, () -> tree("root"), mListener);
        mSource.fail(TranscriptSource.Unavailable.NO_RECOGNITION_SERVICE, "none installed");

        assertThat(mListener.mUnavailable)
                .containsExactly(TranscriptSource.Unavailable.NO_RECOGNITION_SERVICE);
        assertThat(mListener.mLastDetail).isEqualTo("none installed");
        assertThat(capture.isListening()).isFalse();
    }

    /** A second listen while one is outstanding is refused rather than overlapped. */
    @Test
    public void aSecondListenWhileOneIsOutstandingIsRefusedAndDoesNotOpenASecondMicrophone() {
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);
        capture.listen(SURFACE, () -> tree("root"), mListener);

        capture.listen(SURFACE, () -> tree("root"), mListener);

        assertThat(mSource.mListenCalls).isEqualTo(1);
        assertThat(mListener.mUnavailable)
                .containsExactly(TranscriptSource.Unavailable.CANCELLED);
    }

    @Test
    public void listeningEndsWhenAPhraseArrives() {
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);
        capture.listen(SURFACE, () -> tree("root"), mListener);
        assertThat(capture.isListening()).isTrue();

        mSource.speak("play");

        assertThat(capture.isListening()).isFalse();
    }

    /**
     * Cancel always reaches the source, even with no listen outstanding: it releases a finished
     * recogniser.
     */
    @Test
    public void cancelAlwaysReachesTheSource_soAFinishedRecogniserIsReleased() {
        A2uiVoiceCapture capture = captureWith(true, TranscriptSource.Unavailable.RESTRICTED);

        capture.cancel();
        assertThat(mSource.mCancelCalls).isEqualTo(1);

        capture.listen(SURFACE, () -> tree("root"), mListener);
        mSource.speak("play");
        assertThat(capture.isListening()).isFalse();

        // The listen completed; this call still releases the recogniser.
        capture.cancel();

        assertThat(mSource.mCancelCalls).isEqualTo(2);
        assertThat(capture.isListening()).isFalse();
    }

    /** Presses per thread in the two-thread test below. See its comment for why the number. */
    private static final int SIMULTANEOUS_PRESSES = 20_000;

    /**
     * Two threads calling {@code listen} at the same instant open one microphone, not two. Both
     * presses spin in the gate until aligned, then race the guard. The visibility half of the
     * defect cannot be demonstrated by a test, since observing it creates the happens-before edge.
     */
    @Test(timeout = 60_000)
    public void twoThreadsPressingSpeakAtOnceOpenOnlyOneMicrophone() throws Exception {
        AtomicInteger inGate = new AtomicInteger();
        AtomicInteger bothPressesAligned = new AtomicInteger();
        AtomicInteger microphonesThisTrial = new AtomicInteger();
        AtomicInteger microphones = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();
        AtomicInteger trialsWithTwoMicrophones = new AtomicInteger();

        TranscriptSource counting = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                // Never reports, so a second press within a trial must be refused.
                microphonesThisTrial.incrementAndGet();
                microphones.incrementAndGet();
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture.Gate aligning = new A2uiVoiceCapture.Gate() {
            @Override
            public boolean allowsListening() {
                inGate.incrementAndGet();
                while (inGate.get() < 2) {
                    // Deliberate hot spin. See the comment above.
                }
                bothPressesAligned.incrementAndGet();
                return true;
            }

            @Override
            @NonNull
            public TranscriptSource.Unavailable reason() {
                return TranscriptSource.Unavailable.RESTRICTED;
            }
        };
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mProcessor, counting, aligning);
        A2uiVoiceCapture.Listener counter = new A2uiVoiceCapture.Listener() {
            @Override
            public void onDispatched(@NonNull String spoken, @NonNull VoiceOutcome outcome) {
            }

            @Override
            public void onUnavailable(@NonNull TranscriptSource.Unavailable reason,
                    @Nullable String detail) {
                refusals.incrementAndGet();
            }
        };

        CyclicBarrier startOfTrial = new CyclicBarrier(3);
        CyclicBarrier endOfTrial = new CyclicBarrier(3);
        AtomicReference<Throwable> onAPressThread = new AtomicReference<>();
        Runnable press = () -> {
            try {
                for (int i = 0; i < SIMULTANEOUS_PRESSES; i++) {
                    startOfTrial.await(30, TimeUnit.SECONDS);
                    try {
                        capture.listen(SURFACE, () -> tree("root"), counter);
                    } finally {
                        endOfTrial.await(30, TimeUnit.SECONDS);
                    }
                }
            } catch (Throwable t) {
                // Recorded for the test thread, which asserts it.
                onAPressThread.compareAndSet(null, t);
            }
        };
        Thread first = new Thread(press, "speak-press-1");
        Thread second = new Thread(press, "speak-press-2");
        first.start();
        second.start();

        for (int trial = 0; trial < SIMULTANEOUS_PRESSES; trial++) {
            inGate.set(0);
            microphonesThisTrial.set(0);
            // The source never reported, so the winning press is still outstanding. This is the
            // only thing that ends it, and it runs while both threads are parked on the barrier.
            capture.cancel();
            startOfTrial.await(30, TimeUnit.SECONDS);
            endOfTrial.await(30, TimeUnit.SECONDS);
            if (microphonesThisTrial.get() > 1) {
                trialsWithTwoMicrophones.incrementAndGet();
            }
        }
        first.join();
        second.join();

        assertThat(onAPressThread.get()).isNull();
        assertWithMessage("trials where both presses opened a microphone, of %s",
                SIMULTANEOUS_PRESSES)
                .that(trialsWithTwoMicrophones.get()).isEqualTo(0);
        assertThat(microphones.get()).isEqualTo(SIMULTANEOUS_PRESSES);
        assertThat(refusals.get()).isEqualTo(SIMULTANEOUS_PRESSES);
        // Anti-vacuity: every press saw the other in the gate, so they really raced.
        assertThat(bothPressesAligned.get()).isEqualTo(2 * SIMULTANEOUS_PRESSES);
    }

    /**
     * The gate is asked again when the phrase lands: the vehicle can start moving mid-utterance.
     */
    @Test
    public void aTranscriptArrivingAfterTheVehicleChangesItsMindIsRefused() {
        AtomicReference<TranscriptSource.Callback> held = new AtomicReference<>();
        TranscriptSource deferred = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                held.set(callback);   // the phrase arrives later, on the test's schedule
            }

            @Override
            public void cancel() {
            }
        };
        AtomicBoolean allowed = new AtomicBoolean(true);
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mProcessor, deferred,
                new A2uiVoiceCapture.Gate() {
                    @Override
                    public boolean allowsListening() {
                        return allowed.get();
                    }

                    @Override
                    @NonNull
                    public TranscriptSource.Unavailable reason() {
                        return TranscriptSource.Unavailable.RESTRICTED;
                    }
                });

        capture.listen(SURFACE, () -> tree("root"), mListener);
        assertThat(held.get()).isNotNull();   // the gate allowed the session to open

        allowed.set(false);                   // ...and then the vehicle started moving
        held.get().onTranscript("play");

        assertThat(mListener.mDispatched).isEmpty();
        assertThat(mListener.mUnavailable).containsExactly(TranscriptSource.Unavailable.RESTRICTED);
    }

    /** The other direction: an unchanged gate lets the same phrase through. */
    @Test
    public void aTranscriptArrivingWhileTheVehicleStillAllowsItDispatches() {
        AtomicReference<TranscriptSource.Callback> held = new AtomicReference<>();
        TranscriptSource deferred = new TranscriptSource() {
            @Override
            public void listen(@NonNull Callback callback) {
                held.set(callback);
            }

            @Override
            public void cancel() {
            }
        };
        A2uiVoiceCapture capture = new A2uiVoiceCapture(mProcessor, deferred,
                new A2uiVoiceCapture.Gate() {
                    @Override
                    public boolean allowsListening() {
                        return true;
                    }

                    @Override
                    @NonNull
                    public TranscriptSource.Unavailable reason() {
                        return TranscriptSource.Unavailable.RESTRICTED;
                    }
                });

        capture.listen(SURFACE, () -> tree("root"), mListener);
        held.get().onTranscript("play");

        assertThat(mListener.mDispatched).containsExactly("play");
        assertThat(mListener.mUnavailable).isEmpty();
    }
}
