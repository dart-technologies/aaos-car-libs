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

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Sprint 2 anchor catalog: one playlist, three silent tracks of distinct lengths (3/4/5 s)
 * from {@code res/raw}, so track identity is observable in playback state. A real provider
 * replaces this class, not the service.
 */
public final class AnchoredMediaCatalog implements MediaCatalog {

    public static final String ROOT_ID = "root";
    public static final String PLAYLIST_ID = "anchored_fm";

    private final Map<String, MediaItem> mItems = new LinkedHashMap<>();
    private final Map<String, List<String>> mChildren = new LinkedHashMap<>();

    public AnchoredMediaCatalog(@NonNull String packageName) {
        addTrack("track_loop", "Downtown Loop", trackUri(packageName, "anchored_loop"));
        addTrack("track_shuffle", "Park Ave Shuffle", trackUri(packageName, "park_ave_shuffle"));
        addTrack("track_blues", "Carmine St Blues", trackUri(packageName, "carmine_blues"));
        MediaItem playlist = new MediaItem.Builder()
                .setMediaId(PLAYLIST_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("Anchored FM")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                .build();
        mItems.put(PLAYLIST_ID, playlist);
        // The playlist holds the three tracks; the root holds the playlist plus the tracks, so
        // shallow browsers and deep browsers both find something playable.
        mChildren.put(PLAYLIST_ID, trackIds());
        List<String> root = new ArrayList<>();
        root.add(PLAYLIST_ID);
        root.addAll(trackIds());
        mChildren.put(ROOT_ID, root);
        mItems.put(ROOT_ID, new MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("Anchored")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                .build());
    }

    @NonNull
    @Override
    public MediaItem libraryRoot() {
        return mItems.get(ROOT_ID);
    }

    @NonNull
    @Override
    public List<MediaItem> children(@NonNull String parentId, int page, int pageSize) {
        List<String> ids = mChildren.get(parentId);
        if (ids == null) {
            return Collections.emptyList();
        }
        List<MediaItem> items = new ArrayList<>();
        for (String id : ids) {
            items.add(mItems.get(id));
        }
        return paged(items, page, pageSize);
    }

    @Nullable
    @Override
    public MediaItem item(@NonNull String mediaId) {
        return mItems.get(mediaId);
    }

    @NonNull
    @Override
    public List<MediaItem> search(@NonNull String query, int page, int pageSize) {
        String needle = query.toLowerCase(Locale.US).trim();
        List<MediaItem> matches = new ArrayList<>();
        if (!needle.isEmpty()) {
            for (MediaItem item : mItems.values()) {
                CharSequence title = item.mediaMetadata != null
                        ? item.mediaMetadata.title : null;
                if (title != null && item.mediaMetadata.isPlayable
                        && title.toString().toLowerCase(Locale.US).contains(needle)) {
                    matches.add(item);
                }
            }
        }
        return paged(matches, page, pageSize);
    }

    private void addTrack(String id, String title, Uri uri) {
        mItems.put(id, new MediaItem.Builder()
                .setMediaId(id)
                .setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("Anchored")
                        .setAlbumTitle("Anchored FM")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build())
                .build());
    }

    private List<String> trackIds() {
        List<String> ids = new ArrayList<>();
        ids.add("track_loop");
        ids.add("track_shuffle");
        ids.add("track_blues");
        return ids;
    }

    private static Uri trackUri(String packageName, String rawName) {
        return Uri.parse("android.resource://" + packageName + "/raw/" + rawName);
    }

    private static List<MediaItem> paged(List<MediaItem> items, int page, int pageSize) {
        if (page < 0 || pageSize <= 0) {
            return Collections.emptyList();
        }
        int from = page * pageSize;
        if (from >= items.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(items.subList(from, Math.min(items.size(), from + pageSize)));
    }
}
