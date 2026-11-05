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
package com.android.car.media.common.playback;

import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Everything progress needs to know about playback, and nothing else.
 *
 * <h2>Why this exists — Media3 migration, file 1</h2>
 *
 * <p>{@code ProgressLiveData} took a {@code PlaybackStateCompat} and used exactly four things from
 * it: the state, the speed, the position and the last position update time. Taking the whole compat
 * object made a 106-line progress calculator part of the 158-reference legacy surface for no reason
 * — it does not browse, it does not control, it extrapolates a number.
 *
 * <p>Narrowing it to the four values it reads is the first step of the migration that is actually a
 * migration rather than a preparation: {@code PlaybackStateCompat} is now absent from
 * {@code ProgressLiveData} entirely. The same four values come out of Media3's {@code Player}
 * ({@code getCurrentPosition}, {@code getPlaybackParameters().speed}, and the state pair
 * {@code getPlaybackState}/{@code getPlayWhenReady}), so when the playback half moves, this file
 * does not move again.
 *
 * <h2>Two predicates, not one, and they are NOT the same set</h2>
 *
 * <p>The original code checked the state twice with different meanings, and collapsing them would
 * be a silent behaviour change:
 *
 * <ul>
 *   <li>{@link #keepsTicking()} — whether the update timer should re-post. The original condition
 *       is {@code state != PAUSED && state != STOPPED && speed != 0}: only two states stop the
 *       clock.
 *   <li>{@link #positionAdvances()} — whether the extrapolation should move at all. The original
 *       condition is membership of a NINE-state set which additionally includes NONE, BUFFERING,
 *       ERROR, CONNECTING and the three SKIPPING states.
 * </ul>
 *
 * <p>So a buffering track keeps its timer running and does not advance its position. Both are
 * preserved exactly; the comment on the original set — that it guards against apps which do not
 * keep playbackSpeed to spec (b/62375164) — is why the wider set exists.
 *
 * <h2>A live view, not a snapshot, and that distinction was nearly a silent behaviour change</h2>
 *
 * <p>The first version of this class captured the four values at construction. In production that
 * is indistinguishable — {@code PlaybackStateCompat} is immutable and {@code PlaybackViewModel}
 * builds a fresh {@code ProgressLiveData} per state — but it is <b>not</b> what the original code
 * did: {@code updateProgress} re-read {@code getState()} and {@code getPlaybackSpeed()} on every
 * tick.
 *
 * <p>{@code ProgressLiveDataTest} caught it, by mutating a mocked state between ticks — a scenario
 * the real immutable type cannot produce, but one that pins the re-read. Reading through on every
 * call preserves the original semantics exactly, and a migration is the wrong moment to change them
 * on the grounds that nothing currently notices.
 */
public abstract class PlaybackTiming {

    /** Same sentinel the compat type uses, so callers comparing against it do not change. */
    public static final long POSITION_UNKNOWN = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;

    /**
     * States whose position must not be extrapolated.
     *
     * <p>Guards against apps that do not keep their playbackSpeed to spec (b/62375164) — an app
     * reporting BUFFERING at speed 1.0 would otherwise have its progress bar run away.
     */
    @VisibleForTesting
    static final Set<Integer> NO_PROGRESS_STATES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    PlaybackStateCompat.STATE_NONE,
                    PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.STATE_BUFFERING,
                    PlaybackStateCompat.STATE_ERROR,
                    PlaybackStateCompat.STATE_CONNECTING,
                    PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
                    PlaybackStateCompat.STATE_SKIPPING_TO_NEXT,
                    PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM)));

    private PlaybackTiming() {
    }

    public abstract long position();

    public abstract long lastPositionUpdateTime();

    /** The speed to extrapolate with — zero when {@link #positionAdvances()} is false. */
    public float effectiveSpeed() {
        return positionAdvances() ? speed() : 0f;
    }

    protected abstract float speed();

    /** Whether the progress timer should re-post. See the class note: NOT the same as below. */
    public abstract boolean keepsTicking();

    /** Whether the position should be extrapolated at all. See the class note. */
    public abstract boolean positionAdvances();

    /**
     * A live view over the legacy session state — the only conversion site, and it stays until the
     * playback half flips. Every accessor reads THROUGH, preserving the original per-tick re-read.
     */
    @NonNull
    public static PlaybackTiming from(@NonNull PlaybackStateCompat state) {
        return new PlaybackTiming() {
            @Override
            public long position() {
                return state.getPosition();
            }

            @Override
            public long lastPositionUpdateTime() {
                return state.getLastPositionUpdateTime();
            }

            @Override
            protected float speed() {
                return state.getPlaybackSpeed();
            }

            @Override
            public boolean keepsTicking() {
                return state.getState() != PlaybackStateCompat.STATE_PAUSED
                        && state.getState() != PlaybackStateCompat.STATE_STOPPED
                        && state.getPlaybackSpeed() != 0;
            }

            @Override
            public boolean positionAdvances() {
                return !NO_PROGRESS_STATES.contains(state.getState());
            }
        };
    }
}
