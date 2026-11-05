/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.media.common.browse;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;

import static java.util.stream.Collectors.toList;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.MediaItem;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;

import com.android.car.apps.common.util.FutureData;
import com.android.car.media.common.Media3Adapters;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;


/**
 * Fulfills media items search and children queries. The latter also provides the last list of
 * results alongside the new list so that differences can be calculated and acted upon.
 *
 * <h2>Media3 migration, stage 2: three synchronous calls became futures</h2>
 *
 * <p>The legacy client answered browse queries through callbacks registered on the browser, and one
 * of them — {@code getRoot()} — answered synchronously. Media3 answers all three with a
 * {@link ListenableFuture}:
 *
 * <ul>
 *   <li>{@code getRoot()} → {@link MediaBrowser#getLibraryRoot}
 *   <li>{@code subscribe(id, SubscriptionCallback)} → {@link MediaBrowser#subscribe} followed by
 *       {@link MediaBrowser#getChildren}
 *   <li>{@code search(q, extras, SearchCallback)} → {@link MediaBrowser#search} followed by
 *       {@link MediaBrowser#getSearchResult}
 * </ul>
 *
 * <p>Subscribing is still done, and still unconditionally re-done, for two reasons that survive the
 * port: it is what works around media apps that only push updates to subscribers, and in media3's
 * legacy bridge the subscription is also the cache {@code getChildren} is served from, so
 * subscribing first turns the second call into a local read rather than a second round trip.
 * Ongoing pushes arrive as {@code MediaBrowser.Listener#onChildrenChanged}, which the browser
 * delivers to its single listener — owned by the connector — and which reaches this class through
 * {@link MediaBrowserConnector.BrowserEventListener}.
 *
 * <h2>Every completion is checked against the connection that started it</h2>
 *
 * <p>Nothing in the legacy version needed this: its callbacks were owned by a browser that was
 * discarded with its connection. A future is not, so a response belonging to a media source the
 * user has already navigated away from can land in {@link #onBrowseData} and populate the CURRENT
 * source's cache with the previous source's items. {@link #isStale} is the guard, and it is the
 * same argument as the connector's sequence counter.
 */
public class MediaItemsRepository {
    private static final String TAG = "MediaItemsRepository";

    /**
     * Legacy {@code subscribe} had no paging: the service returned every child. Media3's
     * {@code getChildren} requires a page and a size, so "all of them" has to be spelled out.
     *
     * <p>Both halves were checked against the bytecode rather than assumed, because
     * {@code Integer.MAX_VALUE} is the obvious way to say "everything" and also the obvious way to
     * overflow someone else's arithmetic:
     *
     * <ul>
     *   <li>Media3's legacy bridge serves a subscribed node from its cached child list whenever the
     *       requested PAGE matches the subscription's — {@code canServeGetChildrenRequest} compares
     *       the page and nothing else — so at page 0 the size is not consulted at all and the whole
     *       list comes back.
     *   <li>When it does go to the service, {@code MediaBrowserServiceCompat.applyOptions} computes
     *       {@code from = page * pageSize} and {@code to = from + pageSize}. At page 0 that is
     *       {@code 0} and {@code Integer.MAX_VALUE} — no overflow — and {@code to} is then clamped
     *       to the list size.
     * </ul>
     */
    private static final int PAGE_INDEX = 0;
    private static final int PAGE_SIZE = Integer.MAX_VALUE;

    /** One instance per MEDIA_SOURCE_MODE. */
    private static MediaItemsRepository[] sInstances = new MediaItemsRepository[2];

    /** Returns the MediaItemsRepository "singleton" tied to the application for the given mode. */
    public static MediaItemsRepository get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new MediaItemsRepository(
                    MediaSourceViewModel.get(application, mode).getBrowsingState());
        }
        return sInstances[mode];
    }

    /** The live data providing the updates for a query. */
    public static class MediaItemsLiveData
            extends LiveData<FutureData<List<MediaItemMetadata>>> {

        private MediaItemsLiveData() {
            this(true);
        }

        private MediaItemsLiveData(boolean initAsLoading) {
            if (initAsLoading) {
                setLoading();
            } else {
                clear();
            }
        }

        private void onDataLoaded(List<MediaItemMetadata> old, List<MediaItemMetadata> list) {
            setValue(FutureData.newLoadedData(old, list));
        }

        private void setLoading() {
            setValue(FutureData.newLoadingData());
        }

        private void clear() {
            setValue(null);
        }
    }

    private static class MediaChildren {
        final String mNodeId;
        final MediaItemsLiveData mLiveData = new MediaItemsLiveData();
        List<MediaItemMetadata> mPreviousValue = Collections.emptyList();
        int mRequestGeneration;

        MediaChildren(String nodeId) {
            mNodeId = nodeId;
        }
    }

    private static class PerMediaSourceCache {
        String mRootId;
        Map<String, MediaChildren> mChildrenByNodeId = new HashMap<>();
    }

    private BrowsingState mBrowsingState;
    private final Map<MediaSource, PerMediaSourceCache> mCaches = new HashMap<>();
    private final MutableLiveData<BrowsingState> mBrowsingStateLiveData = dataOf(null);
    private final MediaItemsLiveData mRootMediaItems = new MediaItemsLiveData();
    private final MediaItemsLiveData mSearchMediaItems = new MediaItemsLiveData(/*loading*/ false);

    private String mSearchQuery;

    /**
     * Re-broadcast browse pushes. Registered once per connection, on CONNECTED.
     *
     * <p>{@code onChildrenChanged} intentionally re-reads through {@link #getChildrenFor} rather
     * than trusting the item count media3 hands it: the count is a hint, the list is the answer,
     * and the legacy contract this replaces delivered the list itself.
     */
    private final MediaBrowserConnector.BrowserEventListener mBrowserEvents =
            new MediaBrowserConnector.BrowserEventListener() {
                @Override
                public void onChildrenChanged(@NonNull String parentId) {
                    BrowsingState state = mBrowsingState;
                    if (state == null || state.mBrowser == null) return;
                    if (!getCache().mChildrenByNodeId.containsKey(parentId)) {
                        // Not a node this repository is showing; nothing to update.
                        return;
                    }
                    requestChildren(state, parentId);
                }

                @Override
                public void onSearchResultChanged(@NonNull String query) {
                    BrowsingState state = mBrowsingState;
                    if (state == null || state.mBrowser == null) return;
                    if (!Objects.equals(mSearchQuery, query)) return;
                    getSearchResultsFor(state, query);
                }
            };

    @VisibleForTesting
    public MediaItemsRepository(LiveData<BrowsingState> browsingState) {
        browsingState.observeForever(this::onMediaBrowsingStateChanged);
    }

    /**
     * Rebroadcasts browsing state changes before the repository takes any action on them.
     */
    public LiveData<BrowsingState> getBrowsingState() {
        return mBrowsingStateLiveData;
    }

    /**
     * Convenience wrapper for root media items. The live data is the same instance for all
     * media sources.
     */
    public MediaItemsLiveData getRootMediaItems() {
        return mRootMediaItems;
    }

    /**
     * Returns the results from the current search query. The live data is the same instance
     * for all media sources.
     */
    public MediaItemsLiveData getSearchMediaItems() {
        return mSearchMediaItems;
    }

    /** Returns the children of the given node. */
    public MediaItemsLiveData getMediaChildren(String nodeId) {
        PerMediaSourceCache cache = getCache();
        MediaChildren items = cache.mChildrenByNodeId.get(nodeId);
        if (items == null) {
            items = new MediaChildren(nodeId);
            cache.mChildrenByNodeId.put(nodeId, items);
        }

        BrowsingState state = mBrowsingState;
        MediaBrowser browser = (state != null) ? state.mBrowser : null;
        if (browser != null) {
            final MediaChildren requested = items;
            final int generation = ++requested.mRequestGeneration;
            // Always refresh the subscription (to work around bugs in media apps).
            browser.unsubscribe(nodeId);
            addCallback(browser.subscribe(nodeId, null), result -> {
                if (isStale(state) || requested.mRequestGeneration != generation) return;
                if (result.resultCode == LibraryResult.RESULT_SUCCESS) {
                    getChildrenFor(state, requested, generation);
                } else {
                    // The legacy SubscriptionCallback#onError equivalent.
                    onBrowseData(nodeId, null);
                }
            }, () -> {
                if (!isStale(state) && requested.mRequestGeneration == generation) {
                    onBrowseData(nodeId, null);
                }
            });
        }

        return items.mLiveData;
    }

    /** Sets the search query. Results will be given through {@link #getSearchMediaItems}. */
    public void setSearchQuery(String query) {
        mSearchQuery = query;
        if (TextUtils.isEmpty(mSearchQuery)) {
            clearSearchResults();
        } else {
            mSearchMediaItems.setLoading();
            BrowsingState state = mBrowsingState;
            MediaBrowser browser = (state != null) ? state.mBrowser : null;
            if (browser == null) {
                onSearchData(null);
                return;
            }
            String query1 = mSearchQuery;
            addCallback(browser.search(query1, null), result -> {
                if (isStale(state) || !Objects.equals(mSearchQuery, query1)) return;
                if (result.resultCode == LibraryResult.RESULT_SUCCESS) {
                    getSearchResultsFor(state, query1);
                } else {
                    onSearchData(null);
                }
            }, () -> {
                if (!isStale(state) && Objects.equals(mSearchQuery, query1)) onSearchData(null);
            });
        }
    }

    private void requestChildren(@NonNull BrowsingState state, @NonNull String nodeId) {
        MediaChildren children = getCache().mChildrenByNodeId.get(nodeId);
        if (children == null) return;
        int generation = ++children.mRequestGeneration;
        getChildrenFor(state, children, generation);
    }

    private void getChildrenFor(@NonNull BrowsingState state,
            @NonNull MediaChildren children, int generation) {
        MediaBrowser browser = state.mBrowser;
        if (browser == null) return;
        addCallback(browser.getChildren(children.mNodeId, PAGE_INDEX, PAGE_SIZE, null), result -> {
            if (isStale(state) || children.mRequestGeneration != generation) return;
            onBrowseData(children.mNodeId, toMetadata(result));
        }, () -> {
            if (!isStale(state) && children.mRequestGeneration == generation) {
                onBrowseData(children.mNodeId, null);
            }
        });
    }

    private void getSearchResultsFor(@NonNull BrowsingState state, @NonNull String query) {
        MediaBrowser browser = state.mBrowser;
        if (browser == null) return;
        addCallback(browser.getSearchResult(query, PAGE_INDEX, PAGE_SIZE, null), result -> {
            if (isStale(state) || !Objects.equals(mSearchQuery, query)) return;
            onSearchData(toMetadata(result));
        }, () -> {
            if (!isStale(state) && Objects.equals(mSearchQuery, query)) onSearchData(null);
        });
    }

    /**
     * Maps a library result onto the legacy callback pair: a list on success, {@code null} on
     * anything else. {@code null} is not "empty" here — it is what the legacy {@code onError}
     * delivered, and downstream distinguishes the two.
     */
    @Nullable
    private static List<MediaItemMetadata> toMetadata(
            @NonNull LibraryResult<? extends List<MediaItem>> result) {
        if (result.resultCode != LibraryResult.RESULT_SUCCESS || result.value == null) {
            return null;
        }
        return result.value.stream()
                .filter(Objects::nonNull)
                .map(Media3Adapters::fromMedia3)
                .collect(toList());
    }

    private static <T> void addCallback(@NonNull ListenableFuture<T> future,
            @NonNull Consumer<T> onSuccess, @NonNull Runnable onFailure) {
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                if (result == null) {
                    onFailure.run();
                } else {
                    onSuccess.accept(result);
                }
            }

            @Override
            public void onFailure(@NonNull Throwable t) {
                Log.e(TAG, "Browse request failed: " + t);
                onFailure.run();
            }
        }, MediaBrowserConnector.CALLBACK_EXECUTOR);
    }

    /** True when the connection that issued a request is no longer the current one. */
    private boolean isStale(@NonNull BrowsingState state) {
        return mBrowsingState != state;
    }

    private void clearSearchResults() {
        mSearchMediaItems.clear();
    }

    private MediaSource getMediaSource() {
        return (mBrowsingState != null) ? mBrowsingState.mMediaSource : null;
    }

    private void onMediaBrowsingStateChanged(BrowsingState newBrowsingState) {
        mBrowsingState = newBrowsingState;
        if (mBrowsingState == null) {
            Log.e(TAG, "Null browsing state (no media source!)");
            return;
        }
        mBrowsingStateLiveData.setValue(mBrowsingState);
        switch (mBrowsingState.mConnectionStatus) {
            case CONNECTING:
                mRootMediaItems.setLoading();
                break;
            case CONNECTED:
                BrowsingState connected = mBrowsingState;
                MediaBrowser browser = connected.mBrowser;
                if (browser == null) {
                    Log.e(TAG, "CONNECTED without a browser!");
                    break;
                }
                connected.addBrowserEventListener(mBrowserEvents);
                // getRoot() was synchronous; getLibraryRoot() is not, so the root id — and every
                // cache entry keyed off it — now arrives later than the state that announced it.
                addCallback(browser.getLibraryRoot(null), result -> {
                    if (isStale(connected)) return;
                    String rootId = (result.resultCode == LibraryResult.RESULT_SUCCESS
                            && result.value != null) ? result.value.mediaId : null;
                    if (rootId == null) {
                        Log.e(TAG, "Browse service returned no root");
                        mRootMediaItems.onDataLoaded(Collections.emptyList(), null);
                        return;
                    }
                    getCache().mRootId = rootId;
                    getMediaChildren(rootId);
                }, () -> {
                    if (!isStale(connected)) {
                        mRootMediaItems.onDataLoaded(Collections.emptyList(), null);
                    }
                });
                break;
            case DISCONNECTING:
                unsubscribeNodes();
                clearSearchResults();
                clearNodes();
                break;
            case REJECTED:
            case SUSPENDED:
                onBrowseData(getCache().mRootId, null);
                clearSearchResults();
                clearNodes();
        }
    }

    private PerMediaSourceCache getCache() {
        PerMediaSourceCache cache = mCaches.get(getMediaSource());
        if (cache == null) {
            cache = new PerMediaSourceCache();
            mCaches.put(getMediaSource(), cache);
        }
        return cache;
    }

    /** Does NOT clear the cache. */
    private void unsubscribeNodes() {
        PerMediaSourceCache cache = getCache();
        MediaBrowser browser = (mBrowsingState != null) ? mBrowsingState.mBrowser : null;
        if (browser == null) return;
        for (String nodeId : cache.mChildrenByNodeId.keySet()) {
            browser.unsubscribe(nodeId);
        }
    }

    /** Does NOT unsubscribe nodes. */
    private void clearNodes() {
        PerMediaSourceCache cache = getCache();
        cache.mChildrenByNodeId.clear();
    }

    private void onBrowseData(@Nullable String parentId, @Nullable List<MediaItemMetadata> list) {
        if (parentId == null) {
            return;
        }
        PerMediaSourceCache cache = getCache();
        MediaChildren children = cache.mChildrenByNodeId.get(parentId);
        if (children == null) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Browse parent not in the cache: " + parentId);
            }
            return;
        }

        List<MediaItemMetadata> old = children.mPreviousValue;
        children.mPreviousValue = list;
        children.mLiveData.onDataLoaded(old, list);

        if (Objects.equals(parentId, cache.mRootId)) {
            mRootMediaItems.onDataLoaded(old, list);
        }
    }

    private void onSearchData(@Nullable List<MediaItemMetadata> list) {
        mSearchMediaItems.onDataLoaded(null, list);
    }
}
