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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import art.dart.a2ui.demo.DemoTrace;
import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.A2uiComponent;
import art.dart.a2ui.model.ComponentType;
import art.dart.a2ui.protocol.A2uiMessageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The corpus as a browse tree: one playlist per {@code MEDIA_PLAYBACK} case, one playable track
 * per audio player in it, projected through the real processor. Structure comes from the corpus;
 * bytes come from the demo bed ({@code res/raw} silent tracks), because the wire carries titles,
 * not audio. A case that fails to project is skipped, not fatal: one bad recording costs that
 * playlist rather than the session.
 */
public final class CorpusMediaCatalog implements MediaCatalog {

    private static final String TAG = "CorpusMediaCatalog";

    public static final String ROOT_ID = "root";

    /** The demo bed, in rotation: distinct lengths keep track identity observable in playback. */
    private static final String[] BED_TRACKS = {"anchored_loop", "park_ave_shuffle",
            "carmine_blues"};

    private final Map<String, MediaItem> mItems = new LinkedHashMap<>();
    private final Map<String, List<String>> mChildren = new LinkedHashMap<>();

    public CorpusMediaCatalog(@NonNull String packageName) {
        List<String> root = new ArrayList<>();
        int bed = 0;
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            if (!useCase.getTemplateCapabilities()
                    .contains(UseCaseCorpus.TemplateCapability.MEDIA_PLAYBACK)) {
                continue;
            }
            A2uiComponent projected = project(useCase);
            if (projected == null) {
                continue;
            }
            String playlistId = useCase.getId();
            String playlistTitle = projected.getTitle() != null ? projected.getTitle()
                    : playlistId;
            mItems.put(playlistId, new MediaItem.Builder()
                    .setMediaId(playlistId)
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(playlistTitle)
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build())
                    .build());
            List<String> tracks = new ArrayList<>();
            List<A2uiComponent> audio = new ArrayList<>();
            collectAudio(projected, audio);
            for (int i = 0; i < audio.size(); i++) {
                A2uiComponent player = audio.get(i);
                String trackId = playlistId + "/t" + i;
                String trackTitle = player.getTitle() != null ? player.getTitle() : trackId;
                mItems.put(trackId, new MediaItem.Builder()
                        .setMediaId(trackId)
                        .setUri(trackUri(packageName, BED_TRACKS[bed % BED_TRACKS.length]))
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(trackTitle)
                                .setArtist(playlistTitle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build())
                        .build());
                tracks.add(trackId);
                bed++;
            }
            mChildren.put(playlistId, tracks);
            root.add(playlistId);
        }
        mChildren.put(ROOT_ID, root);
        mItems.put(ROOT_ID, new MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("Corpus")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                .build());
    }

    /** Projects one case, or null when the recording does not survive the processor. */
    private static A2uiComponent project(UseCaseCorpus.UseCase useCase) {
        try {
            A2uiMessageProcessor processor = DemoTrace.newProcessor();
            processor.processAll(useCase.getMessages());
            return processor.getSurface(useCase.getId()).project(DemoTrace.ROOT_ID);
        } catch (RuntimeException e) {
            Log.w(TAG, "case " + useCase.getId() + " did not project; skipping", e);
            return null;
        }
    }

    private static void collectAudio(A2uiComponent node, List<A2uiComponent> out) {
        if (node.getType() == ComponentType.AUDIO_PLAYER) {
            out.add(node);
        }
        for (A2uiComponent child : node.getChildren()) {
            collectAudio(child, out);
        }
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
