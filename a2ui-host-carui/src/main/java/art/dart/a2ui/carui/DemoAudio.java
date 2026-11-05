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

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spoken assistant lines and Fifth House earcons, each played only under transient ducking focus.
 *
 * <p>No focus, no sound: if the user's media cannot be ducked the line is dropped, not spoken over
 * it. Earcons come from assets that only debug builds bundle (see build.gradle) and are silent when
 * absent. Main thread only.
 */
final class DemoAudio implements TextToSpeech.OnInitListener,
        AudioManager.OnAudioFocusChangeListener {

    static final String SFX_ASSETS = "sfx";
    static final String WELCOME = "fifth_house_welcome";
    static final String CONFIRMED = "fifth_house_confirmed";
    static final String FOCUS = "fifth_house_focus";
    static final String ARRIVAL = "fifth_house_arrival";

    private static final String TAG = "A2uiDemoAudio";
    private static final int MAX_SPOKEN_CHARS = 200;
    /** Longest cached earcon is 1.3 s. */
    private static final long EARCON_HOLD_MS = 1_500;
    /** Releases focus if the engine never reports the end of an utterance. */
    private static final long SPEECH_WATCHDOG_MS = 20_000;
    private static final AudioAttributes ASSISTANT = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final AudioFocusRequest mFocus;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> mEarcons = new HashMap<>();
    private final Set<Integer> mLoaded = new HashSet<>();
    /** Streams started in the current earcon window, so quiet() can stop them. */
    private final List<Integer> mStreams = new ArrayList<>();
    private final Runnable mMaybeAbandon = this::maybeAbandon;
    private final Runnable mSpeakPending = this::speakPending;
    private final Runnable mWatchdog = () -> {
        mSpeaking = false;
        maybeAbandon();
    };
    @Nullable private SoundPool mPool;
    @Nullable private TextToSpeech mTts;
    private boolean mTtsReady;
    @Nullable private String mPending;
    @Nullable private String mUtterance;
    private int mUtterances;
    private boolean mSpeaking;
    private long mEarconUntil;
    private boolean mHasFocus;
    private boolean mReleased;

    DemoAudio(@NonNull Context context, @NonNull String sfxAssets) {
        mContext = context.getApplicationContext();
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ASSISTANT)
                .setOnAudioFocusChangeListener(this, mMain)
                .build();
        loadEarcons(sfxAssets);
    }

    /** Speaks one short line; a newer line replaces one not yet spoken. */
    void speak(@Nullable CharSequence text) {
        if (mReleased || text == null || text.toString().trim().isEmpty()) {
            return;
        }
        String line = text.toString().trim();
        mPending = line.length() <= MAX_SPOKEN_CHARS ? line : line.substring(0, MAX_SPOKEN_CHARS);
        if (mTts == null) {
            // Bound on first use: a build that never speaks never binds the engine.
            mTts = new TextToSpeech(mContext, this);
            return;
        }
        if (mTtsReady) {
            speakPending();
        }
    }

    /** Plays a cached earcon; false (silently) when absent, still loading, or focus is refused. */
    boolean earcon(@NonNull String effectId) {
        Integer sound = mEarcons.get(effectId);
        if (mReleased || mPool == null || sound == null || !mLoaded.contains(sound)
                || !acquireFocus()) {
            return false;
        }
        int stream = mPool.play(sound, 1f, 1f, 1, 0, 1f);
        if (stream != 0) {
            mStreams.add(stream);
        }
        mEarconUntil = SystemClock.uptimeMillis() + EARCON_HOLD_MS;
        mMain.removeCallbacks(mMaybeAbandon);
        mMain.postAtTime(mMaybeAbandon, mEarconUntil);
        return true;
    }

    /** Stops speech and earcons and gives focus back; resources stay for the next start. */
    void quiet() {
        mPending = null;
        mMain.removeCallbacks(mSpeakPending);
        if (mTts != null) {
            mTts.stop();
        }
        if (mPool != null) {
            for (int stream : mStreams) {
                mPool.stop(stream);
            }
        }
        mStreams.clear();
        mSpeaking = false;
        mEarconUntil = 0;
        maybeAbandon();
    }

    void release() {
        quiet();
        mReleased = true;
        mMain.removeCallbacksAndMessages(null);
        if (mTts != null) {
            mTts.shutdown();
            mTts = null;
        }
        if (mPool != null) {
            mPool.release();
            mPool = null;
        }
    }

    @VisibleForTesting
    boolean hasEarcons() {
        return mPool != null;
    }

    @VisibleForTesting
    boolean holdsFocus() {
        return mHasFocus;
    }

    /** Stands in a loaded earcon, for tests without bundled SFX. */
    @VisibleForTesting
    void useEarconForTesting(@NonNull SoundPool pool, @NonNull String effectId, int sound) {
        mPool = pool;
        mEarcons.put(effectId, sound);
        mLoaded.add(sound);
    }

    @Override
    public void onInit(int status) {
        if (mReleased || mTts == null) {
            return;
        }
        mTtsReady = status == TextToSpeech.SUCCESS;
        if (!mTtsReady) {
            Log.i(TAG, "no text-to-speech engine; staying silent");
            mPending = null;
            return;
        }
        mTts.setLanguage(Locale.US);
        mTts.setAudioAttributes(ASSISTANT);
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                mMain.post(() -> finished(utteranceId));
            }

            @Override
            public void onError(String utteranceId) {
                mMain.post(() -> finished(utteranceId));
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                mMain.post(() -> finished(utteranceId));
            }
        });
        speakPending();
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Something that matters more (a call, a guidance prompt) now owns the speaker.
            quiet();
        }
    }

    private void speakPending() {
        String line = mPending;
        if (mReleased || line == null || mTts == null || !mTtsReady) {
            return;
        }
        long wait = mEarconUntil - SystemClock.uptimeMillis();
        if (wait > 0) {
            mMain.removeCallbacks(mSpeakPending);
            mMain.postDelayed(mSpeakPending, wait);
            return;
        }
        mPending = null;
        if (!acquireFocus()) {
            Log.i(TAG, "line dropped: no ducking focus");
            return;
        }
        mUtterance = "a2ui-" + (++mUtterances);
        mSpeaking = true;
        mMain.removeCallbacks(mWatchdog);
        mMain.postDelayed(mWatchdog, SPEECH_WATCHDOG_MS);
        if (mTts.speak(line, TextToSpeech.QUEUE_FLUSH, null, mUtterance) != TextToSpeech.SUCCESS) {
            finished(mUtterance);
        }
    }

    private void finished(@Nullable String utteranceId) {
        if (utteranceId != null && utteranceId.equals(mUtterance)) {
            mSpeaking = false;
            mMain.removeCallbacks(mWatchdog);
            maybeAbandon();
        }
    }

    private boolean acquireFocus() {
        if (!mHasFocus) {
            mHasFocus = mAudioManager != null && mAudioManager.requestAudioFocus(mFocus)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return mHasFocus;
    }

    private void maybeAbandon() {
        boolean earconsDone = SystemClock.uptimeMillis() >= mEarconUntil;
        if (earconsDone) {
            mStreams.clear();
        }
        // A line queued behind an earcon keeps the focus it is about to use: no unduck-reduck.
        boolean lineQueued = mPending != null && mTtsReady;
        if (mHasFocus && !mSpeaking && !lineQueued && earconsDone) {
            mHasFocus = false;
            mAudioManager.abandonAudioFocusRequest(mFocus);
        }
    }

    private void loadEarcons(@NonNull String dir) {
        String[] names;
        try {
            names = mContext.getAssets().list(dir);
        } catch (IOException absent) {
            names = null;
        }
        if (names == null || names.length == 0) {
            return;
        }
        SoundPool pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(ASSISTANT)
                .build();
        pool.setOnLoadCompleteListener((loaded, sampleId, status) -> {
            if (status == 0) {
                mLoaded.add(sampleId);
            }
        });
        for (String name : names) {
            if (!name.endsWith(".mp3")) {
                continue;
            }
            try (AssetFileDescriptor fd = mContext.getAssets().openFd(dir + "/" + name)) {
                int sound = pool.load(fd, 1);
                if (sound != 0) {
                    mEarcons.put(name.substring(0, name.length() - 4), sound);
                }
            } catch (IOException | RuntimeException unreadable) {
                Log.i(TAG, "earcon unavailable: " + name);
            }
        }
        if (mEarcons.isEmpty()) {
            pool.release();
        } else {
            mPool = pool;
        }
    }
}
