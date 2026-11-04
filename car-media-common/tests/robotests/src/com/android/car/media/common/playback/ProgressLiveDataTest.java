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
 */

package com.android.car.media.common.playback;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.support.v4.media.session.PlaybackStateCompat;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Observer;

import com.android.car.apps.common.testutils.CaptureObserver;
import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.testutils.TestLifecycleOwner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class ProgressLiveDataTest {
    private static final long START_TIME = 500L;
    private static final long START_PROGRESS = 1000L;
    private static final long MAX_PROGRESS = 100000; // 100 seconds

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule
    public final InstantTaskExecutorRule mTaskExecutorRule = new InstantTaskExecutorRule();
    @Rule
    public final TestLifecycleOwner mLifecycleOwner = new TestLifecycleOwner();

    @Mock
    private PlaybackStateCompat mPlaybackState;
    private long mLastPositionUpdateTime;

    private long mCurrentElapsedTime;
    private ProgressLiveData mProgressLiveData;

    @Before
    public void setUp() {
        ShadowLooper.pauseMainLooper();
        mCurrentElapsedTime = START_TIME;
        mLastPositionUpdateTime = START_TIME;
        when(mPlaybackState.getLastPositionUpdateTime()).thenAnswer(
                invocation -> mLastPositionUpdateTime);
        when(mPlaybackState.getPosition()).thenReturn(START_PROGRESS);
        when(mPlaybackState.getPlaybackSpeed()).thenReturn(1F);
        when(mPlaybackState.getState()).thenReturn(PlaybackStateCompat.STATE_PLAYING);
        // Wrapped, not replaced: the mock is still the thing mutated between ticks, so the
        // existing cases keep pinning that positionAdvances() reads THROUGH rather than freezing.
        //
        // They do NOT pin keepsTicking() — the PAUSED and STOPPED cases assert the progress VALUE
        // and never that the update loop stopped. Mutation testing found that: freezing
        // keepsTicking() left every one of them green. testPausingStopsTheUpdateLoop below covers
        // it, because "the timer keeps running forever after pause" is worth a test on its own
        // terms, not only as mutation bait.
        mProgressLiveData = new ProgressLiveData(PlaybackTiming.from(mPlaybackState), MAX_PROGRESS,
                this::getCurrentElapsedTime);
    }

    private long getCurrentElapsedTime() {
        return mCurrentElapsedTime;
    }

    private void advanceElapsedTime(long time) {
        mCurrentElapsedTime += time;
        ShadowLooper.idleMainLooper(time, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testSetsValueOnActive() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(START_PROGRESS);
    }

    @Test
    public void testUnknownProgress() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        when(mPlaybackState.getPosition())
                .thenReturn(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN);
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN);
    }

    @Test
    public void testMovesForwardAtNormalSpeed() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);
        progressObserver.reset();

        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(
                START_PROGRESS + ProgressLiveData.UPDATE_INTERVAL_MS);
    }

    @Test
    public void testMovesForwardAtCustomSpeed() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);
        float speed = 2F;
        when(mPlaybackState.getPlaybackSpeed()).thenReturn(speed);
        progressObserver.reset();

        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(
                (long) (START_PROGRESS + ProgressLiveData.UPDATE_INTERVAL_MS * speed));
    }

    @Test
    public void testDoesntMoveForwardWhenPaused() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);
        when(mPlaybackState.getState()).thenReturn(PlaybackStateCompat.STATE_PAUSED);
        progressObserver.reset();

        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(
                START_PROGRESS);
    }

    @Test
    public void testPausingStopsTheUpdateLoop() {
        // Distinct from testDoesntMoveForwardWhenPaused, which asserts the VALUE. This asserts the
        // LOOP: after pausing, updateProgress must stop re-posting rather than keep firing with an
        // unchanged number. Counted over several intervals, because a single interval cannot tell
        // "posted once more then stopped" from "still posting".
        CountingObserver observer = new CountingObserver();
        mProgressLiveData.observe(mLifecycleOwner, observer);
        when(mPlaybackState.getState()).thenReturn(PlaybackStateCompat.STATE_PAUSED);

        // One tick to deliver the paused state, then let plenty more time pass.
        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);
        int afterPauseDelivered = observer.mCount;
        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS * 5);

        // A live keepsTicking() stops the loop, so nothing further arrives. A frozen one keeps
        // posting every interval — five more notifications carrying the same value.
        assertThat(observer.mCount).isEqualTo(afterPauseDelivered);
    }

    /** Counts notifications; CaptureObserver only records the latest value. */
    private static final class CountingObserver implements Observer<PlaybackProgress> {
        int mCount;

        @Override
        public void onChanged(PlaybackProgress value) {
            mCount++;
        }
    }

    @Test
    public void testDoesntMoveForwardWhenStopped() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);
        when(mPlaybackState.getState()).thenReturn(PlaybackStateCompat.STATE_STOPPED);
        progressObserver.reset();

        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);

        assertThat(progressObserver.hasBeenNotified()).isTrue();
        assertThat(progressObserver.getObservedValue().getProgress()).isEqualTo(
                START_PROGRESS);
    }

    @Test
    public void testDoesntUpdateWhenInactive() {
        CaptureObserver<PlaybackProgress> progressObserver = new CaptureObserver<>();
        mProgressLiveData.observe(mLifecycleOwner, progressObserver);
        mLifecycleOwner.markState(Lifecycle.State.DESTROYED);
        progressObserver.reset();

        advanceElapsedTime(ProgressLiveData.UPDATE_INTERVAL_MS);

        assertThat(progressObserver.hasBeenNotified()).isFalse();
    }
}
