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
package com.android.car.media.common;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The first stage of the Media3 migration, stated as an EQUIVALENCE rather than as a new feature.
 *
 * <p>The legacy path is in production and already tested, so it is the reference. Every assertion
 * here is of the form "the Media3 item and its legacy twin are indistinguishable once converted" —
 * which is the only claim that makes a later stage safe to attempt, because a later stage replaces
 * the legacy side and needs something to have been checked against.
 *
 * <p>A test asserting only that the Media3 converter produces *reasonable-looking* values would
 * pass against a converter that quietly disagreed with the legacy one, and the disagreement would
 * surface as a metadata field going blank on a head unit.
 */
@RunWith(RobolectricTestRunner.class)
public class Media3AdaptersTest {

    private static final String ID = "media-id-1";
    private static final String TITLE = "Song title";
    private static final String SUBTITLE = "Song subtitle";
    private static final String ALBUM = "Album name";
    private static final String ARTIST = "Artist name";
    private static final Uri ARTWORK = Uri.parse("content://art/1");

    // ===========================================================================================
    // Browse items.
    // ===========================================================================================

    /** A Media3 browsable item and a legacy browsable item convert to the same thing. */
    @Test
    public void browsableItem_matchesTheLegacyPath() {
        MediaItemMetadata fromMedia3 = Media3Adapters.fromMedia3(media3Item(true, false));
        MediaItemMetadata fromLegacy = new MediaItemMetadata(
                legacyItem(MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        assertSameSurface(fromMedia3, fromLegacy);
        assertThat(fromMedia3.isBrowsable()).isTrue();
        assertThat(fromMedia3.isPlayable()).isFalse();
    }

    @Test
    public void playableItem_matchesTheLegacyPath() {
        MediaItemMetadata fromMedia3 = Media3Adapters.fromMedia3(media3Item(false, true));
        MediaItemMetadata fromLegacy = new MediaItemMetadata(
                legacyItem(MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));

        assertSameSurface(fromMedia3, fromLegacy);
        assertThat(fromMedia3.isPlayable()).isTrue();
        assertThat(fromMedia3.isBrowsable()).isFalse();
    }

    /**
     * Unset means FALSE, matching a legacy item with no flag bits.
     *
     * <p>Media3 makes these {@code Boolean}, so "the app did not say" is representable in a way the
     * legacy {@code int} flags could not express. Mapping unset to true would invent a capability
     * the browse service never advertised — a browsable-looking row that opens nothing.
     */
    @Test
    public void unsetBrowsableAndPlayableAreFalse_asTheyAreLegacy() {
        MediaItem noFlags = new MediaItem.Builder()
                .setMediaId(ID)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(TITLE).build())
                .build();

        MediaItemMetadata fromMedia3 = Media3Adapters.fromMedia3(noFlags);
        MediaItemMetadata fromLegacy = new MediaItemMetadata(legacyItem(0));

        assertThat(fromMedia3.isBrowsable()).isEqualTo(fromLegacy.isBrowsable());
        assertThat(fromMedia3.isPlayable()).isEqualTo(fromLegacy.isPlayable());
        assertThat(fromMedia3.isBrowsable()).isFalse();
        assertThat(fromMedia3.isPlayable()).isFalse();
    }

    /**
     * <b>Where the equivalence STOPS, and it is a migration hazard rather than a detail.</b>
     *
     * <p>A Media3 browse item can carry album and artist. A legacy {@code
     * MediaBrowserCompat.MediaItem} cannot: it holds only a {@code MediaDescriptionCompat}, and
     * {@code MediaItemMetadata}'s legacy browse constructor therefore passes {@code null, null}
     * for both, unconditionally. Media3 carries strictly MORE here.
     *
     * <p>So migrating the browse path does not merely preserve behaviour — it starts populating two
     * fields that were always null before. Any caller branching on {@code getAlbumTitle() == null}
     * to mean "this is a browse row" changes behaviour silently. Recorded as an assertion rather
     * than as a comment so the divergence is pinned rather than remembered.
     *
     * <p>Found by mutation: {@code Media3 conversion drops album and artist} came back MISSED,
     * because the only album/artist assertion in this suite was on the now-playing path, and the
     * browse fixtures set neither — so the browse mapping was uncovered and its divergence from
     * legacy was unstated.
     */
    @Test
    public void aMedia3BrowseItemCarriesAlbumAndArtist_whichTheLegacyPathStructurallyCannot() {
        MediaItem rich = new MediaItem.Builder()
                .setMediaId(ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(TITLE)
                        .setIsBrowsable(true)
                        .setAlbumTitle(ALBUM)
                        .setArtist(ARTIST)
                        .build())
                .build();

        MediaItemMetadata fromMedia3 = Media3Adapters.fromMedia3(rich);
        MediaItemMetadata fromLegacy = new MediaItemMetadata(
                legacyItem(MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        assertThat(fromMedia3.getAlbumTitle()).isEqualTo(ALBUM);
        assertThat(fromMedia3.getArtist().toString()).isEqualTo(ARTIST);

        // The legacy twin, given the same intent, cannot express it — both are null by
        // construction, not by omission in this fixture.
        assertThat(fromLegacy.getAlbumTitle()).isNull();
        assertThat(fromLegacy.getArtist()).isNull();
    }

    // ===========================================================================================
    // Now-playing metadata.
    // ===========================================================================================

    @Test
    public void nowPlayingMetadata_matchesTheLegacyPath() {
        MediaMetadata media3 = new MediaMetadata.Builder()
                .setTitle(TITLE)
                .setSubtitle(SUBTITLE)
                .setAlbumTitle(ALBUM)
                .setArtist(ARTIST)
                .build();
        MediaMetadataCompat legacy = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ID)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, TITLE)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, SUBTITLE)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, ALBUM)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ARTIST)
                .build();

        MediaItemMetadata fromMedia3 = Media3Adapters.fromMedia3(ID, media3);
        MediaItemMetadata fromLegacy = new MediaItemMetadata(legacy);

        assertSameSurface(fromMedia3, fromLegacy);
        // Album and artist are the two fields the legacy metadata constructor reads separately
        // from the description, so they are the ones a converter is most likely to drop.
        assertThat(fromMedia3.getAlbumTitle()).isEqualTo(ALBUM);
        assertThat(fromMedia3.getArtist().toString()).isEqualTo(ARTIST);
    }

    /** {@code displayTitle} wins over {@code title}, as it does on the legacy browse path. */
    @Test
    public void displayTitleTakesPrecedenceOverTitle() {
        MediaMetadata both = new MediaMetadata.Builder()
                .setTitle("canonical")
                .setDisplayTitle("for display")
                .build();

        assertThat(Media3Adapters.fromMedia3(ID, both).getTitle().toString())
                .isEqualTo("for display");
    }

    /** …and a metadata carrying only {@code title} still renders rather than going blank. */
    @Test
    public void titleAloneStillRenders() {
        MediaMetadata titleOnly = new MediaMetadata.Builder().setTitle("canonical").build();

        assertThat(Media3Adapters.fromMedia3(ID, titleOnly).getTitle().toString())
                .isEqualTo("canonical");
    }

    @Test
    public void artworkAndExtrasSurvive() {
        Bundle extras = new Bundle();
        extras.putString("k", "v");
        MediaItem item = new MediaItem.Builder()
                .setMediaId(ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(TITLE)
                        .setArtworkUri(ARTWORK)
                        .setExtras(extras)
                        .build())
                .build();

        MediaItemMetadata converted = Media3Adapters.fromMedia3(item);

        assertThat(converted.getId()).isEqualTo(ID);
        // Read through the accessors the RENDERER uses — getArtworkKey() is what ImageBinder
        // resolves and getExtras() is what the content-style hints are read from — rather than
        // through the description the converter happened to build. Asserting on the builder's own
        // output would only restate the converter to itself.
        assertThat(converted.getArtworkKey().getImageURI()).isEqualTo(ARTWORK);
        assertThat(converted.getExtras()).isNotNull();
        assertThat(converted.getExtras().getString("k")).isEqualTo("v");
    }

    @Test
    public void aMetadataWithNothingSetDoesNotThrow() {
        // The empty case is where a converter that assumes fields are present falls over, and a
        // browse service returning a bare item is entirely legal.
        MediaItemMetadata converted =
                Media3Adapters.fromMedia3(new MediaItem.Builder().build());

        assertThat(converted).isNotNull();
        assertThat(converted.getTitle()).isNull();
        assertThat(converted.getAlbumTitle()).isNull();
        assertThat(converted.getArtist()).isNull();
    }

    // -------------------------------------------------------------------------------------------

    /** Everything a caller can observe, compared field by field rather than by equals() alone. */
    private static void assertSameSurface(MediaItemMetadata media3, MediaItemMetadata legacy) {
        assertThat(String.valueOf(media3.getId())).isEqualTo(String.valueOf(legacy.getId()));
        assertThat(String.valueOf(media3.getTitle()))
                .isEqualTo(String.valueOf(legacy.getTitle()));
        assertThat(String.valueOf(media3.getSubtitle()))
                .isEqualTo(String.valueOf(legacy.getSubtitle()));
        assertThat(String.valueOf(media3.getAlbumTitle()))
                .isEqualTo(String.valueOf(legacy.getAlbumTitle()));
        assertThat(String.valueOf(media3.getArtist()))
                .isEqualTo(String.valueOf(legacy.getArtist()));
        assertThat(media3.isBrowsable()).isEqualTo(legacy.isBrowsable());
        assertThat(media3.isPlayable()).isEqualTo(legacy.isPlayable());
        assertThat(media3.getQueueId()).isEqualTo(legacy.getQueueId());
    }

    private static MediaItem media3Item(boolean browsable, boolean playable) {
        return new MediaItem.Builder()
                .setMediaId(ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(TITLE)
                        .setSubtitle(SUBTITLE)
                        .setIsBrowsable(browsable)
                        .setIsPlayable(playable)
                        .build())
                .build();
    }

    private static MediaBrowserCompat.MediaItem legacyItem(int flags) {
        return new MediaBrowserCompat.MediaItem(
                new MediaDescriptionCompat.Builder()
                        .setMediaId(ID)
                        .setTitle(TITLE)
                        .setSubtitle(SUBTITLE)
                        .build(),
                flags);
    }


    // ---------------------------------------------------------------------------------------
    // fromMedia3QueueItem — the third conversion, which had no test at all
    // ---------------------------------------------------------------------------------------

    /**
     * The index becomes the queue id.
     *
     * <p>This is the conversion carrying the migration's one deliberate SEMANTIC change: Media3 has
     * no queue-item id, so the timeline index takes over the role the app-assigned {@code long}
     * held. The type is unchanged and the meaning is not, which is exactly the kind of change a
     * type-checked test cannot notice on its own — so the value is asserted, not just its presence.
     */
    @Test
    public void aQueueItemCarriesItsTimelineIndexAsTheQueueId() {
        MediaItemMetadata third = Media3Adapters.fromMedia3QueueItem(
                item("track-3", "Third", /* browsable= */ false, /* playable= */ true), 3L);

        assertThat(third.getQueueId()).isEqualTo(3L);
        assertThat(third.getId()).isEqualTo("track-3");
    }

    /**
     * Index 0 is a real queue id, not an absent one.
     *
     * <p>{@code getQueueId()} returns a boxed {@code Long}, so "no queue id" is null and position
     * zero is 0L. The first entry in every queue hits this, and a conversion that treated 0 as unset
     * would break the head of every list while leaving the rest working.
     */
    @Test
    public void indexZeroIsAQueueIdRatherThanAnAbsentOne() {
        MediaItemMetadata first = Media3Adapters.fromMedia3QueueItem(
                item("track-0", "First", false, true), 0L);

        assertThat(first.getQueueId()).isNotNull();
        assertThat(first.getQueueId()).isEqualTo(0L);
    }

    /**
     * Playable and browsable are FIXED, not read from the item.
     *
     * <p>The javadoc says a queue entry is by construction something the session can play and not
     * something you can descend into. The item here claims the opposite — browsable true, playable
     * false — so a conversion that read the metadata instead of overriding it fails this, while a
     * test using a normal queue item would pass either way.
     */
    @Test
    public void aQueueItemIsPlayableAndNotBrowsableWhateverTheItemClaims() {
        MediaItemMetadata entry = Media3Adapters.fromMedia3QueueItem(
                item("odd", "Claims to be a folder", /* browsable= */ true, /* playable= */ false),
                7L);

        assertThat(entry.isPlayable()).isTrue();
        assertThat(entry.isBrowsable()).isFalse();
    }

    /**
     * The queue conversion shares the same field mapping as the other two.
     *
     * <p>All three route through one builder helper. Album and artist are the two fields that helper
     * carries and that the LEGACY queue path structurally could not, so asserting them here is what
     * shows the sharing is real rather than three similar-looking copies.
     */
    @Test
    public void aQueueItemCarriesAlbumAndArtistThroughTheSharedMapping() {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle("Track")
                .setAlbumTitle("An album")
                .setArtist("An artist")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build();
        MediaItem media3 = new MediaItem.Builder().setMediaId("q").setMediaMetadata(metadata).build();

        MediaItemMetadata entry = Media3Adapters.fromMedia3QueueItem(media3, 2L);

        assertThat(entry.getAlbumTitle()).isEqualTo("An album");
        assertThat(entry.getArtist().toString()).isEqualTo("An artist");
        assertThat(entry.getQueueId()).isEqualTo(2L);
    }

    /** displayTitle wins here too — the precedence is in the shared helper, not per conversion. */
    @Test
    public void aQueueItemPrefersDisplayTitleOverTitle() {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle("raw")
                .setDisplayTitle("shown")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build();
        MediaItem media3 = new MediaItem.Builder().setMediaId("q").setMediaMetadata(metadata).build();

        assertThat(Media3Adapters.fromMedia3QueueItem(media3, 1L).getTitle().toString())
                .isEqualTo("shown");
    }

    private static MediaItem item(String id, String title, boolean browsable, boolean playable) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(browsable)
                        .setIsPlayable(playable)
                        .build())
                .build();
    }
}
