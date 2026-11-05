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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;

/**
 * Media3 {@link Player} state, expressed in the shape {@code PlaybackStateWrapper} publishes.
 *
 * <h2>What this migration does and does not change</h2>
 *
 * <p>The goal is removing the deprecated <b>client</b> — {@code MediaControllerCompat} and
 * {@code MediaBrowserCompat} — from the production path. It is NOT changing
 * {@code PlaybackStateWrapper}'s public contract, which is legacy-<i>shaped</i>: a
 * {@code @PlaybackStateCompat.State int}, a {@code long} action bitmask, {@code RatingCompat}.
 *
 * <p>Those two are separable and only one of them is safe here. Downstream consumers of this
 * library read that contract, there are <b>no in-repo consumers</b> to validate a new shape
 * against, and this module's seven playback tests are written to it — so changing the surface would
 * rewrite the safety net alongside the code it protects, which is the failure mode this migration
 * has been sequenced to avoid. Reshaping the contract is a deliberate follow-up, not a side effect.
 *
 * <h2>The state mapping is a fan-in, and that is the part to check</h2>
 *
 * <p>Media3 splits what the legacy model carried in one integer: {@code getPlaybackState()} says
 * READY/BUFFERING/IDLE/ENDED, and {@code getPlayWhenReady()} separately says whether READY means
 * playing. So READY maps to two different legacy states and the pair must be read together —
 * reading only {@code getPlaybackState()} would report a paused track as playing, which is the
 * single most likely way to get this wrong.
 *
 * <p>Verified end to end on a device against a real legacy service by
 * {@code Media3BrowseEquivalenceTest}, which asserts a session reporting {@code STATE_PLAYING}
 * arrives as {@code STATE_READY} plus {@code playWhenReady}.
 */
public final class Media3PlaybackState {

    private Media3PlaybackState() {
    }

    /** The legacy state integer for a Media3 player's current state. */
    @PlaybackStateCompat.State
    public static int toLegacyState(@NonNull Player player) {
        if (player.getPlayerError() != null) {
            return PlaybackStateCompat.STATE_ERROR;
        }
        switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING:
                return PlaybackStateCompat.STATE_BUFFERING;
            case Player.STATE_READY:
                // The fan-in. READY alone does not mean playing.
                return player.getPlayWhenReady()
                        ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            case Player.STATE_ENDED:
                return PlaybackStateCompat.STATE_STOPPED;
            case Player.STATE_IDLE:
            default:
                return PlaybackStateCompat.STATE_NONE;
        }
    }

    /**
     * The legacy action bitmask for a Media3 player's available commands.
     *
     * <p>{@code Player.Commands} is a set of capabilities; the legacy model is a bitmask. The
     * mapping is deliberately conservative — a bit is set only when the corresponding command is
     * actually available, because every consumer of {@code getSupportedActions()} uses it to decide
     * whether to OFFER a control. Setting a bit for a command the player will reject produces a
     * button that does nothing, which is worse than a missing one.
     */
    public static long toLegacyActions(@NonNull Player player) {
        long actions = 0L;
        if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            actions |= PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        }
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        }
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        }
        if (player.isCommandAvailable(Player.COMMAND_STOP)) {
            actions |= PlaybackStateCompat.ACTION_STOP;
        }
        if (player.isCommandAvailable(Player.COMMAND_PREPARE)) {
            actions |= PlaybackStateCompat.ACTION_PREPARE;
        }
        // Added 2026-08-18 after measuring the bridge instead of guessing at it. These six were
        // absent from the first version on the reasoning that a bit set for a command the player
        // rejects is worse than a missing one -- sound, but it was never checked whether the bridge
        // reports them faithfully. Media3StateVocabularySurvey answers that directly: with the full
        // legacy vocabulary advertised every one of these commands is available, and with only
        // PLAY|PAUSE advertised every one goes false. They track what the session actually offers,
        // so omitting them was dropping controls a legacy app really does support.
        if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
            actions |= PlaybackStateCompat.ACTION_FAST_FORWARD;
        }
        if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
            actions |= PlaybackStateCompat.ACTION_REWIND;
        }
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM;
        }
        if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            actions |= PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED;
        }
        if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            actions |= PlaybackStateCompat.ACTION_SET_REPEAT_MODE;
        }
        if (player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            actions |= PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
        }
        // ACTION_SET_RATING is absent HERE and only here: rating is a session command, not a
        // Player.Command, so a Player genuinely cannot answer it. It is not lost -- see the
        // MediaController overload below, which can. An earlier version of this comment called it
        // the sole unavoidable loss, which was wrong: unavailable from this argument type is not
        // the same as unavailable.
        return actions;
    }

    /**
     * The legacy action bitmask for a connected {@link MediaController}.
     *
     * <p>Everything {@link #toLegacyActions(Player)} reports, plus {@code ACTION_SET_RATING}, which
     * lives in the session command set rather than in {@code Player.Commands}. Media3 splits the
     * two: transport controls are player commands, and session-scoped verbs like rating are
     * {@link SessionCommand}s reached through {@link MediaController#getAvailableSessionCommands}.
     *
     * <p>Prefer this overload wherever a controller is in hand -- which is everywhere in this
     * module, since {@code MediaBrowser extends MediaController}. The {@link Player} overload
     * remains for a caller that genuinely holds only a player, and the hazard of having both is
     * real and worth stating: a variable whose STATIC type is {@code Player} but which holds a
     * controller silently selects the narrower one and drops the rating bit. Overload resolution is
     * a compile-time decision made on the declared type, not the object.
     */
    public static long toLegacyActions(@NonNull MediaController controller) {
        long actions = toLegacyActions((Player) controller);
        if (controller.getAvailableSessionCommands()
                .contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)) {
            actions |= PlaybackStateCompat.ACTION_SET_RATING;
        }
        return actions;
    }

    /**
     * The whole state, in the shape {@code PlaybackStateWrapper} reads.
     *
     * <p>{@code setState} is given the position and speed the player reports NOW, with
     * {@code SystemClock.elapsedRealtime()} as the update time, because that is the contract
     * {@code ProgressLiveData} extrapolates from — see {@code PlaybackTiming}. Passing a stale
     * update time would make progress jump on every state change.
     */
    @NonNull
    public static PlaybackStateCompat toPlaybackState(@NonNull MediaController controller) {
        return toPlaybackState(controller, toLegacyActions(controller));
    }

    /**
     * The {@link Player}-only form. Reports no rating action -- see
     * {@link #toLegacyActions(MediaController)}.
     */
    @NonNull
    public static PlaybackStateCompat toPlaybackState(@NonNull Player player) {
        return toPlaybackState(player, toLegacyActions(player));
    }

    @NonNull
    private static PlaybackStateCompat toPlaybackState(@NonNull Player player, long actions) {
        float speed = player.getPlaybackParameters().speed;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(toLegacyState(player),
                        player.getCurrentPosition(),
                        speed,
                        android.os.SystemClock.elapsedRealtime());
        PlaybackException error = player.getPlayerError();
        if (error != null) {
            // Media3 error codes are not the legacy ERROR_CODE_* values a media SERVICE reports —
            // see MediaErrorCodes. The message is carried through; the code is left as
            // ERROR_CODE_UNKNOWN_ERROR rather than inventing a translation, so
            // PlaybackErrorsHelper falls back to the generic message instead of naming the wrong
            // cause.
            builder.setErrorMessage(MediaErrorCodesBridge.UNKNOWN, String.valueOf(error.getMessage()));
        }
        return builder.build();
    }

    /** Kept separate so the "no translation" decision above has a name rather than a magic 0. */
    private static final class MediaErrorCodesBridge {
        static final int UNKNOWN = PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR;
    }
}
