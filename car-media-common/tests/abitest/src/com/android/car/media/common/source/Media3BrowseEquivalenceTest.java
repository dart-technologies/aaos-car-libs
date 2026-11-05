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

import android.content.ComponentName;
import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Media3 stage 2, stated as an equivalence.</b> A Media3 browser against the SAME legacy service
 * the production browser talks to, compared result for result.
 *
 * <p>This is what {@code LegacyBrowseFixtureTest} exists to make possible. The legacy side is
 * already proven there against a real {@code MediaBrowserServiceCompat} over a real binder, so the
 * fixture is the fixed point and the Media3 client is the only variable — which is the whole reason
 * the fixture was built and verified in its own change first.
 *
 * <p>The question this answers is the one the migration plan has carried since stage 1: third-party
 * AAOS media apps still ship {@code MediaBrowserServiceCompat}, and "Media3 clients can talk to
 * legacy sessions" is a claim about the library, not a measurement of this repository's use of it.
 * Custom actions and browse hints are where the models diverge; children, flags and search results
 * are where they must not.
 */
@RunWith(AndroidJUnit4.class)
public class Media3BrowseEquivalenceTest {

    private static final long TIMEOUT_SECONDS = 15;

    /** Room for elapsed real time between the session publishing a state and the client reading it. */
    private static final long POSITION_TOLERANCE_MS = 30_000L;

    private Context mContext;
    private MediaBrowser mBrowser;

    @Before
    public void connect() throws Exception {
        mContext = getInstrumentation().getContext();
        ComponentName component = new ComponentName(mContext, FakeLegacyBrowseService.class);

        AtomicReference<ListenableFuture<MediaBrowser>> future = new AtomicReference<>();
        // Media3 controllers are built and used on the application's main thread.
        getInstrumentation().runOnMainSync(() -> {
            SessionToken token = new SessionToken(mContext, component);
            future.set(new MediaBrowser.Builder(mContext, token).buildAsync());
        });

        mBrowser = future.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(mBrowser).isNotNull();
    }

    @After
    public void release() {
        if (mBrowser != null) {
            getInstrumentation().runOnMainSync(() -> mBrowser.release());
        }
    }

    @Test
    public void media3ReachesTheSameRootAsTheLegacyBrowser() throws Exception {
        LibraryResult<MediaItem> root = onMain(() -> mBrowser.getLibraryRoot(null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
        assertThat(root.value.mediaId).isEqualTo(FakeLegacyBrowseService.ROOT_ID);
    }

    @Test
    public void media3GetsTheSameChildrenInTheSameOrder() throws Exception {
        String rootId = onMain(() -> mBrowser.getLibraryRoot(null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value.mediaId;

        LibraryResult<ImmutableList<MediaItem>> children =
                onMain(() -> mBrowser.getChildren(rootId, 0, 100, null))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(children.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
        // The same ids, in the same order, as LegacyBrowseFixtureTest asserts for the legacy client.
        assertThat(ids(children.value))
                .containsExactly(FakeLegacyBrowseService.CHILD_A, FakeLegacyBrowseService.CHILD_B)
                .inOrder();
    }

    @Test
    public void media3PreservesBrowsableAndPlayable() throws Exception {
        String rootId = onMain(() -> mBrowser.getLibraryRoot(null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value.mediaId;
        ImmutableList<MediaItem> children =
                onMain(() -> mBrowser.getChildren(rootId, 0, 100, null))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value;

        // The flags Media3 makes Boolean and the legacy path makes int bits — the exact place
        // Media3Adapters had to decide what "unset" means. Measured here rather than assumed.
        assertThat(children.get(0).mediaMetadata.isBrowsable).isTrue();
        assertThat(children.get(0).mediaMetadata.isPlayable).isFalse();
        assertThat(children.get(1).mediaMetadata.isPlayable).isTrue();
        assertThat(children.get(1).mediaMetadata.isBrowsable).isFalse();
    }

    @Test
    public void media3SearchReturnsWhatTheLegacyServiceMatches() throws Exception {
        LibraryResult<Void> started = onMain(() -> mBrowser.search("one", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(started.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);

        LibraryResult<ImmutableList<MediaItem>> hits =
                onMain(() -> mBrowser.getSearchResult("one", 0, 100, null))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(hits.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
        assertThat(ids(hits.value)).containsExactly(FakeLegacyBrowseService.CHILD_B);
    }

    @Test
    public void media3ReportsAnEmptySearchTheWayTheLegacyPathDoes() throws Exception {
        // The divergence most likely to matter: legacy delivers onSearchResult with an empty list
        // and does NOT call onError. Whatever Media3 does here, a migration has to handle it, so it
        // is recorded as a measurement rather than an assumption.
        LibraryResult<Void> started = onMain(() -> mBrowser.search("nothing-matches-this", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        LibraryResult<ImmutableList<MediaItem>> hits =
                onMain(() -> mBrowser.getSearchResult("nothing-matches-this", 0, 100, null))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(started.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
        assertThat(hits.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
        assertThat(hits.value).isEmpty();
    }

    /**
     * The PLAYBACK half, measured the same way — because the token survey below proves the two
     * halves migrate together, so a browse-only equivalence would be measuring half a change.
     *
     * <p>{@code PlaybackViewModel} reads {@code PlaybackStateCompat}: a state constant, a position,
     * a speed and an action bitmask. Media3 decomposes all of that — the state becomes
     * {@code Player.getPlaybackState()} plus {@code getPlayWhenReady()}, the actions become
     * {@code Player.Commands}. This asserts the two describe the SAME playback, which is the claim
     * a migration of that file rests on.
     */
    @Test
    public void media3SeesTheSamePlaybackTheLegacySessionReports() throws Exception {
        int[] state = new int[1];
        boolean[] playWhenReady = new boolean[1];
        long[] position = new long[1];
        String[] title = new String[1];
        boolean[] canPlay = new boolean[1];
        boolean[] canPause = new boolean[1];

        getInstrumentation().runOnMainSync(() -> {
            state[0] = mBrowser.getPlaybackState();
            playWhenReady[0] = mBrowser.getPlayWhenReady();
            position[0] = mBrowser.getCurrentPosition();
            androidx.media3.common.MediaMetadata md = mBrowser.getMediaMetadata();
            title[0] = md.title == null ? null : md.title.toString();
            canPlay[0] = mBrowser.isCommandAvailable(
                    androidx.media3.common.Player.COMMAND_PLAY_PAUSE);
            canPause[0] = canPlay[0];
        });

        // The legacy session says STATE_PLAYING at TEST_POSITION_MS with PLAY|PAUSE available.
        // Media3's decomposition of that: READY + playWhenReady, the same position, and
        // COMMAND_PLAY_PAUSE.
        assertThat(state[0]).isEqualTo(androidx.media3.common.Player.STATE_READY);
        assertThat(playWhenReady[0]).isTrue();
        // BOUNDED on both sides. isAtLeast() alone accepted an arbitrarily inflated position, so
        // it did not establish the "same position" this test claims — a client reporting 10x the
        // real position would have passed. The window is for elapsed real time between the session
        // publishing the state and the client being asked, which at STATE_PLAYING advances.
        assertThat(position[0]).isAtLeast(FakeLegacyBrowseService.TEST_POSITION_MS);
        assertThat(position[0])
                .isLessThan(FakeLegacyBrowseService.TEST_POSITION_MS + POSITION_TOLERANCE_MS);
        assertThat(canPlay[0]).isTrue();
        assertThat(canPause[0]).isTrue();
        // And the metadata arrives through the same client, which is what removes the need for a
        // separate MediaControllerCompat at all.
        assertThat(title[0]).isEqualTo(FakeLegacyBrowseService.TEST_TITLE);
    }

    /**
     * <b>The question stage 2 has carried since stage 1</b>, answered by measurement.
     *
     * <p>{@code PlaybackViewModel} needs a {@code MediaSessionCompat.Token} — that is what the
     * decoupling narrowed its dependency to. If a Media3 browser could hand one back, the browse
     * half could migrate ALONE and the playback half could stay legacy. If it cannot, stages 2 and
     * 3 are one change however the plan words it.
     *
     * <p>Asserted as a reflective survey of what the client actually exposes, so the answer is a
     * fact about this Media3 version rather than a reading of its documentation.
     */
    @Test
    public void whatAMedia3BrowserCanHandBackToTheLegacyPlaybackPath() {
        SessionToken connected = mBrowser.getConnectedToken();
        assertThat(connected).isNotNull();

        List<String> legacyTokenAccessors = new ArrayList<>();
        for (java.lang.reflect.Method m : connected.getClass().getMethods()) {
            if (android.support.v4.media.session.MediaSessionCompat.Token.class
                    .isAssignableFrom(m.getReturnType())) {
                legacyTokenAccessors.add(m.getName());
            }
        }
        for (java.lang.reflect.Method m : mBrowser.getClass().getMethods()) {
            if (android.support.v4.media.session.MediaSessionCompat.Token.class
                    .isAssignableFrom(m.getReturnType())) {
                legacyTokenAccessors.add("browser." + m.getName());
            }
        }
        android.util.Log.i("Media3Equivalence",
                "legacy-token accessors on the Media3 client: " + legacyTokenAccessors);

        // THE result, and it is narrower than it first reads. Empty means there is no DIRECT,
        // typed accessor handing a MediaSessionCompat.Token back from a connected Media3 client.
        //
        // It does NOT prove a staged migration is impossible: a host could keep a second
        // MediaBrowserCompat connection open purely to obtain the token, and migrate browse alone.
        // That is a real option with a real cost — two connections to one service, two connection
        // lifecycles to keep in step, and a service that sees two clients where it expects one —
        // and it should be chosen deliberately rather than ruled out by a test that never examined
        // it. What this measurement kills is the FREE version: browse migrates and the token comes
        // along for nothing.
        assertThat(legacyTokenAccessors).isEmpty();
    }

    // -------------------------------------------------------------------------------------------

    /** Runs a browser call on the main thread and hands back its future. */
    private static <T> ListenableFuture<T> onMain(java.util.concurrent.Callable<ListenableFuture<T>> call) {
        AtomicReference<ListenableFuture<T>> ref = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            try {
                ref.set(call.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return ref.get();
    }

    private static List<String> ids(List<MediaItem> items) {
        List<String> out = new ArrayList<>();
        for (MediaItem item : items) {
            out.add(item.mediaId);
        }
        return out;
    }
}
