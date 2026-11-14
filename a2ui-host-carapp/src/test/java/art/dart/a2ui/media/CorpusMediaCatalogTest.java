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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MediaItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * The corpus as a browse tree: one playlist per {@code MEDIA_PLAYBACK} case in corpus order, one
 * playable track per audio player, bytes from the demo bed. {@code now_playing} carries transport
 * for an external player and no audio of its own, so its playlist browses empty — pinned, not
 * papered over.
 */
@RunWith(RobolectricTestRunner.class)
public class CorpusMediaCatalogTest {

    private static CorpusMediaCatalog catalog() {
        return new CorpusMediaCatalog("art.dart.a2ui.carapp");
    }

    @Test
    public void rootHoldsOnePlaylistPerMediaCaseInCorpusOrder() {
        MediaCatalog catalog = catalog();

        assertThat(catalog.libraryRoot().mediaId).isEqualTo(CorpusMediaCatalog.ROOT_ID);
        assertThat(ids(catalog.children(CorpusMediaCatalog.ROOT_ID, 0, 10)))
                .containsExactly("now_playing", "video_trailer", "big_buck_bunny", "met_visit")
                .inOrder();
    }

    @Test
    public void playlistsAreBrowsableAndTracksArePlayable() {
        MediaCatalog catalog = catalog();

        MediaItem playlist = catalog.item("video_trailer");
        assertThat(playlist.mediaMetadata.isBrowsable).isTrue();
        assertThat(playlist.mediaMetadata.isPlayable).isFalse();
        assertThat(playlist.mediaMetadata.title.toString()).isEqualTo("Album trailer");

        assertThat(ids(catalog.children("video_trailer", 0, 10)))
                .containsExactly("video_trailer/t0");
        MediaItem track = catalog.item("video_trailer/t0");
        assertThat(track.mediaMetadata.isBrowsable).isFalse();
        assertThat(track.mediaMetadata.isPlayable).isTrue();
        assertThat(track.mediaMetadata.title.toString()).isEqualTo("Soundtrack");
    }

    @Test
    public void transportWithoutAudioBrowsesEmpty() {
        MediaCatalog catalog = catalog();

        assertThat(catalog.children("now_playing", 0, 10)).isEmpty();
    }

    @Test
    public void tracksPlayDemoBedAudioWithDistinctUris() {
        MediaCatalog catalog = catalog();

        List<String> uris = new ArrayList<>();
        for (String id : new String[]{"video_trailer/t0", "big_buck_bunny/t0", "met_visit/t0"}) {
            MediaItem track = catalog.item(id);
            assertThat(track).isNotNull();
            assertThat(track.localConfiguration).isNotNull();
            uris.add(track.localConfiguration.uri.toString());
        }
        assertThat(uris).containsNoDuplicates();
        for (String uri : uris) {
            assertThat(uri).startsWith("android.resource://art.dart.a2ui.carapp/raw/");
        }
    }

    @Test
    public void unknownIdsYieldNullAndEmpty() {
        MediaCatalog catalog = catalog();

        assertThat(catalog.item("nope")).isNull();
        assertThat(catalog.children("nope", 0, 10)).isEmpty();
    }

    @Test
    public void searchMatchesPlayableTitlesOnly() {
        MediaCatalog catalog = catalog();

        assertThat(ids(catalog.search("soundtrack", 0, 10)))
                .containsExactly("video_trailer/t0");
        assertThat(ids(catalog.search("AUDIO GUIDE", 0, 10))).containsExactly("met_visit/t0");
        // Playlist titles match nothing playable; the artist is not searched.
        assertThat(catalog.search("trailer", 0, 10)).isEmpty();
        assertThat(catalog.search("   ", 0, 10)).isEmpty();
    }

    @Test
    public void pagingSlicesAndRefusesNonsense() {
        MediaCatalog catalog = catalog();

        assertThat(ids(catalog.children(CorpusMediaCatalog.ROOT_ID, 0, 2)))
                .containsExactly("now_playing", "video_trailer").inOrder();
        assertThat(ids(catalog.children(CorpusMediaCatalog.ROOT_ID, 1, 2)))
                .containsExactly("big_buck_bunny", "met_visit").inOrder();
        assertThat(catalog.children(CorpusMediaCatalog.ROOT_ID, 5, 2)).isEmpty();
        assertThat(catalog.children(CorpusMediaCatalog.ROOT_ID, -1, 2)).isEmpty();
        assertThat(catalog.children(CorpusMediaCatalog.ROOT_ID, 0, 0)).isEmpty();
    }

    private static List<String> ids(List<MediaItem> items) {
        List<String> ids = new ArrayList<>();
        for (MediaItem item : items) {
            ids.add(item.mediaId);
        }
        return ids;
    }
}
