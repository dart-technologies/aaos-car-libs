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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.support.v4.media.session.PlaybackStateCompat;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.MediaController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The Media3 → legacy-shape conversion, which is where this migration is most likely to be wrong.
 *
 * <p>The device-level equivalence in {@code Media3BrowseEquivalenceTest} proves a real legacy
 * session and a real Media3 client agree. This proves the CONVERSION agrees, over states a single
 * fixture session cannot be put into — IDLE, ENDED, error, and every command combination.
 */
@RunWith(RobolectricTestRunner.class)
public class Media3PlaybackStateTest {

    private Player mPlayer;

    @Before
    public void setUp() {
        mPlayer = mock(Player.class);
        when(mPlayer.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
        when(mPlayer.getCurrentPosition()).thenReturn(0L);
    }

    // ===========================================================================================
    // The fan-in. Media3 splits across getPlaybackState() + getPlayWhenReady() what the legacy
    // model carried in one integer, so READY maps to TWO legacy states.
    // ===========================================================================================

    @Test
    public void readyAndPlayWhenReadyIsPLAYING() {
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mPlayer.getPlayWhenReady()).thenReturn(true);

        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_PLAYING);
    }

    /** THE one. Reading only getPlaybackState() reports a paused track as playing. */
    @Test
    public void readyWithoutPlayWhenReadyIsPAUSED_notPlaying() {
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mPlayer.getPlayWhenReady()).thenReturn(false);

        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_PAUSED);
    }

    @Test
    public void bufferingIdleAndEndedMapAcrossUnambiguously() {
        when(mPlayer.getPlayWhenReady()).thenReturn(true);

        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_BUFFERING);
        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_BUFFERING);

        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_IDLE);
        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_NONE);

        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_ENDED);
        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_STOPPED);
    }

    @Test
    public void anErrorWinsOverTheStateItIsReportedAlongside() {
        // A player can hold an error while still reporting READY. Reporting PLAYING there would
        // put a progress bar on a track that is not going to play.
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mPlayer.getPlayWhenReady()).thenReturn(true);
        when(mPlayer.getPlayerError()).thenReturn(
                new PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED));

        assertThat(Media3PlaybackState.toLegacyState(mPlayer))
                .isEqualTo(PlaybackStateCompat.STATE_ERROR);
    }

    // ===========================================================================================
    // Commands -> bitmask. Every consumer uses this to decide whether to OFFER a control, so a bit
    // set for an unavailable command is a button that does nothing.
    // ===========================================================================================

    @Test
    public void noCommandsMeansNoActions() {
        assertThat(Media3PlaybackState.toLegacyActions(mPlayer)).isEqualTo(0L);
    }

    @Test
    public void playPauseSetsAllThreeLegacyBits() {
        when(mPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).thenReturn(true);

        long actions = Media3PlaybackState.toLegacyActions(mPlayer);

        assertThat(actions & PlaybackStateCompat.ACTION_PLAY).isNotEqualTo(0);
        assertThat(actions & PlaybackStateCompat.ACTION_PAUSE).isNotEqualTo(0);
        assertThat(actions & PlaybackStateCompat.ACTION_PLAY_PAUSE).isNotEqualTo(0);
        // And nothing it did not ask for.
        assertThat(actions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT).isEqualTo(0);
        assertThat(actions & PlaybackStateCompat.ACTION_SEEK_TO).isEqualTo(0);
    }

    @Test
    public void eachSeekCommandSetsOnlyItsOwnBit() {
        when(mPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)).thenReturn(true);
        assertThat(Media3PlaybackState.toLegacyActions(mPlayer))
                .isEqualTo(PlaybackStateCompat.ACTION_SKIP_TO_NEXT);

        mPlayer = mock(Player.class);
        when(mPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                .thenReturn(true);
        assertThat(Media3PlaybackState.toLegacyActions(mPlayer))
                .isEqualTo(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);

        mPlayer = mock(Player.class);
        when(mPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                .thenReturn(true);
        assertThat(Media3PlaybackState.toLegacyActions(mPlayer))
                .isEqualTo(PlaybackStateCompat.ACTION_SEEK_TO);
    }

    @Test
    public void stopAndPrepareMapAcross() {
        when(mPlayer.isCommandAvailable(Player.COMMAND_STOP)).thenReturn(true);
        when(mPlayer.isCommandAvailable(Player.COMMAND_PREPARE)).thenReturn(true);

        long actions = Media3PlaybackState.toLegacyActions(mPlayer);

        assertThat(actions & PlaybackStateCompat.ACTION_STOP).isNotEqualTo(0);
        assertThat(actions & PlaybackStateCompat.ACTION_PREPARE).isNotEqualTo(0);
    }

    // ===========================================================================================
    // The assembled state.
    // ===========================================================================================

    @Test
    public void theAssembledStateCarriesPositionAndSpeed() {
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mPlayer.getPlayWhenReady()).thenReturn(true);
        when(mPlayer.getCurrentPosition()).thenReturn(4_200L);
        when(mPlayer.getPlaybackParameters()).thenReturn(new PlaybackParameters(1.5f));
        when(mPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).thenReturn(true);

        PlaybackStateCompat state = Media3PlaybackState.toPlaybackState(mPlayer);

        assertThat(state.getState()).isEqualTo(PlaybackStateCompat.STATE_PLAYING);
        assertThat(state.getPosition()).isEqualTo(4_200L);
        assertThat(state.getPlaybackSpeed()).isEqualTo(1.5f);
        assertThat(state.getActions() & PlaybackStateCompat.ACTION_PLAY).isNotEqualTo(0);
    }

    @Test
    public void anErrorCarriesItsMessageButNotAnInventedCode() {
        // Media3 error codes are not the legacy ERROR_CODE_* values a media SERVICE reports, so
        // translating them would name the wrong cause. PlaybackErrorsHelper then falls back to the
        // generic message, which is the honest outcome.
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_IDLE);
        when(mPlayer.getPlayerError()).thenReturn(
                new PlaybackException("network gone", null,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));

        PlaybackStateCompat state = Media3PlaybackState.toPlaybackState(mPlayer);

        assertThat(state.getState()).isEqualTo(PlaybackStateCompat.STATE_ERROR);
        assertThat(String.valueOf(state.getErrorMessage())).contains("network gone");
        assertThat(state.getErrorCode())
                .isEqualTo(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR);
    }

    @Test
    public void theUpdateTimeIsNowSoProgressDoesNotJump() {
        // ProgressLiveData extrapolates from lastPositionUpdateTime. A stale one makes progress
        // leap on every state change; see PlaybackTiming.
        when(mPlayer.getPlaybackState()).thenReturn(Player.STATE_READY);
        when(mPlayer.getPlayWhenReady()).thenReturn(true);

        long before = android.os.SystemClock.elapsedRealtime();
        PlaybackStateCompat state = Media3PlaybackState.toPlaybackState(mPlayer);
        long after = android.os.SystemClock.elapsedRealtime();

        assertThat(state.getLastPositionUpdateTime()).isAtLeast(before);
        assertThat(state.getLastPositionUpdateTime()).isAtMost(after);
    }

    /**
     * The rating bit comes from the SESSION commands, and only the controller overload sees them.
     *
     * <p>Both directions, because the pair is the point: an audit found ACTION_SET_RATING recorded
     * as an unavoidable loss when it was merely unavailable from the argument type that had been
     * chosen. A test asserting only that the controller reports it would not have caught the
     * reverse mistake -- reporting it from a controller whose session never offered it.
     */
    @Test
    public void ratingIsReadFromTheSessionCommandsAndOnlyByTheControllerOverload() {
        MediaController withRating = mock(MediaController.class);
        when(withRating.getAvailableSessionCommands()).thenReturn(
                new SessionCommands.Builder()
                        .add(new SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING))
                        .build());

        assertThat(Media3PlaybackState.toLegacyActions(withRating)
                & PlaybackStateCompat.ACTION_SET_RATING).isNotEqualTo(0);
        assertThat(Media3PlaybackState.toLegacyActions((Player) withRating)
                & PlaybackStateCompat.ACTION_SET_RATING).isEqualTo(0);
    }

    @Test
    public void noRatingIsReportedWhenTheSessionDoesNotOfferIt() {
        MediaController without = mock(MediaController.class);
        when(without.getAvailableSessionCommands()).thenReturn(SessionCommands.EMPTY);

        assertThat(Media3PlaybackState.toLegacyActions(without)
                & PlaybackStateCompat.ACTION_SET_RATING).isEqualTo(0);
    }
}
