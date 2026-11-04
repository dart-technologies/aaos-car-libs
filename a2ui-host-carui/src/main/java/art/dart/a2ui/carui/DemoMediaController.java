/*
 * Copyright (C) 2026 Dart Technologies
 * Licensed under the Apache License, Version 2.0.
 */
package art.dart.a2ui.carui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import art.dart.a2ui.model.ComponentType;

import java.util.Set;

/** Offline, process-local media fixture used only by Host B's recorded demo corpus. */
final class DemoMediaController implements AudioManager.OnAudioFocusChangeListener {

    enum Mode { STOPPED, AUDIO, VIDEO }

    private static final float DUCKED_VOLUME = 0.2f;

    private final Context mContext;
    private final MediaSession mSession;
    @Nullable private final AudioManager mAudioManager;
    private final AudioFocusRequest mFocusRequest;
    @Nullable private MediaPlayer mAudio;
    @Nullable private VideoView mVideo;
    @Nullable private Bitmap mArt;
    private Mode mMode = Mode.STOPPED;
    private boolean mHasFocus;
    private boolean mResumeOnGain;
    /** Host-started playback activates the session; stop() ends it, so no media button can. */
    private boolean mActive;
    /** Album art is an image: published only while the reading allows IMAGE. */
    private boolean mImagesAllowed;
    /** Duration last published, or -1 before anything played. */
    private long mDurationMs = -1;

    DemoMediaController(@NonNull Context context) {
        this(context, new MediaSession(context, "A2uiDemoMedia"));
    }

    @VisibleForTesting
    DemoMediaController(@NonNull Context context, @NonNull MediaSession session) {
        mContext = context.getApplicationContext();
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper()))
                .build();
        mSession = session;
        mSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stop(); }
        });
        publish(PlaybackState.STATE_STOPPED, 0);
    }

    Mode getMode() {
        return mMode;
    }

    /** Starts or pauses the generated local audio fixture. No network or storage access occurs. */
    void toggleAudio() {
        if (mMode == Mode.AUDIO && mAudio != null && mAudio.isPlaying()) {
            pause();
            return;
        }
        stopVideo();
        activate();
        if (mAudio == null) {
            mAudio = MediaPlayer.create(mContext, R.raw.demo_placeholder_audio);
            if (mAudio == null) {
                publish(PlaybackState.STATE_ERROR, 0);
                return;
            }
            // Looped: the fixture is tiny, and a 60–90 s walkthrough must show audio surviving a
            // UXR transition rather than a four-second completion.
            mAudio.setLooping(true);
            mAudio.setOnCompletionListener(ignored -> {
                mMode = Mode.STOPPED;
                publish(PlaybackState.STATE_STOPPED, 0);
            });
        }
        if (!requestFocus()) {
            publish(PlaybackState.STATE_PAUSED, 0);
            return;
        }
        mAudio.setVolume(1f, 1f);
        mAudio.start();
        mMode = Mode.AUDIO;
        publishMetadata(mAudio.getDuration());
        publish(PlaybackState.STATE_PLAYING, 1);
    }

    /** Plays the generated local video fixture in the supplied parked-only native view. */
    void playVideo(@NonNull VideoView video) {
        playVideo(video, R.raw.demo_placeholder_video);
    }

    /** Plays a bundled video resource in the supplied parked-only native view. */
    void playVideo(@NonNull VideoView video, int rawResource) {
        stop();
        activate();
        mVideo = video;
        // This controller owns focus for both modes, so the view must not take its own.
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        video.setVideoURI(Uri.parse("android.resource://" + mContext.getPackageName()
                + "/" + rawResource));
        video.setOnPreparedListener(player -> {
            if (mVideo != video || !requestFocus()) {
                return;
            }
            player.setLooping(true);
            mMode = Mode.VIDEO;
            video.start();
            publishMetadata(player.getDuration());
            publish(PlaybackState.STATE_PLAYING, 1);
        });
        video.setOnCompletionListener(player -> {
            mMode = Mode.STOPPED;
            publish(PlaybackState.STATE_STOPPED, 0);
        });
        video.setOnErrorListener((player, what, extra) -> {
            mMode = Mode.STOPPED;
            publish(PlaybackState.STATE_ERROR, 0);
            abandonFocus();
            return true;
        });
    }

    void pause() {
        pausePlayback();
        mResumeOnGain = false;
        abandonFocus();
    }

    private void pausePlayback() {
        if (mMode == Mode.AUDIO && mAudio != null && mAudio.isPlaying()) {
            mAudio.pause();
        } else if (mMode == Mode.VIDEO && mVideo != null && mVideo.isPlaying()) {
            mVideo.pause();
        }
        publish(PlaybackState.STATE_PAUSED, 0);
    }

    private void resume() {
        if (!mActive) {
            return; // stopped by the host: a media button must not restart sound behind it
        }
        if (mMode == Mode.VIDEO && mVideo != null) {
            if (requestFocus()) {
                mVideo.start();
                publish(PlaybackState.STATE_PLAYING, 1);
            }
        } else if (mMode == Mode.AUDIO && mAudio != null) {
            if (requestFocus()) {
                mAudio.start();
                publish(PlaybackState.STATE_PLAYING, 1);
            }
        } else {
            toggleAudio();
        }
    }

    /** Stops video immediately while leaving a separately requested audio session possible. */
    void stopVideo() {
        if (mVideo != null) {
            mVideo.stopPlayback();
            mVideo = null;
        }
        if (mMode == Mode.VIDEO) {
            mMode = Mode.STOPPED;
            publish(PlaybackState.STATE_STOPPED, 0);
            abandonFocus();
        }
    }

    void stop() {
        stopVideo();
        if (mAudio != null) {
            mAudio.stop();
            mAudio.release();
            mAudio = null;
        }
        mMode = Mode.STOPPED;
        mResumeOnGain = false;
        abandonFocus();
        publish(PlaybackState.STATE_STOPPED, 0);
        if (mActive) {
            mActive = false;
            mSession.setActive(false);
        }
    }

    /** Republishes the metadata with or without art when the reading's IMAGE answer changes. */
    void setImagesAllowed(boolean allowed) {
        if (allowed == mImagesAllowed) {
            return;
        }
        mImagesAllowed = allowed;
        if (mDurationMs >= 0) {
            publishMetadata(mDurationMs);
        }
    }

    @VisibleForTesting
    boolean imagesAllowed() {
        return mImagesAllowed;
    }

    /** True when a UXR transition must tear down video pixels and decoding. */
    static boolean shouldStopVideo(@NonNull Set<ComponentType> allowed, @NonNull Mode mode) {
        return mode == Mode.VIDEO && !allowed.contains(ComponentType.VIDEO);
    }

    void release() {
        stop();
        mSession.release();
    }

    /** Pause on loss; duck for a transient ducking request; resume only what a transient loss paused. */
    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mAudio != null) {
                    mAudio.setVolume(1f, 1f);
                }
                if (mResumeOnGain) {
                    mResumeOnGain = false;
                    resume();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mAudio != null) {
                    mAudio.setVolume(DUCKED_VOLUME, DUCKED_VOLUME);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                mResumeOnGain = isPlaying();
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            default:
                pause();
                break;
        }
    }

    @VisibleForTesting
    @NonNull
    AudioFocusRequest focusRequestForTesting() {
        return mFocusRequest;
    }

    @VisibleForTesting
    boolean isPlaying() {
        return (mMode == Mode.AUDIO && mAudio != null && mAudio.isPlaying())
                || (mMode == Mode.VIDEO && mVideo != null && mVideo.isPlaying());
    }

    private boolean requestFocus() {
        if (!mHasFocus) {
            mHasFocus = mAudioManager != null && mAudioManager.requestAudioFocus(mFocusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return mHasFocus;
    }

    private void abandonFocus() {
        if (mHasFocus) {
            mHasFocus = false;
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
        }
    }

    private void activate() {
        if (!mActive) {
            mActive = true;
            mSession.setActive(true);
        }
    }

    private void publishMetadata(long durationMs) {
        mDurationMs = Math.max(0, durationMs);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        mContext.getString(R.string.a2ui_brand_media_title))
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        mContext.getString(R.string.a2ui_brand_media_artist))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, mDurationMs);
        if (mImagesAllowed) {
            if (mArt == null) {
                mArt = BitmapFactory.decodeResource(mContext.getResources(),
                        R.drawable.demo_album_art);
            }
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, mArt);
        }
        mSession.setMetadata(metadata.build());
    }

    private void publish(int state, float speed) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        mSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed).build());
    }
}
