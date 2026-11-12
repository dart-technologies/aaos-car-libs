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
 * The anchor catalog: a browsable root with the playlist plus three playable tracks, item
 * lookup, title search, and paging that refuses nonsense instead of throwing.
 */
@RunWith(RobolectricTestRunner.class)
public class AnchoredMediaCatalogTest {

    private static AnchoredMediaCatalog catalog() {
        return new AnchoredMediaCatalog("art.dart.a2ui.carapp");
    }

    @Test
    public void rootHoldsPlaylistPlusTracksAndPlaylistHoldsTracks() {
        MediaCatalog catalog = catalog();

        assertThat(catalog.libraryRoot().mediaId).isEqualTo(AnchoredMediaCatalog.ROOT_ID);
        assertThat(ids(catalog.children(AnchoredMediaCatalog.ROOT_ID, 0, 10)))
                .containsExactly("anchored_fm", "track_loop", "track_shuffle", "track_blues")
                .inOrder();
        assertThat(ids(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, 0, 10)))
                .containsExactly("track_loop", "track_shuffle", "track_blues")
                .inOrder();
    }

    @Test
    public void tracksArePlayableWithDistinctUris() {
        MediaCatalog catalog = catalog();

        List<String> uris = new ArrayList<>();
        for (String id : new String[]{"track_loop", "track_shuffle", "track_blues"}) {
            MediaItem track = catalog.item(id);
            assertThat(track).isNotNull();
            assertThat(track.mediaMetadata.isPlayable).isTrue();
            assertThat(track.mediaMetadata.isBrowsable).isFalse();
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
    public void searchMatchesTitlesOnly() {
        MediaCatalog catalog = catalog();

        assertThat(ids(catalog.search("park", 0, 10))).containsExactly("track_shuffle");
        assertThat(ids(catalog.search("CARMINE", 0, 10))).containsExactly("track_blues");
        // The playlist title matches nothing playable; the artist is not searched.
        assertThat(catalog.search("anchored", 0, 10)).isEmpty();
        assertThat(catalog.search("   ", 0, 10)).isEmpty();
    }

    @Test
    public void pagingSlicesAndRefusesNonsense() {
        MediaCatalog catalog = catalog();

        assertThat(ids(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, 0, 2)))
                .containsExactly("track_loop", "track_shuffle").inOrder();
        assertThat(ids(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, 1, 2)))
                .containsExactly("track_blues");
        assertThat(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, 5, 2)).isEmpty();
        assertThat(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, -1, 2)).isEmpty();
        assertThat(catalog.children(AnchoredMediaCatalog.PLAYLIST_ID, 0, 0)).isEmpty();
    }

    private static List<String> ids(List<MediaItem> items) {
        List<String> ids = new ArrayList<>();
        for (MediaItem item : items) {
            ids.add(item.mediaId);
        }
        return ids;
    }
}
