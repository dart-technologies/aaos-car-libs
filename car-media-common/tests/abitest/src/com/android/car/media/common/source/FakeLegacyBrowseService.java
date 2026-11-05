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

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * A real {@code MediaBrowserServiceCompat}, the shape third-party AAOS media apps still ship.
 *
 * <h2>Why it exists</h2>
 *
 * <p>{@code MediaBrowserConnectorTest} overrides {@code createMediaBrowser} to return a Mockito
 * mock, which is right for the connector's state machine and says nothing about browsing.
 * {@code MediaItemsRepository} — the class that actually calls {@code subscribe},
 * {@code unsubscribe}, {@code search} and {@code getRoot} — had <b>no test at all</b>. A mocked
 * browser can only confirm that the code called the methods it called; it cannot show the results
 * came back the same, which is precisely the claim a Media3 migration needs to make.
 *
 * <h2>{@code setSessionToken} is not optional, and its absence is silent</h2>
 *
 * <p>The first version of this fixture omitted it and the client hung forever with <b>no</b>
 * {@code onConnected} and <b>no</b> {@code onConnectionFailed}. That is not a race or a timeout — it
 * is the documented structure of {@code MediaBrowserServiceCompat.ServiceBinderImpl.connect}:
 *
 * <pre>
 *     if (mSession != null) {
 *         callbacks.onConnect(connection.root.getRootId(), mSession, ...);
 *     }
 * </pre>
 *
 * <p>With no session token, {@code mSession} is null, the connection is recorded in
 * {@code mConnections}, and <b>neither</b> callback is ever sent. A service that forgets it is
 * indistinguishable from one that is slow, which is why the symptom read as an environment problem
 * for a whole debugging pass.
 */
public class FakeLegacyBrowseService extends MediaBrowserServiceCompat {

    public static final String ROOT_ID = "root";
    public static final String CHILD_A = "child-a";
    public static final String CHILD_B = "child-b";

    @Nullable
    private MediaSessionCompat mSession;

    /**
     * The live service, so a test can drive the session through states at runtime.
     *
     * <p>Safe because the fixture is declared in the test APK's manifest with no {@code
     * android:process}, so under instrumentation it runs in the test's own process. A test that
     * merely constructed a session instead would be measuring a session nobody is connected to.
     */
    @Nullable
    private static volatile FakeLegacyBrowseService sInstance;

    /** The running fixture, or null if it has not been started (or has been destroyed). */
    @Nullable
    public static FakeLegacyBrowseService instance() {
        return sInstance;
    }

    /**
     * Publishes an arbitrary legacy playback state, so what Media3's legacy bridge does with each
     * one can be OBSERVED rather than assumed.
     *
     * <p>Speed is a parameter because it is half of what distinguishes some legacy states from each
     * other -- FAST_FORWARDING at 2x and PLAYING at 1x differ in the state integer AND the speed,
     * and a survey that held speed at 1.0 could not tell which of the two Media3 was reading.
     */
    public void publishState(int state, long actions, long positionMs, float speed) {
        MediaSessionCompat session = mSession;
        if (session == null) {
            throw new IllegalStateException("publishState before onCreate");
        }
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, speed)
                .build());
    }

    /** The state the session reports, so both clients can be asked the same question. */
    public static final long TEST_POSITION_MS = 4_200L;
    public static final float TEST_SPEED = 1.0f;
    public static final String TEST_TITLE = "Now playing title";
    public static final String TEST_ARTIST = "Now playing artist";

    @Override
    public void onCreate() {
        super.onCreate();
        // In onCreate and immediately, as the platform documentation says: the client cannot
        // complete its connection until this exists, and may only be set once.
        mSession = new MediaSessionCompat(this, "FakeLegacyBrowseService");
        // A real playback state and metadata, so the PLAYBACK half can be compared the same way the
        // browse half is. Without this the session is connectable and says nothing, which would let
        // a playback equivalence pass by both sides reporting nothing.
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, TEST_POSITION_MS, TEST_SPEED)
                .build());
        mSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, TEST_TITLE)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, TEST_ARTIST)
                .build());
        mSession.setActive(true);
        setSessionToken(mSession.getSessionToken());
        sInstance = this;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, @Nullable Bundle hints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> children = new ArrayList<>();
        if (ROOT_ID.equals(parentId)) {
            children.add(item(CHILD_A, "Album one",
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            children.add(item(CHILD_B, "Track one",
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(children);
    }

    @Override
    public void onSearch(String query, @Nullable Bundle extras,
            Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> hits = new ArrayList<>();
        if ("one".equalsIgnoreCase(query)) {
            hits.add(item(CHILD_B, "Track one", MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(hits);
    }

    private static MediaBrowserCompat.MediaItem item(String id, String title, int flags) {
        return new MediaBrowserCompat.MediaItem(
                new MediaDescriptionCompat.Builder()
                        .setMediaId(id)
                        .setTitle(title)
                        .build(),
                flags);
    }
}
