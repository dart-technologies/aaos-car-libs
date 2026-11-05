/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.car.media.common;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import android.os.Bundle;
import com.android.car.media.common.MediaConstants;
import android.support.v4.media.MediaDescriptionCompat;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MediaItemMetadataTest {
    /**
     * A FULLY POPULATED round trip, which is what makes this a test of the parcel format.
     *
     * <p>The previous version passed null for the description and set only album and artist, so
     * every other field was null on both sides and {@code equals} compared nulls to nulls. It would
     * have passed against a write order that did not match the read order at all. That mattered
     * little while the format was one {@code writeValue(MediaDescriptionCompat)} -- the platform did
     * the work -- and matters a great deal now that stage 4 writes the fields by hand: the two
     * halves are a matched pair with no version tag and no self-description, so a field written and
     * not read, or read in the wrong order, silently corrupts everything after it.
     */
    @Test
    public void aFullyPopulatedInstanceSurvivesTheParcel() {
        Bundle extras = new Bundle();
        extras.putString("extra-key", "extra-value");
        MediaItemMetadata metadata = new MediaItemMetadata.Builder()
                .setId("media-id")
                .setTitle("A title")
                .setSubtitle("A subtitle")
                .setArtworkUri(Uri.parse("content://art/1"))
                .setExtras(extras)
                .setQueueId(7L)
                .setBrowsable(true)
                .setPlayable(true)
                .setAlbumTitle("An album")
                .setArtist("An artist")
                .build();

        MediaItemMetadata read = roundTrip(metadata);

        // equals() first, then the fields it does NOT cover. Asserting only equals would leave
        // extras untested, and extras is where the content-style hints and the explicit/downloaded
        // flags live -- the parcel could drop it entirely and equals would still hold.
        assertThat(read).isEqualTo(metadata);
        assertThat(read.getId()).isEqualTo("media-id");
        assertThat(read.getTitle().toString()).isEqualTo("A title");
        assertThat(read.getSubtitle().toString()).isEqualTo("A subtitle");
        assertThat(read.getArtworkKey().getImageURI()).isEqualTo(Uri.parse("content://art/1"));
        assertThat(read.getQueueId()).isEqualTo(7L);
        assertThat(read.isBrowsable()).isTrue();
        assertThat(read.isPlayable()).isTrue();
        assertThat(read.getAlbumTitle()).isEqualTo("An album");
        assertThat(read.getArtist().toString()).isEqualTo("An artist");
        assertThat(read.getExtras()).isNotNull();
        assertThat(read.getExtras().getString("extra-key")).isEqualTo("extra-value");
    }

    /** The original case: empty and null strings must not become each other, or crash. */
    @Test
    public void emptyAndNullStringsSurviveTheParcel() {
        String[] albums = {"album", "", null};
        String[] artists = {"artist", "", null};
        for (int i = 0; i < 3; i++) {
            MediaItemMetadata metadata = new MediaItemMetadata.Builder()
                    .setQueueId(0L)
                    .setAlbumTitle(albums[i])
                    .setArtist(artists[i])
                    .build();

            MediaItemMetadata read = roundTrip(metadata);

            assertThat(read).isEqualTo(metadata);
            assertThat(read.getAlbumTitle()).isEqualTo(albums[i]);
            assertThat(read.getArtist()).isEqualTo(artists[i]);
        }
    }

    /**
     * Through a REAL {@link Parcel}, because the Intent route does not marshal.
     *
     * <p>This is the second version of this helper. The first went through
     * {@code new Intent().putExtra(...)} then {@code getExtras().getParcelable(...)}, on the
     * reasoning that it is how the class is actually parcelled in production -- and under
     * Robolectric it hands back THE SAME INSTANCE without ever calling {@code writeToParcel}. Every
     * assertion then compared an object to itself and held no matter what the parcel code did.
     *
     * <p>It was not review that caught this. A mutation swapping two writes in {@code
     * writeToParcel} came back MISSED against the "fully populated round trip" -- a test written
     * specifically to catch that class of defect, which could not have caught anything at all.
     * obtain/marshal/unmarshal is the only form of this test that touches the code it names.
     */
    private static MediaItemMetadata roundTrip(MediaItemMetadata metadata) {
        Parcel parcel = Parcel.obtain();
        try {
            metadata.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            MediaItemMetadata read = MediaItemMetadata.CREATOR.createFromParcel(parcel);
            // Anti-vacuity: if this ever becomes the same object again, every assertion below is
            // comparing it to itself and the test has stopped testing anything.
            assertThat(read).isNotSameInstanceAs(metadata);
            return read;
        } finally {
            parcel.recycle();
        }
    }

    /**
     * The extras-derived accessors, which nothing covered.
     *
     * <p>Stage 4 moved every one of these off a MediaDescriptionCompat and onto a Bundle field, and
     * the only test was a parcel round trip. isExplicit, isDownloaded and the three content-style
     * hints all read the SAME bundle with different keys and different defaults -- exactly the shape
     * where a copy-paste error reads the wrong key and nothing notices, because each is a boolean or
     * a small int that looks plausible either way.
     */
    @Test
    public void explicitAndDownloadedAreReadFromTheirOwnExtras() {
        Bundle extras = new Bundle();
        extras.putLong(MediaConstants.EXTRA_IS_EXPLICIT,
                MediaConstants.EXTRA_METADATA_ENABLED_VALUE);
        MediaItemMetadata explicit = new MediaItemMetadata.Builder().setExtras(extras).build();

        assertThat(explicit.isExplicit()).isTrue();
        // Not downloaded: the SAME bundle, a different key, and it must not be inferred.
        assertThat(explicit.isDownloaded()).isFalse();
    }

    @Test
    public void downloadedIsReadFromItsOwnExtra() {
        Bundle extras = new Bundle();
        extras.putLong(MediaConstants.EXTRA_DOWNLOAD_STATUS,
                MediaDescriptionCompat.STATUS_DOWNLOADED);
        MediaItemMetadata downloaded = new MediaItemMetadata.Builder().setExtras(extras).build();

        assertThat(downloaded.isDownloaded()).isTrue();
        assertThat(downloaded.isExplicit()).isFalse();
    }

    @Test
    public void absentExtrasMeanNeitherExplicitNorDownloaded() {
        MediaItemMetadata bare = new MediaItemMetadata.Builder().build();

        assertThat(bare.isExplicit()).isFalse();
        assertThat(bare.isDownloaded()).isFalse();
        assertThat(bare.getExtras()).isNull();
    }

    /**
     * Content-style hints, including the PRERELEASE fallback.
     *
     * <p>Each hint has two accepted keys -- the released one and a prerelease one a browse service
     * may still be sending -- and the released key wins. A test with only one key set would pass
     * against an implementation that read either.
     */
    @Test
    public void contentStyleHintsPreferTheReleasedKeyOverThePrereleaseOne() {
        Bundle extras = new Bundle();
        extras.putInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT, 2);
        extras.putInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 9);
        extras.putInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT, 3);
        extras.putInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 9);
        MediaItemMetadata both = new MediaItemMetadata.Builder().setExtras(extras).build();

        assertThat(both.getBrowsableContentStyleHint()).isEqualTo(2);
        assertThat(both.getPlayableContentStyleHint()).isEqualTo(3);
    }

    @Test
    public void contentStyleHintsFallBackToThePrereleaseKey() {
        Bundle extras = new Bundle();
        extras.putInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 7);
        extras.putInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 8);
        MediaItemMetadata pre = new MediaItemMetadata.Builder().setExtras(extras).build();

        assertThat(pre.getBrowsableContentStyleHint()).isEqualTo(7);
        assertThat(pre.getPlayableContentStyleHint()).isEqualTo(8);
    }

    @Test
    public void absentHintsAreZeroRatherThanAGuess() {
        MediaItemMetadata bare = new MediaItemMetadata.Builder().build();

        assertThat(bare.getBrowsableContentStyleHint()).isEqualTo(0);
        assertThat(bare.getPlayableContentStyleHint()).isEqualTo(0);
        assertThat(bare.getTitleGrouping()).isNull();
    }

    @Test
    public void titleGroupingPrefersTheReleasedKeyAndFallsBack() {
        Bundle released = new Bundle();
        released.putString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT, "Albums");
        released.putString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE, "Old");
        assertThat(new MediaItemMetadata.Builder().setExtras(released).build().getTitleGrouping())
                .isEqualTo("Albums");

        Bundle pre = new Bundle();
        pre.putString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE, "Old");
        assertThat(new MediaItemMetadata.Builder().setExtras(pre).build().getTitleGrouping())
                .isEqualTo("Old");
    }

    /** equals() spans nine fields; two that differ only in queue id must not be equal. */
    @Test
    public void equalityDistinguishesItemsThatDifferOnlyByQueueId() {
        MediaItemMetadata.Builder base = new MediaItemMetadata.Builder()
                .setId("id").setTitle("t").setSubtitle("s").setAlbumTitle("a").setArtist("r");
        MediaItemMetadata first = base.setQueueId(1L).build();
        MediaItemMetadata second = new MediaItemMetadata.Builder()
                .setId("id").setTitle("t").setSubtitle("s").setAlbumTitle("a").setArtist("r")
                .setQueueId(2L).build();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(base.setQueueId(1L).build());
    }
}
