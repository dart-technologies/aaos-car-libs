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

import static com.android.car.media.common.MediaTestUtils.newFakeMediaSource;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.RuntimeEnvironment.getApplication;

import androidx.annotation.NonNull;
import androidx.media3.session.MediaBrowser;

import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.testutils.TestLifecycleOwner;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Media3 port note. The connector no longer creates a browser and then connects it; it asks Media3
 * for a {@link ListenableFuture} that yields an already-connected browser. Two consequences shape
 * every test below:
 *
 * <ul>
 *   <li>The test seam is the future, not the browser. Each test hands back a
 *       {@link SettableFuture} and decides when — and whether — it completes, which is exactly the
 *       control the old {@code doAnswer(...).when(browser).connect()} gave.
 *   <li>A state that has no browser YET reports {@code null} for it. {@code CONNECTING} is emitted
 *       before Media3 has produced anything, and a connection that fails never produces anything at
 *       all, so the browser-identity assertions that used to cover those two cases are stated here
 *       against the states that do have a browser. See the comments on the individual tests.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class MediaBrowserConnectorTest {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public final InstantTaskExecutorRule mTaskExecutorRule = new InstantTaskExecutorRule();
    @Rule
    public final TestLifecycleOwner mLifecycleOwner = new TestLifecycleOwner();

    @Mock
    public MediaBrowser mMediaBrowser1;
    @Mock
    public MediaBrowser mMediaBrowser2;

    private final MediaSource mMediaSource1 = newFakeMediaSource("mediaService1", "className1");
    private final MediaSource mMediaSource2 = newFakeMediaSource("mediaService2", "className2");

    private final Map<MediaSource, MediaBrowser> mBrowsers = new HashMap<>(2);
    private final Map<MediaSource, SettableFuture<MediaBrowser>> mFutures = new HashMap<>(2);

    private MediaBrowserConnector mBrowserConnector;

    /** Set by a test that wants {@code createMediaBrowser} itself to fail. */
    private RuntimeException mCreationFailure;

    private int mCreateMediaBrowserCalls;

    /** The Media3 listener handed to the most recent {@code createMediaBrowser} call. */
    private MediaBrowser.Listener mCapturedListener;

    @Mock
    public MediaBrowserConnector.Callback mConnectedBrowserCallback;
    @Captor
    private ArgumentCaptor<BrowsingState> mBrowsingStateCaptor;

    @Before
    public void setUp() {
        mBrowsers.put(mMediaSource1, mMediaBrowser1);
        mBrowsers.put(mMediaSource2, mMediaBrowser2);

        doNothing().when(mConnectedBrowserCallback).onBrowserConnectionChanged(
                mBrowsingStateCaptor.capture());

        mBrowserConnector = new MediaBrowserConnector(getApplication(), mConnectedBrowserCallback) {
            @Override
            @NonNull
            protected ListenableFuture<MediaBrowser> createMediaBrowser(
                    @NonNull MediaSource mediaSource, @NonNull MediaBrowser.Listener listener) {
                mCreateMediaBrowserCalls++;
                mCapturedListener = listener;
                if (mCreationFailure != null) {
                    throw mCreationFailure;
                }
                SettableFuture<MediaBrowser> future = SettableFuture.create();
                mFutures.put(mediaSource, future);
                return future;
            }
        };
    }

    /** Completes the pending connection for the given source, as Media3 would on success. */
    private void connectSucceeds(MediaSource source) {
        mFutures.get(source).set(mBrowsers.get(source));
    }

    /**
     * The legacy version asserted only that {@code connect()} was reached and nothing was thrown.
     * The equivalent here is that the creation seam was reached and its exception did not escape;
     * the SUSPENDED assertion is added rather than substituted — it is the behaviour the legacy
     * catch block already had and never checked.
     */
    @Test
    public void testExceptionOnConnectDoesNotCrash() {
        mCreationFailure = new IllegalStateException("expected");

        mBrowserConnector.connectTo(mMediaSource1);

        assertThat(mCreateMediaBrowserCalls).isEqualTo(1);
        assertThat(mBrowsingStateCaptor.getValue().mConnectionStatus)
                .isEqualTo(ConnectionStatus.SUSPENDED);
    }

    /**
     * Media3 rejects an unresolvable browse service from the {@code SessionToken} constructor with
     * IllegalArgumentException, which is the same "this source cannot be connected" outcome the
     * legacy IllegalStateException stood for. Pinned so the widened catch is not narrowed back by
     * accident.
     */
    @Test
    public void testUnresolvableServiceDoesNotCrash() {
        mCreationFailure = new IllegalArgumentException("no such browse service");

        mBrowserConnector.connectTo(mMediaSource1);

        assertThat(mBrowsingStateCaptor.getValue().mConnectionStatus)
                .isEqualTo(ConnectionStatus.SUSPENDED);
    }

    @Test
    public void testConnectionCallback_onConnected() {
        doReturn(true).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        connectSucceeds(mMediaSource1);

        BrowsingState state = mBrowsingStateCaptor.getValue();
        assertThat(state.mBrowser).isEqualTo(mMediaBrowser1);
        assertThat(state.mConnectionStatus).isEqualTo(ConnectionStatus.CONNECTED);
        verify(mMediaBrowser1, never()).release();
    }

    /**
     * The future produced a browser that says it is not connected.
     *
     * <p>The legacy assertion {@code state.mBrowser == browser} cannot be restated here: that
     * browser is a live binder this connector owns and must release, and a released browser must
     * not be handed to consumers. The release is asserted instead — it is the property that
     * actually matters and the legacy code had no equivalent of, because the browser it discarded
     * had never bound to anything.
     */
    @Test
    public void testConnectionCallback_onConnected_inconsistentState() {
        doReturn(false).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        connectSucceeds(mMediaSource1);

        BrowsingState state = mBrowsingStateCaptor.getValue();
        assertThat(state.mConnectionStatus).isEqualTo(ConnectionStatus.REJECTED);
        assertThat(state.mMediaSource).isEqualTo(mMediaSource1);
        assertThat(state.mBrowser).isNull();
        verify(mMediaBrowser1).release();
    }

    /**
     * The future failed, so — unlike the legacy path, which had constructed a browser object before
     * calling connect — there is no browser in existence to name in the state. The media source is
     * asserted in its place so the state is still pinned to the connection it belongs to.
     */
    @Test
    public void testConnectionCallback_onConnectionFailed() {
        mBrowserConnector.connectTo(mMediaSource1);
        mFutures.get(mMediaSource1).setException(new IllegalStateException("refused"));

        BrowsingState state = mBrowsingStateCaptor.getValue();
        assertThat(state.mMediaSource).isEqualTo(mMediaSource1);
        assertThat(state.mBrowser).isNull();
        assertThat(state.mConnectionStatus).isEqualTo(ConnectionStatus.REJECTED);
    }

    /** A future that completes with no browser is a refusal, not a connection. */
    @Test
    public void testConnectionCallback_nullBrowserIsRejected() {
        mBrowserConnector.connectTo(mMediaSource1);
        mFutures.get(mMediaSource1).set(null);

        assertThat(mBrowsingStateCaptor.getValue().mConnectionStatus)
                .isEqualTo(ConnectionStatus.REJECTED);
    }

    @Test
    public void testConnectionCallback_onConnectionSuspended() {
        doReturn(true).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        MediaBrowser.Listener listener = capturedListener();
        connectSucceeds(mMediaSource1);
        // Media3's replacement for onConnectionSuspended.
        listener.onDisconnected(mMediaBrowser1);

        List<BrowsingState> browsingStates = mBrowsingStateCaptor.getAllValues();
        // CONNECTING is emitted before Media3 has produced a browser, so it names the source it is
        // connecting to rather than an object that does not exist yet.
        assertThat(browsingStates.get(0).mMediaSource).isEqualTo(mMediaSource1);
        assertThat(browsingStates.get(1).mBrowser).isEqualTo(mMediaBrowser1);
        assertThat(browsingStates.get(2).mBrowser).isEqualTo(mMediaBrowser1);

        assertThat(browsingStates.get(0).mConnectionStatus).isEqualTo(ConnectionStatus.CONNECTING);
        assertThat(browsingStates.get(1).mConnectionStatus).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(browsingStates.get(2).mConnectionStatus).isEqualTo(ConnectionStatus.SUSPENDED);
    }

    /**
     * The sequence-number guard. A future created by an earlier {@code connectTo} completing after
     * a newer one must not publish state — the same hazard the legacy connection callbacks had, and
     * the reason the counter survived the port.
     */
    @Test
    public void testConnectionCallback_onConnectedIgnoredWhenLate() {
        doReturn(true).when(mMediaBrowser1).isConnected();
        doReturn(true).when(mMediaBrowser2).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        mBrowserConnector.connectTo(mMediaSource2);

        connectSucceeds(mMediaSource2);
        connectSucceeds(mMediaSource1);

        assertThat(mBrowsingStateCaptor.getValue().mBrowser).isEqualTo(mMediaBrowser2);
        // The obsolete future produced a live binder; ignoring it without releasing would leak it.
        verify(mMediaBrowser1).release();
    }

    /**
     * A → null, which is the transition the sequence-number guard did NOT cover.
     *
     * <p>The counter used to advance as a side effect of constructing a callback, and
     * {@code connectTo(null)} constructs none — so an in-flight future from the cleared source
     * stayed valid, {@code onConnected} accepted the browser it produced, and nothing released it.
     * {@code sendNewState} then only logged, because the media source was null, so the leak was
     * silent as well.
     *
     * <p>Existing coverage missed it for a specific reason worth stating: A → B advances the counter
     * incidentally, so every test written against a source SWAP passed while the clear path leaked.
     */
    @Test
    public void aFutureCompletingAfterTheSourceWasClearedReleasesTheBrowser() {
        doReturn(true).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        mBrowserConnector.connectTo(null);

        // The connection the caller cleared finally succeeds. It hands back a live bound client.
        connectSucceeds(mMediaSource1);

        verify(mMediaBrowser1).release();
    }

    /**
     * That the late browser is not PUBLISHED either — and this one already held before the fix.
     *
     * <p>Stated plainly because the first draft of this comment claimed it was "the other half of
     * the same defect", and measuring it said otherwise: run against the unfixed connector it
     * passes, because {@code sendNewState} already returned early when the media source was null.
     * The leak was silent for exactly that reason. So this is not a reproduction of the defect; it
     * is a guard against the over-correction of routing the late completion somewhere that DOES
     * publish. Kept, and labelled for what it is, rather than left implying coverage it never had.
     */
    @Test
    public void aFutureCompletingAfterTheSourceWasClearedPublishesNoFurtherState() {
        doReturn(true).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        mBrowserConnector.connectTo(null);
        int statesBefore = mBrowsingStateCaptor.getAllValues().size();

        connectSucceeds(mMediaSource1);

        assertThat(mBrowsingStateCaptor.getAllValues()).hasSize(statesBefore);
    }

    /**
     * A browser that has stopped reporting itself connected is still a bound client.
     *
     * <p>Replacement used to release only when {@code isConnected()} was true, so a session that
     * died before its source was replaced had its reference dropped without {@code release()} —
     * one leaked binder per dead session.
     */
    @Test
    public void aBrowserThatIsNoLongerConnectedIsStillReleasedWhenReplaced() {
        doReturn(true).when(mMediaBrowser1).isConnected();
        doReturn(true).when(mMediaBrowser2).isConnected();
        mBrowserConnector.connectTo(mMediaSource1);
        connectSucceeds(mMediaSource1);

        // The session dies while it is still the current one.
        doReturn(false).when(mMediaBrowser1).isConnected();
        mBrowserConnector.connectTo(mMediaSource2);

        verify(mMediaBrowser1).release();
    }

    /**
     * Releasing unconditionally must not also announce DISCONNECTING unconditionally.
     *
     * <p>DISCONNECTING describes leaving a connection. Emitting it for a browser that was already
     * gone would tell consumers to tear down state on behalf of a connection that had none, and it
     * is the obvious over-correction when making release unconditional — so it is pinned.
     */
    @Test
    public void replacingADisconnectedBrowserDoesNotAnnounceDisconnecting() {
        doReturn(true).when(mMediaBrowser1).isConnected();
        doReturn(true).when(mMediaBrowser2).isConnected();
        mBrowserConnector.connectTo(mMediaSource1);
        connectSucceeds(mMediaSource1);

        doReturn(false).when(mMediaBrowser1).isConnected();
        mBrowserConnector.connectTo(mMediaSource2);

        List<ConnectionStatus> after = new ArrayList<>();
        for (BrowsingState state : mBrowsingStateCaptor.getAllValues()) {
            after.add(state.mConnectionStatus);
        }
        assertThat(after).doesNotContain(ConnectionStatus.DISCONNECTING);
    }

    /** Browse pushes reach a registered listener, and stop reaching it once superseded. */
    @Test
    public void testBrowserEventsAreRebroadcastAndStopWhenSuperseded() {
        doReturn(true).when(mMediaBrowser1).isConnected();

        mBrowserConnector.connectTo(mMediaSource1);
        MediaBrowser.Listener listener = capturedListener();
        connectSucceeds(mMediaSource1);

        List<String> seen = new ArrayList<>();
        mBrowsingStateCaptor.getValue().addBrowserEventListener(
                new MediaBrowserConnector.BrowserEventListener() {
                    @Override
                    public void onChildrenChanged(@NonNull String parentId) {
                        seen.add(parentId);
                    }
                });

        listener.onChildrenChanged(mMediaBrowser1, "node", 3, null);
        assertThat(seen).containsExactly("node");

        // A newer connection makes the old listener obsolete.
        mBrowserConnector.connectTo(mMediaSource2);
        listener.onChildrenChanged(mMediaBrowser1, "stale-node", 3, null);
        assertThat(seen).containsExactly("node");
    }

    /** The listener the connector handed to Media3 for the most recent connection. */
    private MediaBrowser.Listener capturedListener() {
        return mCapturedListener;
    }
}
