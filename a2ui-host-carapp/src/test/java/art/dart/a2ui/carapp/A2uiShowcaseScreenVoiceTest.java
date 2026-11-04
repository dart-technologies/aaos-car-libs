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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.robolectric.Shadows.shadowOf;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.model.Action;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;
import art.dart.a2ui.protocol.A2uiMessageProcessor.VoiceOutcome;

import org.junit.After;
import org.junit.Before;
import android.car.drivingstate.CarUxRestrictions;
import com.android.car.ui.utils.CarUxRestrictionsUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * The voice wiring in the screen: the tree is read when the phrase arrives, the microphone is asked
 * for once, and approval retries the press. {@link TestCarContext} records the permission request
 * without a templates host.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiShowcaseScreenVoiceTest {

    private TestCarContext mCarContext;
    private FakeSource mSource;
    private A2uiShowcaseScreen mScreen;
    private ScreenController mController;

    @Before
    public void setUp() {
        mCarContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        mSource = new FakeSource();
        // A demo override is the only trustworthy reading under Robolectric (no car service).
        // The list limit is the emulator templates host's real 100: TestCarContext's default of 6
        // would truncate every interactive control off the screen.
        overrideContentLimit(100);
        CarUxRestrictionsUtil.getInstance(mCarContext).setUxRestrictions(
                new CarUxRestrictions.Builder(false,
                        A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());
        mScreen = new A2uiShowcaseScreen(mCarContext, mSource);
        mScreen.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        // STARTED, as a screen being pressed is: the permission retry and teardown depend on it.
        mController = new ScreenController(mScreen);
        mController.moveToState(Lifecycle.State.STARTED);
    }

    @After
    public void tearDown() {
        // The screen override is mutable state and must not leak into later assertions.
        mScreen.setDemoOverrideForTesting(null);
    }

    // The action is offered, or it is not there at all.

    @Test
    public void theSpeakActionIsOfferedWhenTheVehicleAllowsTranscription() {
        Action action = mScreen.voiceAction(DemoTrace.SURFACE_ID);

        assertThat(action).isNotNull();
        assertThat(action.getTitle().toString()).isEqualTo("Speak");
    }

    /** Absent, not present-and-declining, as {@code A2uiActionBinder} does for a refused row. */
    @Test
    public void theSpeakActionIsWithheldWhenTheVehicleForbidsTranscription() {
        mScreen.setDemoOverrideForTesting(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);

        assertThat(mScreen.voiceAction(DemoTrace.SURFACE_ID)).isNull();
    }

    /**
     * A vehicle that starts moving mid-utterance closes the microphone, not only the button that
     * opens it.
     */
    @Test
    public void aVehicleThatForbidsTranscriptionMidCaptureClosesTheMicrophone() {
        mScreen.startListening();
        assertThat(mSource.mListenCalls).isEqualTo(1);
        assertThat(mSource.mCancelCalls).isEqualTo(0);

        mScreen.setDemoOverrideForTesting(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);

        assertThat(mSource.mCancelCalls).isEqualTo(1);
    }

    /** The other direction: a state change that still permits transcription cancels nothing. */
    @Test
    public void aVehicleStateChangeThatStillPermitsTranscriptionLeavesTheCaptureAlone() {
        mScreen.startListening();

        mScreen.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VIDEO);

        assertThat(mSource.mCancelCalls).isEqualTo(0);
    }

    @Test
    public void aRefusedGateNeverOpensTheMicrophone() {
        mScreen.setDemoOverrideForTesting(
                A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);

        mScreen.startListening();

        assertThat(mSource.mListenCalls).isEqualTo(0);
    }

    // The tree is read when the phrase arrives.

    /**
     * A phrase acts on what the driver sees when they finish speaking: LIMIT_CONTENT arriving
     * mid-utterance removes "Buy album" (the tenth component), so only re-reading the tree yields
     * NO_MATCH.
     */
    @Test
    public void theTreeIsReadWhenThePhraseArrives_notWhenListeningStarted() {
        mScreen.startListening();
        assertThat(mSource.mListenCalls).isEqualTo(1);

        // The vehicle restricts content WHILE the driver is still speaking.
        mScreen.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_LIMIT_CONTENT);
        mSource.speak("buy album");

        assertThat(lastOutcome()).isEqualTo(VoiceOutcome.NO_MATCH);
    }

    /** The same phrase, with nothing changing in between, must still reach the control. */
    @Test
    public void withNothingChangingThePhraseReachesTheControl() {
        mScreen.startListening();

        mSource.speak("buy album");

        assertThat(lastOutcome()).isEqualTo(VoiceOutcome.CONFIRMATION_REQUIRED);
    }

    // Listening, and being able to stop.

    /** The listening half of the ternary in {@code voiceAction()}. */
    @Test
    public void whileListeningTheActionSaysSo() {
        mScreen.startListening();

        assertThat(mScreen.voiceAction(DemoTrace.SURFACE_ID).getTitle().toString()).isEqualTo("Listening…");
    }

    /** The action stops a running listen, so the driver can always close the microphone. */
    @Test
    public void stoppingClosesTheMicrophoneAndPutsTheActionBack() {
        mScreen.startListening();

        mScreen.stopListening();

        assertThat(mSource.mCancelCalls).isEqualTo(1);
        assertThat(mScreen.voiceAction(DemoTrace.SURFACE_ID).getTitle().toString()).isEqualTo("Speak");
    }

    /**
     * Pressing the action listens on the surface the action was built for, asserted through the
     * real click delegate: the host can show a template built before a case switch.
     */
    @Test
    public void pressingTheActionListensOnTheSurfaceItWasBuiltFor() {
        Action action = mScreen.voiceAction("handoff_to_driver");
        mScreen.selectCase(DemoTrace.SURFACE_ID);

        action.getOnClickDelegate().sendClick(new OnDoneCallback() {
            @Override
            public void onSuccess(@Nullable Bundleable response) {
            }

            @Override
            public void onFailure(@NonNull Bundleable failureResponse) {
            }
        });
        mSource.speak("send to driver");

        // "Send to driver" exists only on handoff_to_driver. Reading the id at press time would
        // have resolved it against the showcase trace instead, where nothing answers to it.
        assertThat(mScreen.lastVoiceOutcome()).isNotEqualTo(VoiceOutcome.NO_MATCH);
    }

    /** Anti-vacuity: that phrase really does name nothing on the surface now selected. */
    @Test
    public void thatPhraseNamesNothingOnTheShowcaseTrace() {
        mScreen.startListening(DemoTrace.SURFACE_ID);
        mSource.speak("send to driver");

        assertThat(mScreen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.NO_MATCH);
    }

    // What the driver is told.

    /** Every outcome is said in words; no enum name reaches the toast. */
    @Test
    public void everyOutcomeIsSaidInWordsAndNoneLeaksItsEnumName() {
        int seen = 0;
        for (VoiceOutcome outcome : VoiceOutcome.values()) {
            seen++;
            String message = A2uiShowcaseScreen.voiceMessage("buy album", outcome);
            assertThat(message).doesNotContain(outcome.name());
            assertThat(message).isNotEmpty();
        }
        assertThat(seen).isEqualTo(5);
        assertThat(A2uiShowcaseScreen.voiceMessage("buy album", VoiceOutcome.DISPATCHED))
                .contains("done");
        assertThat(A2uiShowcaseScreen.voiceMessage("buy album", VoiceOutcome.REFUSED_BY_POLICY))
                .contains("while driving");
    }

    /** The refusal half: no "Voice unavailable: CANCELLED". */
    @Test
    public void everyRefusalIsSaidInWordsAndNoneLeaksItsEnumName() {
        int seen = 0;
        for (TranscriptSource.Unavailable reason : TranscriptSource.Unavailable.values()) {
            seen++;
            String message = A2uiShowcaseScreen.unavailableMessage(reason);
            assertThat(message).doesNotContain(reason.name());
            assertThat(message).isNotEmpty();
        }
        assertThat(seen).isEqualTo(5);
        assertThat(A2uiShowcaseScreen.unavailableMessage(
                TranscriptSource.Unavailable.PERMISSION_DENIED)).contains("microphone");
        // Deliberately NOT asserting "while driving": the gate refuses both for the vehicle's
        // no-transcription bit and for a reading it cannot trust, and only the first means moving.
        assertThat(A2uiShowcaseScreen.unavailableMessage(
                TranscriptSource.Unavailable.RESTRICTED)).doesNotContain("driving");
    }

    /**
     * A phrase cannot reach a control the host's list limit removed: at a limit of six, "Buy album"
     * is off screen, while the test above reaches it at 100.
     */
    @Test
    public void aPhraseCannotReachAControlTheHostContentLimitRemoved() {
        overrideContentLimit(6);
        FakeSource source = new FakeSource();
        A2uiShowcaseScreen screen = new A2uiShowcaseScreen(mCarContext, source);
        screen.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        new ScreenController(screen).moveToState(Lifecycle.State.STARTED);

        screen.startListening();
        source.speak("buy album");

        assertThat(screen.lastVoiceOutcome()).isEqualTo(VoiceOutcome.NO_MATCH);
    }

    // The microphone request.

    @Test
    public void aMissingPermissionRequestsRECORD_AUDIO() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);

        TestCarContext.PermissionRequestInfo info = mCarContext.getLastPermissionRequestInfo();
        assertThat(info).isNotNull();
        assertThat(info.getPermissionsRequested())
                .containsExactly(Manifest.permission.RECORD_AUDIO);
    }

    /** Approval retries the press the driver already made. */
    @Test
    public void approvingTheMicrophoneRetriesTheListen() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);
        assertThat(mSource.mListenCalls).isEqualTo(1);

        approve();

        assertThat(mSource.mListenCalls).isEqualTo(2);
    }

    /**
     * The retry listens on the surface the press was made against, not one selected while the
     * dialog was up.
     */
    @Test
    public void approvingTheMicrophoneRetriesOnTheSurfaceThePressWasMadeAgainst() {
        mScreen.startListening("handoff_to_driver");
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);

        // The driver answers the dialog; a case broadcast lands while it is up.
        mScreen.selectCase(DemoTrace.SURFACE_ID);
        approve();
        mSource.speak("send to driver");

        // "Send to driver" exists only on handoff_to_driver. Retrying on the newly selected case
        // would find nothing.
        assertThat(mScreen.lastVoiceOutcome()).isNotEqualTo(VoiceOutcome.NO_MATCH);
    }

    /**
     * A driver who declines is not asked again. Asserted by identity: {@code TestCarContext}
     * exposes only the last request.
     */
    @Test
    public void decliningTheMicrophoneAsksNoSecondTime() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);
        TestCarContext.PermissionRequestInfo first = mCarContext.getLastPermissionRequestInfo();
        decline();

        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);

        assertThat(mCarContext.getLastPermissionRequestInfo()).isSameInstanceAs(first);
    }

    /** Anti-vacuity for the test above: a request that is made produces a different instance. */
    @Test
    public void aFreshScreenDoesAskAgain_soTheIdentityCheckMeasuresSomething() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);
        TestCarContext.PermissionRequestInfo first = mCarContext.getLastPermissionRequestInfo();
        decline();

        FakeSource second = new FakeSource();
        A2uiShowcaseScreen fresh = new A2uiShowcaseScreen(mCarContext, second);
        fresh.setDemoOverrideForTesting(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE);
        fresh.startListening();
        second.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);

        assertThat(mCarContext.getLastPermissionRequestInfo()).isNotSameInstanceAs(first);
    }

    @Test
    public void noOtherRefusalRaisesARequest() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.NO_RECOGNITION_SERVICE);

        assertThat(mCarContext.getLastPermissionRequestInfo()).isNull();
    }

    // Teardown.

    /**
     * Destroying the screen releases the microphone and the receivers, which would otherwise reach
     * every Screen built on each host re-bind.
     */
    @Test
    public void destroyingTheScreenStopsListeningAndReleasesTheReceivers() {
        mScreen.startListening();
        int registeredWhileAlive = shadowOf(RuntimeEnvironment.getApplication())
                .getRegisteredReceivers().size();

        mController.moveToState(Lifecycle.State.DESTROYED);

        assertThat(mSource.mCancelCalls).isAtLeast(1);
        assertThat(shadowOf(RuntimeEnvironment.getApplication()).getRegisteredReceivers().size())
                .isLessThan(registeredWhileAlive);
    }

    /** Anti-vacuity: the screen really did register receivers, so the count above measures them. */
    @Test
    public void theScreenRegistersDebugReceiversToBeginWith() {
        int withScreen = shadowOf(RuntimeEnvironment.getApplication())
                .getRegisteredReceivers().size();

        new A2uiShowcaseScreen(mCarContext, new FakeSource());

        assertThat(shadowOf(RuntimeEnvironment.getApplication()).getRegisteredReceivers().size())
                .isGreaterThan(withScreen);
    }

    /**
     * An approval arriving after the app was dismissed must not open the microphone on a screen
     * with no surface.
     */
    @Test
    public void anApprovalArrivingAfterTheScreenStoppedDoesNotOpenTheMicrophone() {
        mScreen.startListening();
        mSource.refuse(TranscriptSource.Unavailable.PERMISSION_DENIED);
        int listensBefore = mSource.mListenCalls;

        mController.moveToState(Lifecycle.State.CREATED);
        approve();

        assertThat(mSource.mListenCalls).isEqualTo(listensBefore);
    }

    // ------------------------------------------------------------------------------------

    private void approve() {
        TestCarContext.PermissionRequestInfo info = mCarContext.getLastPermissionRequestInfo();
        info.getListener().onRequestPermissionsResult(
                listOf(Manifest.permission.RECORD_AUDIO), new ArrayList<>());
    }

    private void decline() {
        TestCarContext.PermissionRequestInfo info = mCarContext.getLastPermissionRequestInfo();
        info.getListener().onRequestPermissionsResult(
                new ArrayList<>(), listOf(Manifest.permission.RECORD_AUDIO));
    }

    private static List<String> listOf(String one) {
        List<String> out = new ArrayList<>();
        out.add(one);
        return out;
    }

    @Nullable
    private VoiceOutcome lastOutcome() {
        return mScreen.lastVoiceOutcome();
    }

    /** Stands in for the recogniser: records that it was asked, and delivers on demand. */
    private void overrideContentLimit(int limit) {
        ConstraintManager constraints = mock(ConstraintManager.class);
        when(constraints.getContentLimit(anyInt())).thenReturn(limit);
        mCarContext.overrideCarService(ConstraintManager.class, constraints);
    }

    private static final class FakeSource implements TranscriptSource {
        int mListenCalls;
        int mCancelCalls;
        @Nullable Callback mCallback;

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

        void refuse(Unavailable reason) {
            mCallback.onUnavailable(reason, null);
        }
    }

    /**
     * The screen can be constructed with no demo override, as at every cold start: register()
     * seeds the listener synchronously before an authoritative reading exists.
     */
    @Test
    public void constructorSurvivesWithNoDemoOverride() {
        A2uiShowcaseScreen screen = new A2uiShowcaseScreen(mCarContext, mSource);

        assertThat(screen).isNotNull();
    }

    /**
     * The real listener cancels, not just the method it calls. Drives a genuine reading, since an
     * override bypasses the listener path a moving vehicle takes.
     */
    @Test
    public void aRealRestrictionChangeCancelsTheCapture_soTheListenerItselfIsPinned() {
        mScreen.setDemoOverrideForTesting(null);
        CarUxRestrictionsUtil restrictions = CarUxRestrictionsUtil.getInstance(mCarContext);
        restrictions.setUxRestrictions(new CarUxRestrictions.Builder(
                false, A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE, 0).build());

        mScreen.startListening();
        assertThat(mSource.mListenCalls).isEqualTo(1);
        assertThat(mSource.mCancelCalls).isEqualTo(0);

        // No direct call: this goes through CarUxRestrictionsUtil's own fan-out to whatever the
        // screen registered, which is the thing under test.
        restrictions.setUxRestrictions(new CarUxRestrictions.Builder(
                false, A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION, 0).build());

        assertThat(mSource.mCancelCalls).isEqualTo(1);
    }
}
