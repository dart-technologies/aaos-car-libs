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
package art.dart.a2ui.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.SessionError;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/**
 * Host A's media source: a library session over ExoPlayer, serving the {@link MediaCatalog}.
 * The callbacks are deliberately thin — root, children, item, search, nothing else — so the
 * future templated-media track reuses the catalog behind a new session without touching this
 * service. Playback state (play/pause) is the player's; browse/search answers are the
 * catalog's; this class only joins them.
 */
public class A2uiMediaLibraryService extends MediaLibraryService {

    private MediaLibrarySession mSession;
    private Player mPlayer;
    private MediaCatalog mCatalog;

    @Override
    public void onCreate() {
        super.onCreate();
        mCatalog = new AnchoredMediaCatalog(getPackageName());
        mPlayer = new ExoPlayer.Builder(this).build();
        mSession = new MediaLibrarySession.Builder(this, mPlayer, new CatalogCallback())
                .setId("a2ui-media")
                .build();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mSession;
    }

    @Override
    public void onDestroy() {
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        super.onDestroy();
    }

    /** The catalog this session serves. */
    @NonNull
    @VisibleForTesting
    MediaCatalog catalogForTest() {
        return mCatalog;
    }

    private final class CatalogCallback implements MediaLibrarySession.Callback {
        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser,
                @Nullable LibraryParams params) {
            return Futures.immediateFuture(
                    LibraryResult.ofItem(mCatalog.libraryRoot(), params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<
                MediaItem>>> onGetChildren(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId,
                int page, int pageSize, @Nullable LibraryParams params) {
            List<MediaItem> children =
                    mCatalog.children(parentId, page, pageSize);
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String mediaId) {
            MediaItem item = mCatalog.item(mediaId);
            if (item == null) {
                return Futures.immediateFuture(
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
            }
            return Futures.immediateFuture(LibraryResult.ofItem(item, null));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query,
                @Nullable LibraryParams params) {
            // Stateless: the query runs again in onGetSearchResult, so no session state to keep.
            return Futures.immediateFuture(
                    LibraryResult.ofVoid());
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<
                MediaItem>>> onGetSearchResult(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query,
                int page, int pageSize, @Nullable LibraryParams params) {
            return Futures.immediateFuture(
                    LibraryResult.ofItemList(mCatalog.search(query, page, pageSize), params));
        }
    }
}
