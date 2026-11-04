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
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.car.drivingstate.CarUxRestrictions;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import com.android.car.ui.core.CarUiInstaller;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import art.dart.a2ui.policy.A2uiActionBinder;
import art.dart.a2ui.policy.A2uiRestrictionPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * That {@code startListening()} asks the gate, not that the gate answers correctly
 * ({@link A2uiVoiceGateTest}): deleting the call leaves those tests green. With no delivered
 * reading the gate must refuse; the toast pins the order, since without the gate control reaches
 * the permission request and no toast is shown.
 */
@RunWith(RobolectricTestRunner.class)
public class A2uiVoiceWiringTest {

    /**
     * Installs car-ui-lib's LayoutInflater factory, which {@code CarUiInstaller} (not run under
     * Robolectric) would; the CarUiRecyclerView tag is an interface.
     */
    @Before
    public void installCarUiFactory() {
        CarUiInstaller.register(RuntimeEnvironment.getApplication());
    }

    /** The instance cache is static and keyed by display, so it outlives a single test. */
    @Before
    public void clearRestrictionInstances() {
        CarUxRestrictionsUtil.clearInstancesForTesting();
    }

    /** Do not let toolbar async-inflation work escape into the next Robolectric sandbox. */
    @After
    public void drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void startListeningRefusesBeforeItTouchesThePermissionOrTheRecogniser() {
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().get();

        activity.startListening();

        // The recogniser is the load-bearing assertion; a toast can be shown by code that then
        // opens the microphone anyway.
        assertThat(activity.mRecognizer).isNull();
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    @Test
    public void aTranscriptArrivingAfterTheVehicleForbidsItIsRefused() {
        // onHeard() is reachable without startListening() (the debug SPEAK receiver, or a session
        // outliving its reading), so it must gate too: dispatchByVoice is never reached.
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().get();

        activity.onHeard("pause");

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    @Test
    public void theSpeakControlIsWithheldWhileTheVehicleForbidsTranscription() {
        // The control is withheld while refused. start(), not create(): onStart applies it.
        A2uiCarUiActivity activity =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().start().get();

        assertThat(activity.mSpeakItem).isNotNull();
        assertThat(activity.mSpeakItem.isEnabled()).isFalse();
        // The header's microphone, the same control on consumer screens, is withheld alike...
        assertThat(activity.mHeaderMic.isEnabled()).isFalse();
        // ...and a tap that gets through anyway is refused by the vehicle gate it calls.
        ShadowToast.reset();
        activity.mHeaderMic.performClick();
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    private static CarUxRestrictions reading(int mask) {
        return new CarUxRestrictions.Builder(false, mask, 0).build();
    }

    /**
     * The allowed direction, end to end, so a hard-refusing implementation fails: a parked,
     * delivered reading lets the control through to the permission check.
     */
    @Test
    public void aParkedVehicleLetsTheControlThroughAndReachesThePermissionCheck() {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        A2uiCarUiActivity activity = controller.get();
        CarUxRestrictionsUtil.getInstance(activity)
                .setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        controller.start();
        assertThat(activity.mSpeakItem.isEnabled()).isTrue();
        assertThat(activity.mHeaderMic.isEnabled()).isTrue();

        activity.startListening();

        // Past the gate: RECORD_AUDIO is not granted, so it stops at the permission branch.
        assertThat(ShadowToast.getTextOfLatestToast())
                .isNotEqualTo(A2uiActionBinder.REFUSED_NOTE);
    }

    @Test
    public void permissionGrantResumesThroughStartListeningSoUxrIsRechecked() {
        A2uiCarUiActivity activity = spy(
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().get());
        doNothing().when(activity).startListening();

        activity.onRequestPermissionsResult(1,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_GRANTED});

        verify(activity).startListening();
    }

    @Test
    public void permissionDenialDoesNotResumeListening() {
        A2uiCarUiActivity activity = spy(
                Robolectric.buildActivity(A2uiCarUiActivity.class).create().get());
        doNothing().when(activity).startListening();

        activity.onRequestPermissionsResult(1,
                new String[]{Manifest.permission.RECORD_AUDIO},
                new int[]{PackageManager.PERMISSION_DENIED});

        verify(activity, never()).startListening();
    }

    @Test
    public void spokenConfirmationConfirmsTheCapturedComponentInsteadOfRedispatchingThePhrase() {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        A2uiCarUiActivity activity = controller.get();
        CarUxRestrictionsUtil.getInstance(activity)
                .setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        controller.start();
        String[] invokedComponent = new String[1];
        activity.mProcessor.setActionSink((surface, component, action, payload) ->
                invokedComponent[0] = component);

        activity.onHeard("buy album");
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(confirmation).isNotNull();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(invokedComponent[0]).isEqualTo("buy");
        assertThat(String.valueOf(ShadowToast.getTextOfLatestToast())).contains("done");
    }

    @Test
    public void spokenConfirmationFailsClosedWhenTheSurfaceChanges() {
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        A2uiCarUiActivity activity = controller.get();
        CarUxRestrictionsUtil.getInstance(activity)
                .setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        controller.start();
        String[] invokedComponent = new String[1];
        activity.mProcessor.setActionSink((surface, component, action, payload) ->
                invokedComponent[0] = component);

        activity.onHeard("buy album");
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertThat(confirmation).isNotNull();
        assertThat(activity.selectCase("now_playing")).isTrue();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(invokedComponent[0]).isNull();
        assertThat(String.valueOf(ShadowToast.getTextOfLatestToast()))
                .contains("conditions changed");
    }

    @Test
    public void aRunningRecogniserIsClosedWhenTheVehicleWithdrawsPermission() {
        // Withholding the control does not stop a capture already running; refresh() must close it.
        // Planted directly, since refresh()'s obligation is under test, not the recogniser.
        ActivityController<A2uiCarUiActivity> controller =
                Robolectric.buildActivity(A2uiCarUiActivity.class).create();
        A2uiCarUiActivity activity = controller.get();
        CarUxRestrictionsUtil restrictions = CarUxRestrictionsUtil.getInstance(activity);

        // Allowed first, so this is a real allowed -> forbidden transition.
        restrictions.setUxRestrictions(reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_BASELINE));
        controller.start();
        activity.mRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        assertThat(activity.mRecognizer).isNotNull();
        assertThat(activity.mSpeakItem.isEnabled()).isTrue();

        // ...and now the vehicle changes its mind mid-session.
        restrictions.setUxRestrictions(
                reading(A2uiRestrictionPolicy.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION));

        assertThat(activity.mRecognizer).isNull();
        assertThat(activity.mSpeakItem.isEnabled()).isFalse();
        assertThat(activity.mHeaderMic.isEnabled()).isFalse();
    }
}
