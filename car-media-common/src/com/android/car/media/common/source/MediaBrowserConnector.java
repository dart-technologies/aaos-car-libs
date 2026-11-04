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

package com.android.car.media.common.source;

import static com.android.car.apps.common.util.CarAppsDebugUtils.idHash;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Preconditions;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.SessionToken;

import com.android.car.media.common.MediaConstants;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A helper class to connect to a single {@link MediaSource} to its {@link MediaBrowser}.
 * Connecting to a new one automatically disconnects the previous browser. Changes in the
 * connection status are sent via {@link MediaBrowserConnector.Callback}.
 *
 * <h2>Media3 migration, stage 2</h2>
 *
 * <p>This class used to build a {@code android.support.v4.media.MediaBrowserCompat}. It now builds
 * an {@code androidx.media3.session.MediaBrowser}. The state machine is the same one; what changed
 * is where each transition comes from:
 *
 * <ul>
 *   <li>{@code connect()} became {@code MediaBrowser.Builder#buildAsync()}, which returns a
 *       {@link ListenableFuture}. The browser object therefore does not exist until the connection
 *       has already succeeded — see {@link BrowsingState#mBrowser} for what that costs.
 *   <li>{@code ConnectionCallback#onConnected} / {@code onConnectionFailed} became the two outcomes
 *       of that future.
 *   <li>{@code onConnectionSuspended} became {@link MediaController.Listener#onDisconnected}.
 *   <li>{@code disconnect()} became {@link MediaController#release()}.
 * </ul>
 *
 * <p>The sequence-number guard ({@code mBrowserConnectionCallbackCounter}) survives that port
 * unchanged, and deliberately so. It exists because a callback belonging to a superseded connection
 * must not be allowed to publish state. A {@link ListenableFuture} has exactly the same hazard —
 * a future created by an earlier {@code connectTo} can complete after a later one has started — so
 * removing the counter as "legacy noise" would have reintroduced the bug it was written for. It is
 * also load-bearing in one new way: an obsolete future that completes with a live browser owns a
 * real binder, so the stale branch releases it rather than merely ignoring it.
 */
public class MediaBrowserConnector {

    private static final String TAG = "MediaBrowserConnector";

    /**
     * Where media3's future callbacks are delivered.
     *
     * <p>Runs inline when already on the main thread and posts otherwise. Inline matters: media3
     * completes these futures on the browser's application looper, which is the main looper here,
     * so posting unconditionally would turn a same-thread completion into a deferred one and change
     * the observable ordering of the {@code CONNECTING} / {@code CONNECTED} pair. It also keeps
     * Robolectric tests synchronous, since those run on the main thread with a paused looper.
     */
    public static final Executor CALLBACK_EXECUTOR = new Executor() {
        @Nullable private Handler mHandler;

        @Override
        public void execute(@NonNull Runnable command) {
            Looper main = Looper.getMainLooper();
            if (Looper.myLooper() == main) {
                command.run();
                return;
            }
            if (mHandler == null) {
                mHandler = new Handler(main);
            }
            mHandler.post(command);
        }
    };

    /**
     * Represents the state of the connection to the media browser service given to
     * {@link #connectTo}.
     */
    public enum ConnectionStatus {
        /**
         * The connection request to the browser is being initiated.
         * Sent from {@link #connectTo} just before asking media3 to build the browser.
         */
        CONNECTING,
        /**
         * The connection to the browser has been established and it can be used.
         * Sent when the {@link ListenableFuture} returned by {@code buildAsync} succeeds and
         * {@link MediaController#isConnected} also returns true.
         */
        CONNECTED,
        /**
         * The connection to the browser was refused.
         * Sent when that future fails, or when it succeeds but
         * {@link MediaController#isConnected} returns false.
         */
        REJECTED,
        /**
         * The browser crashed and that calls should NOT be made to it anymore.
         * Sent from {@link MediaController.Listener#onDisconnected} and from {@link #connectTo}
         * when building the browser throws.
         */
        SUSPENDED,
        /**
         * The connection to the browser is being closed.
         * When connecting to a new browser and the old browser is connected, this is sent
         * from {@link #connectTo} just before calling {@link MediaController#release} on the
         * old browser.
         */
        DISCONNECTING
    }

    /**
     * Push notifications media3 delivers to the browser's <b>single</b>
     * {@link MediaBrowser.Listener}.
     *
     * <p>That listener has to be supplied when the browser is built, so the connector is the only
     * object that can own it — but the events are for the browse repository, not for the connector.
     * Hence this re-broadcast. Dropping it was not an option: media3's legacy bridge routes a
     * service's {@code notifyChildrenChanged} through {@code Listener#onChildrenChanged}, so a
     * connector that swallowed these would leave every browse list frozen at its first load.
     */
    public interface BrowserEventListener {
        /** The browse service says the children of this node changed (or failed to load). */
        default void onChildrenChanged(@NonNull String parentId) {}

        /** The browse service says the results for this query changed. */
        default void onSearchResultChanged(@NonNull String query) {}
    }

    /** Registration point for {@link BrowserEventListener}, scoped to one connection. */
    interface BrowserEventRegistrar {
        void addBrowserEventListener(@NonNull BrowserEventListener listener);
    }

    /**
     * Encapsulates a {@link ComponentName} with its {@link MediaBrowser} and the
     * {@link ConnectionStatus}.
     */
    public static class BrowsingState {
        @NonNull public final MediaSource mMediaSource;

        /**
         * The connected browser, or {@code null} when there isn't one yet.
         *
         * <p>This became nullable in the Media3 port and the reason is structural, not a
         * relaxation. Legacy handed you a {@code MediaBrowserCompat} instance immediately and you
         * called {@code connect()} on it, so a {@code CONNECTING} state could carry a (useless,
         * not-yet-connected) browser. Media3's {@code buildAsync} produces the object only once the
         * connection has succeeded, so during {@code CONNECTING} — and after {@code REJECTED} —
         * there is genuinely no browser to carry. Every consumer already only dereferenced this in
         * the {@code CONNECTED} and {@code DISCONNECTING} branches; the type now says so.
         */
        @Nullable public final MediaBrowser mBrowser;

        @NonNull public final ConnectionStatus mConnectionStatus;

        @Nullable private final BrowserEventRegistrar mEventRegistrar;

        @VisibleForTesting
        public BrowsingState(@NonNull MediaSource mediaSource, @Nullable MediaBrowser browser,
                @NonNull ConnectionStatus status) {
            this(mediaSource, browser, status, /* eventRegistrar= */ null);
        }

        BrowsingState(@NonNull MediaSource mediaSource, @Nullable MediaBrowser browser,
                @NonNull ConnectionStatus status, @Nullable BrowserEventRegistrar registrar) {
            mMediaSource = Preconditions.checkNotNull(mediaSource, "source can't be null");
            mBrowser = browser;
            mConnectionStatus = Preconditions.checkNotNull(status, "status can't be null");
            mEventRegistrar = registrar;
        }

        /**
         * The controller for the session this browser is connected to.
         *
         * <p>Exists so {@code PlaybackViewModel} can stop taking the BROWSER. It only ever wanted
         * the ability to control playback — the production factory was literally
         * {@code browser -> new MediaControllerCompat(app, browser.getSessionToken())} — and taking
         * the browser instead made the playback half depend on the browse half's type.
         *
         * <p>In Media3 {@code MediaBrowser extends MediaController}, so the narrowing that used to
         * need a token and a factory is now just the supertype: the playback half is handed a
         * {@link MediaController} and therefore cannot browse with it. The token round trip, and
         * the {@code InputFactory} that existed only to perform it, are gone — there is no second
         * client object to build.
         */
        @Nullable
        public MediaController getController() {
            return mBrowser;
        }

        /**
         * Subscribes to browse-service push notifications for this connection.
         *
         * <p>No-op for a {@link BrowsingState} built by a test through the visible-for-testing
         * constructor, which has no connection behind it.
         */
        public void addBrowserEventListener(@NonNull BrowserEventListener listener) {
            if (mEventRegistrar != null) {
                mEventRegistrar.addBrowserEventListener(listener);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BrowsingState that = (BrowsingState) o;
            return mMediaSource.equals(that.mMediaSource)
                    && Objects.equals(mBrowser, that.mBrowser)
                    && mConnectionStatus == that.mConnectionStatus;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mMediaSource, mBrowser, mConnectionStatus);
        }
    }

    /** The callback to receive the current {@link MediaBrowser} and its connection state. */
    public interface Callback {
        /** Notifies the listener of connection status changes. */
        void onBrowserConnectionChanged(@NonNull BrowsingState state);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final int mMaxBitmapSizePx;

    @Nullable private MediaSource mMediaSource;
    @Nullable private MediaBrowser mBrowser;
    @Nullable private BrowserConnectionCallback mCurrentCallback;

    /**
     * Create a new MediaBrowserConnector.
     *
     * @param context The Context with which to build MediaBrowsers.
     */
    public MediaBrowserConnector(@NonNull Context context, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;
        mMaxBitmapSizePx = mContext.getResources().getInteger(
                com.android.car.media.common.R.integer.media_items_bitmap_max_size_px);
    }

    private String getSourcePackage() {
        if (mMediaSource == null) return null;
        return mMediaSource.getBrowseServiceComponentName().getPackageName();
    }

    /** Counter so callbacks from obsolete connections can be ignored. */
    private int mBrowserConnectionCallbackCounter = 0;

    /**
     * Retires every outstanding connection callback, so a future that has not completed yet cannot
     * publish anything when it does.
     *
     * <p>This exists as its own step, called unconditionally, because the obvious place to put it
     * was wrong. Advancing the counter used to be a side effect of CONSTRUCTING a
     * {@link BrowserConnectionCallback}, which happens only for a non-null media source -- so
     * {@code connectTo(null)} left the counter where it was and an in-flight future from the
     * previous source stayed VALID. When it completed, {@link BrowserConnectionCallback#onConnected}
     * accepted it, stored the browser, and {@code sendNewState} then merely logged because
     * {@code mMediaSource} was null. The result was a live bound client with no owner, and the
     * quiet resurrection of a connection the caller had explicitly cleared.
     *
     * <p>Retiring the callback here routes that late completion into the stale branch of
     * {@code onConnected}, which already releases the browser it was handed -- so the fix is one
     * counter increment rather than a second release path.
     *
     * <p>A → B never showed this because constructing B's callback advanced the counter as a side
     * effect. Only A → null did, which is why the test for it is named for that transition.
     */
    private void retireOutstandingCallbacks() {
        mBrowserConnectionCallbackCounter++;
        mCurrentCallback = null;
    }

    private class BrowserConnectionCallback
            implements MediaBrowser.Listener, BrowserEventRegistrar {

        private final int mSequenceNumber = ++mBrowserConnectionCallbackCounter;
        private final String mCallbackPackage = getSourcePackage();
        private final Set<BrowserEventListener> mEventListeners = new LinkedHashSet<>();

        private BrowserConnectionCallback() {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "New Callback: " + idHash(this));
            }
        }

        private boolean isValidCall(String method) {
            if (mSequenceNumber != mBrowserConnectionCallbackCounter) {
                Log.e(TAG, "Callback: " + idHash(this) + " ignoring " + method + " for "
                        + mCallbackPackage + " seq: "
                        + mSequenceNumber + " current: " + mBrowserConnectionCallbackCounter
                        + " package: " + getSourcePackage());
                return false;
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, method + " " + getSourcePackage() + " mBrowser: " + idHash(mBrowser));
            }
            return true;
        }

        @Override
        public void addBrowserEventListener(@NonNull BrowserEventListener listener) {
            mEventListeners.add(listener);
        }

        void onConnected(@NonNull MediaBrowser browser) {
            if (!isValidCall("onConnected")) {
                // A superseded connection completed anyway. Legacy could merely ignore this: the
                // browser it was ignoring was one it had already stopped referencing. Here the
                // future hands back a live, bound client, so ignoring it would leak the binder for
                // the lifetime of the process.
                browser.release();
                return;
            }
            if (browser.isConnected()) {
                mBrowser = browser;
                sendNewState(ConnectionStatus.CONNECTED);
            } else {
                browser.release();
                sendNewState(ConnectionStatus.REJECTED);
            }
        }

        void onConnectionFailed() {
            if (isValidCall("onConnectionFailed")) {
                sendNewState(ConnectionStatus.REJECTED);
            }
        }

        @Override
        public void onDisconnected(@NonNull MediaController controller) {
            if (isValidCall("onDisconnected")) {
                sendNewState(ConnectionStatus.SUSPENDED);
            }
        }

        @Override
        public void onChildrenChanged(@NonNull MediaBrowser browser, @NonNull String parentId,
                int itemCount, @Nullable MediaLibraryService.LibraryParams params) {
            if (!isValidCall("onChildrenChanged")) return;
            for (BrowserEventListener listener : mEventListeners) {
                listener.onChildrenChanged(parentId);
            }
        }

        @Override
        public void onSearchResultChanged(@NonNull MediaBrowser browser, @NonNull String query,
                int itemCount, @Nullable MediaLibraryService.LibraryParams params) {
            if (!isValidCall("onSearchResultChanged")) return;
            for (BrowserEventListener listener : mEventListeners) {
                listener.onSearchResultChanged(query);
            }
        }
    }

    private void sendNewState(ConnectionStatus cnx) {
        if (mMediaSource == null) {
            Log.e(TAG, "sendNewState mMediaSource is null!");
            return;
        }
        // NOTE: unlike the legacy version there is no null-browser guard here. CONNECTING and
        // REJECTED legitimately have no browser now (see BrowsingState#mBrowser); refusing to
        // publish them would strand every consumer in its previous state.
        mCallback.onBrowserConnectionChanged(
                new BrowsingState(mMediaSource, mBrowser, cnx, mCurrentCallback));
    }

    /**
     * Creates and connects a new {@link MediaBrowser} if the given {@link MediaSource} isn't null.
     * If needed, the previous browser is disconnected.
     *
     * @param mediaSource the media source to connect to.
     */
    public void connectTo(@Nullable MediaSource mediaSource) {
        if (mBrowser != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Disconnecting: " + getSourcePackage()
                        + " mBrowser: " + idHash(mBrowser));
            }
            // DISCONNECTING describes leaving a connection, so it is announced only when there was
            // one. release() is NOT conditioned on that: a browser reporting isConnected() == false
            // is still a bound client that owns a binder, and dropping the reference without
            // releasing it leaked one every time a session died before its source was replaced.
            if (mBrowser.isConnected()) {
                sendNewState(ConnectionStatus.DISCONNECTING);
            }
            mBrowser.release();
        }
        mBrowser = null;

        // Unconditional, and the whole point: this is the line that also runs for a null source.
        retireOutstandingCallbacks();

        mMediaSource = mediaSource;
        if (mMediaSource != null) {
            BrowserConnectionCallback callback = new BrowserConnectionCallback();
            mCurrentCallback = callback;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Connecting to: " + getSourcePackage());
            }
            sendNewState(ConnectionStatus.CONNECTING);
            try {
                ListenableFuture<MediaBrowser> future = createMediaBrowser(mMediaSource, callback);
                Futures.addCallback(future, new FutureCallback<MediaBrowser>() {
                    @Override
                    public void onSuccess(@Nullable MediaBrowser result) {
                        if (result != null) {
                            callback.onConnected(result);
                        } else {
                            callback.onConnectionFailed();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {
                        Log.e(TAG, "Connection future failed: " + t);
                        callback.onConnectionFailed();
                    }
                }, CALLBACK_EXECUTOR);
            } catch (IllegalStateException | IllegalArgumentException ex) {
                // Legacy caught IllegalStateException from MediaBrowserCompat#connect, on the
                // grounds that the browser could be in an intermediate state. Media3 throws
                // IllegalArgumentException from the SessionToken constructor when the component
                // does not resolve to a media browse service — the same class of "the source is
                // not usable right now" failure, and the same response: report SUSPENDED rather
                // than propagate.
                Log.e(TAG, "Connection exception: " + ex);
                sendNewState(ConnectionStatus.SUSPENDED);
            }
        }
    }

    // Override for testing.
    @NonNull
    protected ListenableFuture<MediaBrowser> createMediaBrowser(@NonNull MediaSource mediaSource,
            @NonNull MediaBrowser.Listener listener) {
        Bundle rootHints = new Bundle();
        rootHints.putInt(MediaConstants.EXTRA_MEDIA_ART_SIZE_HINT_PIXELS, mMaxBitmapSizePx);
        ComponentName browseService = mediaSource.getBrowseServiceComponentName();
        // Connection hints are what media3's legacy bridge passes to MediaBrowserCompat as root
        // hints, so this is the same bitmap-size hint reaching the same place by the new name.
        return new MediaBrowser.Builder(mContext, new SessionToken(mContext, browseService))
                .setConnectionHints(rootHints)
                .setListener(listener)
                .buildAsync();
    }
}
