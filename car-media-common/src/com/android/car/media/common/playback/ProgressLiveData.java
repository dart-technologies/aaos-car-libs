/*
 * Copyright 2018 The Android Open Source Project
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
 *
 * Modified by Dart Technologies, 2026; see FORK.md.
 */

package com.android.car.media.common.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;

import java.util.function.Supplier;

/**
 * Updates current progress from a given {@link PlaybackTiming} while active
 */
class ProgressLiveData extends LiveData<PlaybackProgress> {

    /** How long this LiveData should wait between progress updates */
    @VisibleForTesting
    static final long UPDATE_INTERVAL_MS = 500;

    // The state predicates moved to PlaybackTiming, which is where they can be produced from either
    // a legacy session or a Media3 Player. This file no longer knows what a PlaybackStateCompat is.
    private final PlaybackTiming mTiming;
    private final long mMaxProgress;
    private final Handler mTimerHandler = new Handler(Looper.getMainLooper());
    private final Supplier<Long> mElapsedRealtime;

    ProgressLiveData(@NonNull PlaybackTiming timing, long maxProgress) {
        this(timing, maxProgress, SystemClock::elapsedRealtime);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    ProgressLiveData(
            @NonNull PlaybackTiming timing, long maxProgress,
            Supplier<Long> elapsedRealtime) {
        mTiming = timing;
        mMaxProgress = maxProgress;
        mElapsedRealtime = elapsedRealtime;
    }

    private void updateProgress() {
        setValue(new PlaybackProgress(getProgress(), mMaxProgress));
        if (mTiming.keepsTicking()) {
            mTimerHandler.postDelayed(this::updateProgress, UPDATE_INTERVAL_MS);
        }
    }

    private long getProgress() {
        if (mTiming.position() == PlaybackTiming.POSITION_UNKNOWN) {
            return PlaybackTiming.POSITION_UNKNOWN;
        }
        long timeDiff = mElapsedRealtime.get() - mTiming.lastPositionUpdateTime();
        long posDiff = (long) (timeDiff * mTiming.effectiveSpeed());
        return Math.min(posDiff + mTiming.position(), mMaxProgress);
    }

    @Override
    protected void onActive() {
        super.onActive();
        updateProgress();
    }

    @Override
    protected void onInactive() {
        super.onInactive();
        mTimerHandler.removeCallbacksAndMessages(null);
    }
}
