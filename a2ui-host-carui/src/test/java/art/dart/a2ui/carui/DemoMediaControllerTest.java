/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.carui;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.widget.VideoView;

import art.dart.a2ui.model.ComponentType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.util.EnumSet;
import java.util.List;

/** B5: what reaches the real MediaSession, and how audio focus moves playback. */
@RunWith(RobolectricTestRunner.class)
public class DemoMediaControllerTest {

    private Application mApp;
    private ShadowAudioManager mAudio;
    private MediaSession mSession;

    @Before
    public void setUp() {
        mApp = RuntimeEnvironment.getApplication();
        mAudio = shadowOf(mApp.getSystemService(AudioManager.class));
        mSession = mock(MediaSession.class);
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource("android.resource://"
                + mApp.getPackageName() + "/" + R.raw.demo_placeholder_audio),
                new ShadowMediaPlayer.MediaInfo(12_000, 0));
    }

    @Test
    public void transitionThatRemovesVideoRequiresImmediateTeardown() {
        assertThat(DemoMediaController.shouldStopVideo(
                EnumSet.of(ComponentType.AUDIO_PLAYER), DemoMediaController.Mode.VIDEO)).isTrue();
    }

    @Test
    public void audioSurvivesNoVideoAndParkedVideoDoesNotSelfStop() {
        assertThat(DemoMediaController.shouldStopVideo(
                EnumSet.of(ComponentType.AUDIO_PLAYER), DemoMediaController.Mode.AUDIO)).isFalse();
        assertThat(DemoMediaController.shouldStopVideo(
                EnumSet.of(ComponentType.VIDEO), DemoMediaController.Mode.VIDEO)).isFalse();
    }

    @Test
    public void audioTakesMediaFocusPublishesMetadataAndPausesOnLoss() {
        DemoMediaController media = new DemoMediaController(mApp, mSession);
        media.setImagesAllowed(true);

        media.toggleAudio();

        assertThat(media.isPlaying()).isTrue();
        verify(mSession).setActive(true);
        AudioFocusRequest request = mAudio.getLastAudioFocusRequest().audioFocusRequest;
        assertThat(request).isSameInstanceAs(media.focusRequestForTesting());
        assertThat(request.getFocusGain()).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
        MediaMetadata metadata = lastMetadata();
        assertThat(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                .isEqualTo(mApp.getString(R.string.a2ui_brand_media_title));
        assertThat(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                .isEqualTo(mApp.getString(R.string.a2ui_brand_media_artist));
        assertThat(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).isEqualTo(12_000);
        assertThat(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)).isNotNull();

        media.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);

        assertThat(media.isPlaying()).isFalse();
        assertThat(mAudio.getLastAbandonedAudioFocusRequest()).isSameInstanceAs(request);
        // A permanent loss is not undone by a later gain.
        media.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertThat(media.isPlaying()).isFalse();
    }

    /** Album art is an image: off the session while the reading withholds IMAGE. */
    @Test
    public void albumArtFollowsTheReading() {
        DemoMediaController media = new DemoMediaController(mApp, mSession);
        media.toggleAudio();
        assertThat(lastMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)).isNull();

        media.setImagesAllowed(true);
        assertThat(lastMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)).isNotNull();

        media.setImagesAllowed(false);
        assertThat(lastMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)).isNull();
        assertThat(lastMetadata().getString(MediaMetadata.METADATA_KEY_TITLE)).isNotNull();
    }

    @Test
    public void aTransientLossPausesAndTheGainResumes() {
        DemoMediaController media = new DemoMediaController(mApp, mSession);
        media.toggleAudio();

        media.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertThat(media.isPlaying()).isFalse();
        assertThat(lastState()).isEqualTo(PlaybackState.STATE_PAUSED);
        assertThat(mAudio.getLastAbandonedAudioFocusRequest()).isNull();

        media.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertThat(media.isPlaying()).isTrue();
        assertThat(lastState()).isEqualTo(PlaybackState.STATE_PLAYING);
    }

    /** onStop's stop() ends the session, and a media-button PLAY then restarts nothing. */
    @Test
    public void afterStopAMediaButtonCannotRestartSound() {
        DemoMediaController media = new DemoMediaController(mApp, mSession);
        ArgumentCaptor<MediaSession.Callback> callback =
                ArgumentCaptor.forClass(MediaSession.Callback.class);
        verify(mSession).setCallback(callback.capture());
        media.toggleAudio();

        media.stop();
        verify(mSession).setActive(false);
        callback.getValue().onPlay();

        assertThat(media.isPlaying()).isFalse();
        assertThat(media.getMode()).isEqualTo(DemoMediaController.Mode.STOPPED);
    }

    @Test
    public void aVideoErrorGivesFocusBack() {
        DemoMediaController media = new DemoMediaController(mApp, mSession);
        VideoView video = new VideoView(mApp);
        media.playVideo(video);
        MediaPlayer player = mock(MediaPlayer.class);
        shadowOf(video).getOnPreparedListener().onPrepared(player);
        assertThat(media.getMode()).isEqualTo(DemoMediaController.Mode.VIDEO);
        AudioFocusRequest request = mAudio.getLastAudioFocusRequest().audioFocusRequest;

        shadowOf(video).getOnErrorListener().onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);

        assertThat(media.getMode()).isEqualTo(DemoMediaController.Mode.STOPPED);
        assertThat(mAudio.getLastAbandonedAudioFocusRequest()).isSameInstanceAs(request);
    }

    @Test
    public void refusedFocusDoesNotStartPlayback() {
        mAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        DemoMediaController media = new DemoMediaController(mApp, mSession);

        media.toggleAudio();

        assertThat(media.isPlaying()).isFalse();
        assertThat(media.getMode()).isEqualTo(DemoMediaController.Mode.STOPPED);
    }

    private MediaMetadata lastMetadata() {
        ArgumentCaptor<MediaMetadata> metadata = ArgumentCaptor.forClass(MediaMetadata.class);
        verify(mSession, atLeastOnce()).setMetadata(metadata.capture());
        List<MediaMetadata> all = metadata.getAllValues();
        return all.get(all.size() - 1);
    }

    private int lastState() {
        ArgumentCaptor<PlaybackState> state = ArgumentCaptor.forClass(PlaybackState.class);
        verify(mSession, atLeastOnce()).setPlaybackState(state.capture());
        List<PlaybackState> all = state.getAllValues();
        return all.get(all.size() - 1).getState();
    }
}
