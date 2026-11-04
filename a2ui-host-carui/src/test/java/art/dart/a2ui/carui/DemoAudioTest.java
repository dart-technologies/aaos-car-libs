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
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowTextToSpeech;

import java.time.Duration;

/** B4: speech and earcons only ever play under transient ducking focus, and absence is silence. */
@RunWith(RobolectricTestRunner.class)
public class DemoAudioTest {
    private static final String ABSENT = "sfx-not-bundled";

    private Application mApp;
    private ShadowAudioManager mAudio;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        mAudio = shadowOf(mApp.getSystemService(AudioManager.class));
    }

    @Test
    public void absentEarconsAreSilentAndTakeNoFocus() {
        DemoAudio audio = new DemoAudio(mApp, ABSENT);

        assertThat(audio.hasEarcons()).isFalse();
        for (String id : new String[]{DemoAudio.WELCOME, DemoAudio.CONFIRMED, DemoAudio.FOCUS,
                DemoAudio.ARRIVAL}) {
            assertThat(audio.earcon(id)).isFalse();
        }
        assertThat(mAudio.getLastAudioFocusRequest()).isNull();
        audio.release();
        assertThat(audio.earcon(DemoAudio.WELCOME)).isFalse();
    }

    @Test
    public void speechTakesTransientDuckingFocusAndGivesItBackWhenDone() {
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.speak("Coffee on the way");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        // Nothing is asked of the speaker before the engine is ready.
        assertThat(mAudio.getLastAudioFocusRequest()).isNull();

        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);

        AudioFocusRequest request = mAudio.getLastAudioFocusRequest().audioFocusRequest;
        assertThat(request.getFocusGain()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        assertThat(request.getAudioAttributes().getUsage())
                .isEqualTo(AudioAttributes.USAGE_ASSISTANT);
        assertThat(shadowOf(tts).getLastSpokenText()).isEqualTo("Coffee on the way");
        assertThat(audio.holdsFocus()).isTrue();

        shadowOf(Looper.getMainLooper()).idle();

        assertThat(mAudio.getLastAbandonedAudioFocusRequest()).isSameInstanceAs(request);
        assertThat(audio.holdsFocus()).isFalse();
        audio.release();
        assertThat(shadowOf(tts).isShutdown()).isTrue();
    }

    @Test
    public void noDuckingFocusMeansNoSpeech() {
        mAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.speak("Coffee on the way");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();

        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);

        assertThat(shadowOf(tts).getLastSpokenText()).isNull();
        assertThat(audio.holdsFocus()).isFalse();
    }

    @Test
    public void losingFocusMidLineStopsAndReleases() {
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.speak("Coffee on the way");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        AudioFocusRequest request = mAudio.getLastAudioFocusRequest().audioFocusRequest;

        audio.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

        assertThat(shadowOf(tts).isStopped()).isTrue();
        assertThat(audio.holdsFocus()).isFalse();
        assertThat(mAudio.getLastAbandonedAudioFocusRequest()).isSameInstanceAs(request);
    }

    /** onStop's quiet() stops a playing earcon, not only speech. */
    @Test
    public void quietStopsEarconStreams() {
        SoundPool pool = mock(SoundPool.class);
        when(pool.play(anyInt(), anyFloat(), anyFloat(), anyInt(), anyInt(), anyFloat()))
                .thenReturn(7);
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.useEarconForTesting(pool, DemoAudio.WELCOME, 1);
        assertThat(audio.earcon(DemoAudio.WELCOME)).isTrue();

        audio.quiet();

        verify(pool).stop(7);
        assertThat(audio.holdsFocus()).isFalse();
    }

    /** A title queued behind an earcon keeps the focus: the user's media is not unducked between. */
    @Test
    public void anEarconThenATitleHoldFocusThroughout() {
        SoundPool pool = mock(SoundPool.class);
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.useEarconForTesting(pool, DemoAudio.WELCOME, 1);
        assertThat(audio.earcon(DemoAudio.WELCOME)).isTrue();
        audio.speak("Coffee on the way");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        ShadowAudioManager.AudioFocusRequest held = mAudio.getLastAudioFocusRequest();
        // The title waits for the earcon.
        assertThat(shadowOf(tts).getLastSpokenText()).isNull();

        // The focus check and the title fall due together when the earcon ends.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500));

        assertThat(shadowOf(tts).getLastSpokenText()).isEqualTo("Coffee on the way");
        // One request for both: a second one means focus was dropped and taken back between.
        assertThat(mAudio.getLastAudioFocusRequest()).isSameInstanceAs(held);
        assertThat(mAudio.getLastAbandonedAudioFocusRequest())
                .isSameInstanceAs(held.audioFocusRequest);
    }

    @Test
    public void noEngineMeansSilenceNotACrash() {
        DemoAudio audio = new DemoAudio(mApp, ABSENT);
        audio.speak("Coffee on the way");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();

        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.ERROR);
        audio.speak("Still nothing");

        assertThat(shadowOf(tts).getLastSpokenText()).isNull();
        assertThat(mAudio.getLastAudioFocusRequest()).isNull();
        audio.release();
    }
}
