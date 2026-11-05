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
package com.android.car.media.common.source;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.media3.common.Player;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.media.common.playback.Media3PlaybackState;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PINS what Media3's legacy bridge does with the full legacy state and action vocabulary.
 *
 * <p>This is an instrument, not an assertion of intent. The audit's finding is that
 * {@code Media3PlaybackState.toLegacyState()} can emit only six of the legacy states while
 * {@code PlaybackStateWrapper} still publishes and interprets eleven, and that the action bitmask is
 * similarly narrowed. Both halves of that are questions about what Media3 ACTUALLY EXPOSES when
 * talking to a real legacy session over a real binder — not about what this repository's conversion
 * chooses to do with it. A conversion cannot recover something the bridge never delivered, and it
 * should not be extended to fabricate something it did.
 *
 * <p>So the table is read off the device first, logged under {@code M3VOCAB}, and only then turned
 * into assertions. Writing the assertions first would have encoded a guess about someone else's
 * library as if it were a measurement — which is exactly the error the finding is about.
 *
 * <p>Synchronisation is by polling rather than by listener, deliberately: several legacy states are
 * expected to collapse onto the same Media3 signal, and a listener-and-latch scheme would time out
 * on precisely those cases and report "no answer" where the answer is "identical to the previous
 * one". Polling reads the value either way.
 */
@RunWith(AndroidJUnit4.class)
public class Media3CompatibilityDeltaTest {

    private static final String TAG = "M3VOCAB";
    private static final long TIMEOUT_SECONDS = 15;
    private static final long SETTLE_MS = 600;

    /** Every legacy state PlaybackStateWrapper can be handed. */
    private static final int[] LEGACY_STATES = {
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.STATE_STOPPED,
            PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_FAST_FORWARDING,
            PlaybackStateCompat.STATE_REWINDING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_ERROR,
            PlaybackStateCompat.STATE_CONNECTING,
            PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackStateCompat.STATE_SKIPPING_TO_NEXT,
            PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM,
    };

    private static final long ALL_ACTIONS =
            PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_REWIND
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_FAST_FORWARD
            | PlaybackStateCompat.ACTION_SET_RATING
            | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            | PlaybackStateCompat.ACTION_PLAY_FROM_URI
            | PlaybackStateCompat.ACTION_PREPARE
            | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            | PlaybackStateCompat.ACTION_PREPARE_FROM_URI
            | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            | PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED
            | PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED;

    private Context mContext;
    private MediaBrowser mBrowser;

    @Before
    public void connect() throws Exception {
        mContext = getInstrumentation().getContext();
        ComponentName component = new ComponentName(mContext, FakeLegacyBrowseService.class);
        AtomicReference<ListenableFuture<MediaBrowser>> future = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            SessionToken token = new SessionToken(mContext, component);
            future.set(new MediaBrowser.Builder(mContext, token).buildAsync());
        });
        mBrowser = future.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(mBrowser).isNotNull();
        assertThat(FakeLegacyBrowseService.instance()).isNotNull();
    }

    @After
    public void release() {
        if (mBrowser != null) {
            getInstrumentation().runOnMainSync(() -> mBrowser.release());
        }
    }

    /**
     * The five transitional states all collapse onto BUFFERING — and that is why the collapse is
     * mostly harmless.
     *
     * <p>Measured, not assumed: FAST_FORWARDING (speed 2.0), REWINDING (-2.0) and all three
     * SKIPPING states arrive as {@code Player.STATE_BUFFERING} with playWhenReady set, so
     * {@code toLegacyState} returns STATE_BUFFERING for each. Both consumers of the vocabulary
     * already group BUFFERING with them: {@code PlaybackViewModel.isLoading()} tests all six
     * together, and {@code getMainAction()} puts all six in the same branch. So the distinction is
     * lost and the BEHAVIOUR is not.
     *
     * <p>This corrects a claim made earlier in this migration from reading the switch statement
     * alone: that a legacy app reporting a SKIPPING state would stop reading as loading. It does
     * not, because it does not arrive as a SKIPPING state — it arrives as BUFFERING.
     */
    @Test
    public void theFiveTransitionalStatesCollapseOntoBufferingAndStayLoading() {
        int[] transitional = {
                PlaybackStateCompat.STATE_FAST_FORWARDING,
                PlaybackStateCompat.STATE_REWINDING,
                PlaybackStateCompat.STATE_SKIPPING_TO_NEXT,
                PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM,
        };
        for (int legacy : transitional) {
            float speed = (legacy == PlaybackStateCompat.STATE_FAST_FORWARDING) ? 2.0f
                    : (legacy == PlaybackStateCompat.STATE_REWINDING) ? -2.0f : 1.0f;
            publishAndSettle(legacy, ALL_ACTIONS, 4_200L, speed);
            assertWithMessage("legacy %s round-tripped", nameOfState(legacy))
                    .that(nameOfState(roundTrip())).isEqualTo("BUFFERING");
        }
    }

    /**
     * CONNECTING is the one state whose loss actually changes behaviour, and it is unavoidable.
     *
     * <p>It arrives as {@code STATE_IDLE} with no error and playWhenReady false — indistinguishable
     * from a session that published NONE — so {@code toLegacyState} answers NONE. NONE is absent
     * from {@code isLoading()}'s set and sits in {@code getMainAction()}'s "offer play" branch, so
     * during a legacy app's CONNECTING the spinner stops and the button shows play instead of pause.
     *
     * <p>Nothing in the conversion can recover it: the bridge delivers no signal that separates
     * CONNECTING from NONE. Recording it as an unavoidable loss is the honest outcome.
     */
    @Test
    public void connectingIsIndistinguishableFromNoneAndThatIsTheRealLoss() {
        publishAndSettle(PlaybackStateCompat.STATE_CONNECTING, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("NONE");

        publishAndSettle(PlaybackStateCompat.STATE_NONE, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("NONE");
    }

    /**
     * STOPPED also collapses to NONE, and this one is benign.
     *
     * <p>{@code toLegacyState} maps {@code STATE_ENDED} to STOPPED, but the legacy bridge never
     * produces ENDED for a legacy session — a stopped session arrives as IDLE — so STOPPED is
     * unreachable from a legacy source. Both consumers treat STOPPED and NONE identically
     * ({@code getMainAction()} groups them; neither is in {@code isLoading()}), so the value changes
     * and no behaviour does.
     */
    @Test
    public void stoppedCollapsesToNoneAndBothConsumersTreatThemAlike() {
        publishAndSettle(PlaybackStateCompat.STATE_STOPPED, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("NONE");
    }

    /** The four states that survive intact, so the losses above are not the whole story. */
    @Test
    public void playingPausedBufferingAndErrorRoundTripUnchanged() {
        publishAndSettle(PlaybackStateCompat.STATE_PLAYING, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("PLAYING");
        publishAndSettle(PlaybackStateCompat.STATE_PAUSED, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("PAUSED");
        publishAndSettle(PlaybackStateCompat.STATE_BUFFERING, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("BUFFERING");
        publishAndSettle(PlaybackStateCompat.STATE_ERROR, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(nameOfState(roundTrip())).isEqualTo("ERROR");
    }

    /**
     * Every advertised action Media3 can express survives the conversion — SET_RATING excepted.
     *
     * <p>This is what the widened {@code toLegacyActions} is for. Before it, FAST_FORWARD, REWIND,
     * SKIP_TO_QUEUE_ITEM, SET_PLAYBACK_SPEED, SET_REPEAT_MODE and SET_SHUFFLE_MODE were dropped
     * even though the bridge reports every one of them.
     */
    /**
     * The exact loss set, over the WHOLE vocabulary, with each entry accounted for.
     *
     * <p>This assertion replaces one that ranged over fifteen of the twenty-two advertised actions
     * and concluded SET_RATING was the sole loss. Both halves of that were wrong. SET_RATING is not
     * lost at all -- it is a session command rather than a player command, so
     * {@code toLegacyActions(Player)} cannot see it and {@code toLegacyActions(MediaController)}
     * can. And seven actions the fixture publishes were simply never asked about.
     *
     * <p>The seven that ARE lost divide into two kinds, and the difference matters to anyone
     * reading this as a capability list:
     *
     * <ul>
     *   <li><b>Six collapsed ADDRESSING FORMS</b> -- PLAY_FROM and PREPARE_FROM, each in
     *       MEDIA_ID/SEARCH/URI. Media3 has no "play from a media id" verb; it has
     *       {@code setMediaItem} followed by {@code prepare}/{@code play}. The capability survives,
     *       the legacy way of naming it does not. {@link #theCollapsedFormsHaveAMedia3Equivalent}
     *       measures that claim instead of asserting it.
     *   <li><b>One genuinely different model</b> -- SET_CAPTIONING_ENABLED. Media3 expresses
     *       captions through track selection, not a playback action.
     * </ul>
     */
    @Test
    public void theLossSetIsExactlyTheCollapsedFormsAndCaptioning() {
        publishAndSettle(PlaybackStateCompat.STATE_PLAYING, ALL_ACTIONS, 4_200L, 1.0f);
        long roundTripped = actionsRoundTrip();

        List<String> missing = new ArrayList<>();
        for (long action : ACTIONS_OF_INTEREST) {
            if ((roundTripped & action) == 0) {
                missing.add(nameOfAction(action));
            }
        }
        assertThat(missing).containsExactly(
                "ACTION_PLAY_FROM_MEDIA_ID",
                "ACTION_PLAY_FROM_SEARCH",
                "ACTION_PLAY_FROM_URI",
                "ACTION_PREPARE_FROM_MEDIA_ID",
                "ACTION_PREPARE_FROM_SEARCH",
                "ACTION_PREPARE_FROM_URI",
                "ACTION_SET_CAPTIONING_ENABLED");
    }

    /**
     * SET_RATING survives -- through the controller, and only through the controller.
     *
     * <p>Both halves are asserted because the difference between them IS the finding: the Player
     * overload cannot answer a session command, so a caller holding a {@code Player}-typed variable
     * silently loses the bit even when the object behind it is a controller.
     */
    @Test
    public void ratingSurvivesThroughTheControllerAndNotThroughThePlayer() {
        publishAndSettle(PlaybackStateCompat.STATE_PLAYING, ALL_ACTIONS, 4_200L, 1.0f);

        long[] viaController = new long[1];
        long[] viaPlayer = new long[1];
        getInstrumentation().runOnMainSync(() -> {
            viaController[0] = Media3PlaybackState.toLegacyActions((MediaController) mBrowser);
            viaPlayer[0] = Media3PlaybackState.toLegacyActions((Player) mBrowser);
        });

        assertThat(viaController[0] & PlaybackStateCompat.ACTION_SET_RATING).isNotEqualTo(0);
        assertThat(viaPlayer[0] & PlaybackStateCompat.ACTION_SET_RATING).isEqualTo(0);
    }

    /**
     * The six collapsed forms have a Media3 equivalent available, so they are a naming change
     * rather than a lost capability.
     *
     * <p>Without this the classification above would be an assertion about someone else's library
     * made from its documentation. If these commands were NOT available, "collapsed form" would be
     * the wrong description and the honest word would be "lost".
     */
    @Test
    public void theCollapsedFormsHaveAMedia3Equivalent() {
        publishAndSettle(PlaybackStateCompat.STATE_PLAYING, ALL_ACTIONS, 4_200L, 1.0f);

        // play/prepare FROM {media id, search, uri} all become: set an item, then prepare or play.
        assertThat(commandAvailable(Player.COMMAND_SET_MEDIA_ITEM)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_PREPARE)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue();
    }

    /**
     * THE CONTROL. Commands track what the session advertised; they are not granted wholesale.
     *
     * <p>Without this the previous test proves nothing: if the bridge reported every command
     * available regardless, widening the bitmask would advertise controls the app never offered,
     * and a button that does nothing is worse than a missing one. Measured both ways — full
     * vocabulary and PLAY|PAUSE only.
     */
    @Test
    public void commandsTrackTheAdvertisedActionsRatherThanBeingGrantedWholesale() {
        publishAndSettle(PlaybackStateCompat.STATE_PLAYING, ALL_ACTIONS, 4_200L, 1.0f);
        assertThat(commandAvailable(Player.COMMAND_SEEK_FORWARD)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SEEK_BACK)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SET_REPEAT_MODE)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)).isTrue();

        publishAndSettle(PlaybackStateCompat.STATE_PLAYING,
                PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE, 4_200L, 1.0f);
        assertThat(commandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue();
        assertThat(commandAvailable(Player.COMMAND_SEEK_FORWARD)).isFalse();
        assertThat(commandAvailable(Player.COMMAND_SEEK_BACK)).isFalse();
        assertThat(commandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isFalse();
        assertThat(commandAvailable(Player.COMMAND_SET_REPEAT_MODE)).isFalse();
        assertThat(commandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)).isFalse();
        assertThat(commandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)).isFalse();

        // And the conversion narrows with it, rather than reporting the wider set regardless.
        long narrowed = actionsRoundTrip();
        assertThat((narrowed & PlaybackStateCompat.ACTION_FAST_FORWARD)).isEqualTo(0);
        assertThat((narrowed & PlaybackStateCompat.ACTION_PLAY_PAUSE)).isNotEqualTo(0);
    }

    private int roundTrip() {
        int[] out = new int[1];
        getInstrumentation().runOnMainSync(
                () -> out[0] = Media3PlaybackState.toLegacyState(mBrowser));
        return out[0];
    }

    private long actionsRoundTrip() {
        long[] out = new long[1];
        // The MediaController overload, deliberately: mBrowser IS a controller, and the Player
        // overload cannot see session commands, which is where rating lives.
        getInstrumentation().runOnMainSync(
                () -> out[0] = Media3PlaybackState.toLegacyActions((MediaController) mBrowser));
        return out[0];
    }

    private boolean commandAvailable(int command) {
        boolean[] out = new boolean[1];
        getInstrumentation().runOnMainSync(
                () -> out[0] = mBrowser.isCommandAvailable(command));
        return out[0];
    }

    private void publishAndSettle(int state, long actions, long positionMs, float speed) {
        FakeLegacyBrowseService service = FakeLegacyBrowseService.instance();
        getInstrumentation().runOnMainSync(
                () -> service.publishState(state, actions, positionMs, speed));
        // A fixed settle rather than a listener: several of these legacy states are expected to map
        // onto the SAME Media3 signal, and waiting for a change event would hang on exactly the
        // collapses this survey exists to find.
        SystemClock.sleep(SETTLE_MS);
        getInstrumentation().waitForIdleSync();
    }

    private static final int[] COMMANDS_OF_INTEREST = {
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_TIMELINE,
    };

    /**
     * EVERY action in ALL_ACTIONS, not a chosen subset.
     *
     * <p>The previous version of this array held fifteen of the twenty-two actions the fixture
     * advertises, and the test below then concluded that SET_RATING was the sole loss. That
     * conclusion was drawn over a vocabulary the test itself had narrowed: seven actions were
     * published to the session and never asked about. An exact-set assertion is only as strong as
     * the set it ranges over, and excluding candidates from it is the one edit that makes it weaker
     * while looking stricter.
     */
    private static final long[] ACTIONS_OF_INTEREST = {
            PlaybackStateCompat.ACTION_PLAY,
            PlaybackStateCompat.ACTION_PAUSE,
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
            PlaybackStateCompat.ACTION_STOP,
            PlaybackStateCompat.ACTION_PREPARE,
            PlaybackStateCompat.ACTION_SEEK_TO,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            PlaybackStateCompat.ACTION_FAST_FORWARD,
            PlaybackStateCompat.ACTION_REWIND,
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM,
            PlaybackStateCompat.ACTION_SET_RATING,
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE,
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE,
            PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED,
            PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED,
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH,
            PlaybackStateCompat.ACTION_PLAY_FROM_URI,
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID,
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH,
            PlaybackStateCompat.ACTION_PREPARE_FROM_URI,
    };

    private static String nameOfState(int s) {
        switch (s) {
            case PlaybackStateCompat.STATE_NONE: return "NONE";
            case PlaybackStateCompat.STATE_STOPPED: return "STOPPED";
            case PlaybackStateCompat.STATE_PAUSED: return "PAUSED";
            case PlaybackStateCompat.STATE_PLAYING: return "PLAYING";
            case PlaybackStateCompat.STATE_FAST_FORWARDING: return "FAST_FORWARDING";
            case PlaybackStateCompat.STATE_REWINDING: return "REWINDING";
            case PlaybackStateCompat.STATE_BUFFERING: return "BUFFERING";
            case PlaybackStateCompat.STATE_ERROR: return "ERROR";
            case PlaybackStateCompat.STATE_CONNECTING: return "CONNECTING";
            case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS: return "SKIPPING_TO_PREVIOUS";
            case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT: return "SKIPPING_TO_NEXT";
            case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM: return "SKIPPING_TO_QUEUE_ITEM";
            default: return "UNKNOWN(" + s + ")";
        }
    }

    private static String nameOfMedia3(int s) {
        switch (s) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN(" + s + ")";
        }
    }

    private static String nameOfCommand(int c) {
        switch (c) {
            case Player.COMMAND_PLAY_PAUSE: return "PLAY_PAUSE";
            case Player.COMMAND_PREPARE: return "PREPARE";
            case Player.COMMAND_STOP: return "STOP";
            case Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM: return "SEEK_TO_NEXT_MEDIA_ITEM";
            case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM: return "SEEK_TO_PREVIOUS_MEDIA_ITEM";
            case Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM: return "SEEK_IN_CURRENT_MEDIA_ITEM";
            case Player.COMMAND_SEEK_TO_MEDIA_ITEM: return "SEEK_TO_MEDIA_ITEM";
            case Player.COMMAND_SEEK_BACK: return "SEEK_BACK";
            case Player.COMMAND_SEEK_FORWARD: return "SEEK_FORWARD";
            case Player.COMMAND_SET_SPEED_AND_PITCH: return "SET_SPEED_AND_PITCH";
            case Player.COMMAND_SET_REPEAT_MODE: return "SET_REPEAT_MODE";
            case Player.COMMAND_SET_SHUFFLE_MODE: return "SET_SHUFFLE_MODE";
            case Player.COMMAND_GET_TIMELINE: return "GET_TIMELINE";
            default: return "COMMAND(" + c + ")";
        }
    }

    private static String nameOfAction(long a) {
        if (a == PlaybackStateCompat.ACTION_PLAY) return "ACTION_PLAY";
        if (a == PlaybackStateCompat.ACTION_PAUSE) return "ACTION_PAUSE";
        if (a == PlaybackStateCompat.ACTION_PLAY_PAUSE) return "ACTION_PLAY_PAUSE";
        if (a == PlaybackStateCompat.ACTION_STOP) return "ACTION_STOP";
        if (a == PlaybackStateCompat.ACTION_PREPARE) return "ACTION_PREPARE";
        if (a == PlaybackStateCompat.ACTION_SEEK_TO) return "ACTION_SEEK_TO";
        if (a == PlaybackStateCompat.ACTION_SKIP_TO_NEXT) return "ACTION_SKIP_TO_NEXT";
        if (a == PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) return "ACTION_SKIP_TO_PREVIOUS";
        if (a == PlaybackStateCompat.ACTION_FAST_FORWARD) return "ACTION_FAST_FORWARD";
        if (a == PlaybackStateCompat.ACTION_REWIND) return "ACTION_REWIND";
        if (a == PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM) return "ACTION_SKIP_TO_QUEUE_ITEM";
        if (a == PlaybackStateCompat.ACTION_SET_RATING) return "ACTION_SET_RATING";
        if (a == PlaybackStateCompat.ACTION_SET_REPEAT_MODE) return "ACTION_SET_REPEAT_MODE";
        if (a == PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) return "ACTION_SET_SHUFFLE_MODE";
        if (a == PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED) return "ACTION_SET_PLAYBACK_SPEED";
        if (a == PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED) return "ACTION_SET_CAPTIONING_ENABLED";
        if (a == PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID) return "ACTION_PLAY_FROM_MEDIA_ID";
        if (a == PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH) return "ACTION_PLAY_FROM_SEARCH";
        if (a == PlaybackStateCompat.ACTION_PLAY_FROM_URI) return "ACTION_PLAY_FROM_URI";
        if (a == PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID) return "ACTION_PREPARE_FROM_MEDIA_ID";
        if (a == PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH) return "ACTION_PREPARE_FROM_SEARCH";
        if (a == PlaybackStateCompat.ACTION_PREPARE_FROM_URI) return "ACTION_PREPARE_FROM_URI";
        return "ACTION(0x" + Long.toHexString(a) + ")";
    }
}
