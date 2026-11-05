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
package com.android.car.media.common.browse;

import static com.android.car.media.common.MediaTestUtils.newFakeMediaSource;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;

import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.util.FutureData;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaItemsRepository.MediaItemsLiveData;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.TestBrowsingStates;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * The asynchronous boundary this repository grew in the Media3 flip.
 *
 * <p>Before the flip there was nothing here to test in isolation, and no test: browse answers
 * arrived on callbacks owned by a browser that was discarded with its connection, so a response
 * could not outlive the thing that asked for it. Futures can. Most of what follows exists because a
 * completion can now land after the world it was issued in has gone away, and the class guards that
 * with {@code isStale} plus a pair of query comparisons that nothing exercised until now.
 *
 * <h2>How these tests get determinism</h2>
 *
 * <p>The seam is the future. Each browser method is stubbed to hand back a {@link SettableFuture}
 * the test completes by hand, so "A's answer arrives after B connected" is written as literally that
 * ordering rather than approximated with a timeout. {@code MediaBrowserConnector.CALLBACK_EXECUTOR}
 * runs inline when it is already on the main looper, which under Robolectric is the test thread, so
 * completing a future runs its callback before the next statement.
 *
 * <h2>One trap worth naming</h2>
 *
 * <p>{@link MediaItemsRepository#getMediaChildren} is not a getter — it subscribes as a side effect.
 * Reading a node's value by calling it a second time would issue a second subscription, and a test
 * could pass because of traffic it generated while measuring. Every test here captures the
 * {@link MediaItemsLiveData} once and reads {@code getValue()} afterwards.
 */
@RunWith(RobolectricTestRunner.class)
public class MediaItemsRepositoryTest {

    @Rule public final InstantTaskExecutorRule mTaskExecutorRule = new InstantTaskExecutorRule();
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private static final String ROOT_A = "rootA";

    @Mock private MediaBrowser mBrowserA;
    @Mock private MediaBrowser mBrowserB;

    private final MediaSource mSourceA = newFakeMediaSource("com.a", "com.a.BrowseService");
    private final MediaSource mSourceB = newFakeMediaSource("com.b", "com.b.BrowseService");

    private MutableLiveData<BrowsingState> mBrowsingStates;
    private MediaItemsRepository mRepository;
    private TestBrowsingStates.Pushes mPushesA;

    @Before
    public void setUp() {
        mBrowsingStates = new MutableLiveData<>();
        mRepository = new MediaItemsRepository(mBrowsingStates);
        mPushesA = new TestBrowsingStates.Pushes();
    }

    // -------------------------------------------------------------------------------------
    // A completion that outlived its connection
    // -------------------------------------------------------------------------------------

    @Test
    public void aRootArrivingAfterTheSourceChangedCannotPopulateTheNewSource() {
        SettableFuture<LibraryResult<MediaItem>> rootA = stubRoot(mBrowserA);
        connect(mSourceA, mBrowserA, mPushesA);

        // The user navigates to another app before the first one answered.
        stubRoot(mBrowserB);
        connect(mSourceB, mBrowserB, new TestBrowsingStates.Pushes());

        rootA.set(LibraryResult.ofItem(
                item(ROOT_A, /* browsable= */ true, /* playable= */ false), null));

        // Source A's root must not become source B's root. getCache() keys off the CURRENT source,
        // so an unguarded completion here would write A's root id into B's cache and subscribe for
        // it -- against whichever browser is current.
        verify(mBrowserA, never()).subscribe(eq(ROOT_A), any());
        verify(mBrowserB, never()).subscribe(eq(ROOT_A), any());
    }

    @Test
    public void childrenArrivingAfterTheSourceChangedCannotPopulateTheNewSource() {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> childrenA =
                stubChildren(mBrowserA, "node");
        stubSubscribe(mBrowserA, "node").set(LibraryResult.ofVoid());
        stubRoot(mBrowserA);
        connect(mSourceA, mBrowserA, mPushesA);
        MediaItemsLiveData node = mRepository.getMediaChildren("node");

        stubRoot(mBrowserB);
        connect(mSourceB, mBrowserB, new TestBrowsingStates.Pushes());

        childrenA.set(LibraryResult.ofItemList(ImmutableList.of(item("late")), null));

        // Lists are cached per media source, so a stale answer landing here would show source A's
        // items while source B is on screen. The node is left as it was: still loading.
        assertThat(node.getValue().isLoading()).isTrue();
        assertThat(FutureData.getData(node.getValue())).isNull();
    }

    // -------------------------------------------------------------------------------------
    // Supersession within one connection
    // -------------------------------------------------------------------------------------

    @Test
    public void aSupersededSearchIsNotFetched() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        SettableFuture<LibraryResult<Void>> jazz = stubSearch(mBrowserA, "jazz");
        stubSearch(mBrowserA, "rock");

        mRepository.setSearchQuery("jazz");
        mRepository.setSearchQuery("rock");
        jazz.set(LibraryResult.ofVoid());

        // "jazz" succeeded, but the user is looking at "rock". Fetching its results would spend a
        // round trip whose answer the next guard would then have to throw away.
        verify(mBrowserA, never()).getSearchResult(eq("jazz"), anyInt(), anyInt(), any());
    }

    @Test
    public void aSupersededSearchResultCannotOverwriteTheNewerQuery() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSearch(mBrowserA, "jazz").set(LibraryResult.ofVoid());
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> jazzHits =
                stubSearchResult(mBrowserA, "jazz");
        mRepository.setSearchQuery("jazz");

        stubSearch(mBrowserA, "rock").set(LibraryResult.ofVoid());
        stubSearchResult(mBrowserA, "rock").set(
                LibraryResult.ofItemList(ImmutableList.of(item("rock-1")), null));
        mRepository.setSearchQuery("rock");

        // Only now does the earlier query's fetch come back.
        jazzHits.set(LibraryResult.ofItemList(ImmutableList.of(item("jazz-1")), null));

        assertThat(ids(FutureData.getData(mRepository.getSearchMediaItems().getValue())))
                .containsExactly("rock-1");
    }

    @Test
    public void aSearchWithoutABrowserCompletesAsAnErrorInsteadOfLoadingForever() {
        mBrowsingStates.setValue(TestBrowsingStates.with(
                mSourceA, null, ConnectionStatus.CONNECTING, mPushesA));

        mRepository.setSearchQuery("rock");

        assertThat(mRepository.getSearchMediaItems().getValue().isLoading()).isFalse();
        assertThat(FutureData.getData(mRepository.getSearchMediaItems().getValue())).isNull();
    }

    @Test
    public void anOlderRequestForTheSameNodeCannotOverwriteANewerOne() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        SettableFuture<LibraryResult<Void>> firstSubscribe = stubSubscribe(mBrowserA, "node");
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> firstChildren =
                stubChildren(mBrowserA, "node");
        MediaItemsLiveData node = mRepository.getMediaChildren("node");
        firstSubscribe.set(LibraryResult.ofVoid());

        SettableFuture<LibraryResult<Void>> secondSubscribe = stubSubscribe(mBrowserA, "node");
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> secondChildren =
                stubChildren(mBrowserA, "node");
        mRepository.getMediaChildren("node");
        secondSubscribe.set(LibraryResult.ofVoid());
        secondChildren.set(LibraryResult.ofItemList(ImmutableList.of(item("new")), null));
        firstChildren.set(LibraryResult.ofItemList(ImmutableList.of(item("old")), null));

        assertThat(ids(FutureData.getData(node.getValue()))).containsExactly("new");
    }

    // -------------------------------------------------------------------------------------
    // Failure is not emptiness
    // -------------------------------------------------------------------------------------

    @Test
    public void aSubscribeFailureIsReportedAsNullNotAsAnEmptyList() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSubscribe(mBrowserA, "bad").set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));

        MediaItemsLiveData node = mRepository.getMediaChildren("bad");

        // null is what the legacy SubscriptionCallback#onError delivered and downstream shows an
        // error for it; an empty list means "this node has no children", a normal browse result.
        // Collapsing the two is the mistake being asserted against.
        assertThat(node.getValue().isLoading()).isFalse();
        assertThat(FutureData.getData(node.getValue())).isNull();
    }

    @Test
    public void anEmptySuccessIsAnEmptyListNotAFailure() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSubscribe(mBrowserA, "empty").set(LibraryResult.ofVoid());
        stubChildren(mBrowserA, "empty").set(LibraryResult.ofItemList(ImmutableList.of(), null));

        MediaItemsLiveData node = mRepository.getMediaChildren("empty");

        assertThat(node.getValue().isLoading()).isFalse();
        assertThat(FutureData.getData(node.getValue())).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // Push routing
    // -------------------------------------------------------------------------------------

    @Test
    public void theRepositoryRegistersExactlyOneListenerForTheConnection() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);

        // Anti-vacuity for the two tests below. If nothing registered, "the push was ignored" and
        // "there was nobody to ignore it" are indistinguishable and both would pass.
        assertThat(mPushesA.listenerCount()).isEqualTo(1);
    }

    @Test
    public void aPushForTheShownNodeRefetchesIt() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSubscribe(mBrowserA, "shown").set(LibraryResult.ofVoid());
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> first =
                stubChildren(mBrowserA, "shown");
        MediaItemsLiveData node = mRepository.getMediaChildren("shown");
        first.set(LibraryResult.ofItemList(ImmutableList.of(item("one")), null));

        stubChildren(mBrowserA, "shown").set(
                LibraryResult.ofItemList(ImmutableList.of(item("one"), item("two")), null));
        mPushesA.childrenChanged("shown");

        // The push carries a count, not a list, and the repository re-reads rather than trusting
        // it. A connector that swallowed these would freeze every browse list at its first load.
        assertThat(ids(FutureData.getData(node.getValue())))
                .containsExactly("one", "two").inOrder();
    }

    @Test
    public void aPushForANodeNotBeingShownIsIgnored() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);

        mPushesA.childrenChanged("a-node-nobody-asked-for");

        verify(mBrowserA, never())
                .getChildren(eq("a-node-nobody-asked-for"), anyInt(), anyInt(), any());
    }

    @Test
    public void aPushForAStaleSearchQueryIsIgnored() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSearch(mBrowserA, "rock").set(LibraryResult.ofVoid());
        stubSearchResult(mBrowserA, "rock").set(
                LibraryResult.ofItemList(ImmutableList.of(item("rock-1")), null));
        mRepository.setSearchQuery("rock");

        mPushesA.searchResultChanged("jazz");

        // A service is entitled to push about a query this client has already moved on from.
        verify(mBrowserA, never()).getSearchResult(eq("jazz"), anyInt(), anyInt(), any());
    }

    // -------------------------------------------------------------------------------------
    // Losing the connection
    // -------------------------------------------------------------------------------------

    @Test
    public void disconnectingUnsubscribesEveryNodeAndClearsTheSearchResults() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSubscribe(mBrowserA, "node").set(LibraryResult.ofVoid());
        stubChildren(mBrowserA, "node").set(
                LibraryResult.ofItemList(ImmutableList.of(item("one")), null));
        mRepository.getMediaChildren("node");
        stubSearch(mBrowserA, "rock").set(LibraryResult.ofVoid());
        stubSearchResult(mBrowserA, "rock").set(
                LibraryResult.ofItemList(ImmutableList.of(item("rock-1")), null));
        mRepository.setSearchQuery("rock");

        // Counted from here, because getMediaChildren unsubscribes before re-subscribing BY DESIGN
        // (it is the documented workaround for apps that only push to subscribers). Verifying from
        // the start of the test would conflate that setup call with the disconnect's own, and
        // times(2) would pass just as well if the disconnect stopped unsubscribing entirely.
        clearInvocations(mBrowserA);

        mBrowsingStates.setValue(TestBrowsingStates.with(
                mSourceA, mBrowserA, ConnectionStatus.DISCONNECTING, mPushesA));

        verify(mBrowserA).unsubscribe("node");
        assertThat(mRepository.getSearchMediaItems().getValue()).isNull();

        // The node cache really was emptied, shown without re-entering getMediaChildren (which
        // would subscribe again and could make this pass on traffic it generated itself): a push
        // for that node is now treated as one nobody asked for.
        mPushesA.childrenChanged("node");
        verify(mBrowserA, never()).getChildren(eq("node"), anyInt(), anyInt(), any());
    }

    @Test
    public void disconnectingDoesNotClearAChildListAlreadyHandedOut() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSubscribe(mBrowserA, "node").set(LibraryResult.ofVoid());
        stubChildren(mBrowserA, "node").set(
                LibraryResult.ofItemList(ImmutableList.of(item("one")), null));
        MediaItemsLiveData node = mRepository.getMediaChildren("node");
        assertThat(FutureData.getData(node.getValue())).isNotEmpty();

        mBrowsingStates.setValue(TestBrowsingStates.with(
                mSourceA, mBrowserA, ConnectionStatus.DISCONNECTING, mPushesA));

        // A GAP, pinned rather than asserted away, and PRE-EXISTING: clearNodes() is byte-identical
        // before and after the Media3 flip, so this is inherited AOSP behaviour and not something
        // the migration introduced.
        //
        // clearNodes() empties the cache map but never touches the MediaItemsLiveData instances
        // already returned by getMediaChildren, and nothing posts a final value to them. A screen
        // still observing one keeps rendering the disconnected source's items. Whether that is
        // visible depends on whether the observer re-enters getMediaChildren (getting a fresh
        // loading entry) or holds the instance it was given -- which is a property of the caller,
        // not a guarantee from here.
        //
        // The test asserts the current behaviour so a deliberate fix shows up as a failure here
        // rather than passing silently, and so the gap cannot be mistaken for covered.
        assertThat(ids(FutureData.getData(node.getValue()))).containsExactly("one");
    }

    @Test
    public void aRejectedConnectionReportsAnErrorRatherThanClearingSilently() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        MediaItemsLiveData root = mRepository.getRootMediaItems();

        mBrowsingStates.setValue(TestBrowsingStates.with(
                mSourceA, mBrowserA, ConnectionStatus.REJECTED, mPushesA));

        // REJECTED and SUSPENDED go through onBrowseData(rootId, null), which is the error shape.
        // That is deliberately NOT what DISCONNECTING does above, which clears to null outright,
        // and a change that merged the two paths would be invisible without both tests.
        assertThat(root.getValue()).isNotNull();
        assertThat(root.getValue().isLoading()).isFalse();
        assertThat(FutureData.getData(root.getValue())).isNull();
    }

    @Test
    public void reconnectingDoesNotReissueTheSearchQueryThatIsStillSet() {
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, mPushesA);
        stubSearch(mBrowserA, "rock").set(LibraryResult.ofVoid());
        stubSearchResult(mBrowserA, "rock").set(
                LibraryResult.ofItemList(ImmutableList.of(item("rock-1")), null));
        mRepository.setSearchQuery("rock");

        mBrowsingStates.setValue(TestBrowsingStates.with(
                mSourceA, mBrowserA, ConnectionStatus.SUSPENDED, mPushesA));
        connectWithRoot(mSourceA, mBrowserA, ROOT_A, new TestBrowsingStates.Pushes());

        // This pins a CHOICE, not an obvious correctness property, which is why it is spelled out.
        // After a reconnect the query string is still held, the results stay cleared, and nothing
        // is re-searched -- so the screen shows no results for a query the search box still
        // displays, until the user edits it. Defensible (a silent refetch on every reconnect is its
        // own surprise) but not self-evidently right, and changing it should be a decision rather
        // than an accident.
        // times(1), not never(): the query WAS searched once before the connection dropped, and
        // never() would have been satisfied by that invocation never happening either -- which is
        // a different bug and would have made this test pass for the wrong reason.
        verify(mBrowserA, times(1)).search(eq("rock"), any());
        assertThat(mRepository.getSearchMediaItems().getValue()).isNull();
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private void connect(MediaSource source, MediaBrowser browser,
            TestBrowsingStates.Pushes pushes) {
        mBrowsingStates.setValue(
                TestBrowsingStates.with(source, browser, ConnectionStatus.CONNECTED, pushes));
    }

    /** Connects and lets the root resolve, which is the state most tests start from. */
    private void connectWithRoot(MediaSource source, MediaBrowser browser, String rootId,
            TestBrowsingStates.Pushes pushes) {
        SettableFuture<LibraryResult<MediaItem>> root = stubRoot(browser);
        stubSubscribe(browser, rootId).set(LibraryResult.ofVoid());
        stubChildren(browser, rootId).set(LibraryResult.ofItemList(ImmutableList.of(), null));
        connect(source, browser, pushes);
        root.set(LibraryResult.ofItem(
                item(rootId, /* browsable= */ true, /* playable= */ false), null));
    }

    private static SettableFuture<LibraryResult<MediaItem>> stubRoot(MediaBrowser browser) {
        SettableFuture<LibraryResult<MediaItem>> future = SettableFuture.create();
        when(browser.getLibraryRoot(any())).thenReturn(future);
        return future;
    }

    private static SettableFuture<LibraryResult<Void>> stubSubscribe(MediaBrowser browser,
            String nodeId) {
        SettableFuture<LibraryResult<Void>> future = SettableFuture.create();
        when(browser.subscribe(eq(nodeId), any())).thenReturn(future);
        return future;
    }

    private static SettableFuture<LibraryResult<ImmutableList<MediaItem>>> stubChildren(
            MediaBrowser browser, String nodeId) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        when(browser.getChildren(eq(nodeId), anyInt(), anyInt(), any())).thenReturn(future);
        return future;
    }

    private static SettableFuture<LibraryResult<Void>> stubSearch(MediaBrowser browser,
            String query) {
        SettableFuture<LibraryResult<Void>> future = SettableFuture.create();
        when(browser.search(eq(query), any())).thenReturn(future);
        return future;
    }

    private static SettableFuture<LibraryResult<ImmutableList<MediaItem>>> stubSearchResult(
            MediaBrowser browser, String query) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        when(browser.getSearchResult(eq(query), anyInt(), anyInt(), any())).thenReturn(future);
        return future;
    }

    /**
     * A playable leaf.
     *
     * <p>Both flags are set explicitly because {@code LibraryResult} rejects an item whose metadata
     * leaves them unspecified -- {@code verifyMediaItem} throws rather than defaulting. That is a
     * real contract of the API this code now depends on, so the fixture satisfies it the way a
     * browse service would.
     */
    private static MediaItem item(String id) {
        return item(id, /* browsable= */ false, /* playable= */ true);
    }

    private static MediaItem item(String id, boolean browsable, boolean playable) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setIsBrowsable(browsable)
                        .setIsPlayable(playable)
                        .build())
                .build();
    }

    private static List<String> ids(List<MediaItemMetadata> items) {
        List<String> out = new ArrayList<>();
        if (items != null) {
            for (MediaItemMetadata item : items) {
                out.add(item.getId());
            }
        }
        return out;
    }
}
