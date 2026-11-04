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

import android.net.Uri;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

/**
 * Media3 value types in, this module's {@link MediaItemMetadata} out.
 *
 * <h2>Why this exists, and why it is only the first stage</h2>
 *
 * <p>{@code androidx.media} is deprecated in favour of {@code androidx.media3}, so every legacy type
 * this module is built on — {@code MediaBrowserCompat}, {@code MediaControllerCompat},
 * {@code MediaMetadataCompat}, {@code PlaybackStateCompat} — is on a retired ladder. Migrating is
 * therefore not optional forever. It is also not a rename: Media3's session model is structurally
 * different, and this module has <b>nine public signatures</b> that take or return legacy types, so
 * a full swap is a breaking API change for every downstream consumer.
 *
 * <p>The dangerous way to do it is all at once, because <b>the tests would migrate with the code
 * they protect</b> — the safety net rewritten alongside the thing it catches. So the first stage is
 * this: a converter, additive, breaking nothing, whose correctness is stated as an EQUIVALENCE
 * against the legacy path that is already in production and already tested.
 * {@code Media3AdaptersTest} asserts that a Media3 item and its legacy twin produce
 * indistinguishable {@link MediaItemMetadata}. Until that holds, no later stage can be trusted;
 * once it holds, later stages have something to be checked against.
 *
 * <h2>Where the equivalence stops</h2>
 *
 * <p>It is not total, and the exception is a migration hazard rather than a detail. A Media3 browse
 * item can carry <b>album and artist</b>; a legacy {@code MediaBrowserCompat.MediaItem} cannot — it
 * holds only a {@code MediaDescriptionCompat}, so {@link MediaItemMetadata}'s legacy browse
 * constructor passes {@code null, null} unconditionally. Media3 carries strictly more.
 *
 * <p>So migrating the browse path does not merely preserve behaviour: it starts populating two
 * fields that were always null. A caller branching on {@code getAlbumTitle() == null} to mean "this
 * is a browse row" changes behaviour silently. Pinned by
 * {@code aMedia3BrowseItemCarriesAlbumAndArtist_whichTheLegacyPathStructurallyCannot}, which exists
 * because a mutation dropping album and artist came back MISSED — the browse mapping had no
 * assertion at all, and its divergence from legacy was unstated.
 *
 * <p><b>Deliberately not done here:</b> changing {@link MediaItemMetadata}'s internals. It stores a
 * {@link MediaDescriptionCompat} and every accessor reads it, so replacing that field reaches the
 * artwork loader and the parcelling. This class converts INTO the existing representation on
 * purpose — a bridge is reversible, an internal rewrite is not.
 */
public final class Media3Adapters {

    private Media3Adapters() {
    }

    /**
     * Converts a Media3 {@link MediaItem} into this module's metadata.
     *
     * <p>The browsable/playable flags are {@code Boolean} on Media3's {@link MediaMetadata} and
     * primitive {@code boolean} on the legacy {@code MediaBrowserCompat.MediaItem} flags. Unset
     * therefore has to mean something, and it means <b>false</b> — the same answer the legacy path
     * gives for an item whose flag bit is absent. Mapping unset to true would invent a capability
     * the browse service never advertised.
     */
    @NonNull
    public static MediaItemMetadata fromMedia3(@NonNull MediaItem item) {
        MediaMetadata metadata = item.mediaMetadata;
        return builderOf(item.mediaId, metadata)
                .setBrowsable(Boolean.TRUE.equals(metadata.isBrowsable))
                .setPlayable(Boolean.TRUE.equals(metadata.isPlayable))
                .build();
    }

    /**
     * Converts a Media3 {@link MediaMetadata} with an explicit id.
     *
     * <p>Separate entry point because {@code MediaMetadata} carries no id — Media3 puts it on the
     * {@link MediaItem} — and the metadata alone is what a player callback hands you. Neither
     * browsable nor playable is meaningful for a now-playing item, which is why both are false
     * here rather than read from the metadata: the legacy {@code MediaMetadataCompat} constructor
     * makes the same choice, and this method exists to match it.
     */
    @NonNull
    public static MediaItemMetadata fromMedia3(
            @Nullable String mediaId, @NonNull MediaMetadata metadata) {
        return builderOf(mediaId, metadata)
                .setBrowsable(false)
                .setPlayable(false)
                .build();
    }

    /**
     * Converts a Media3 queue entry, carrying the position that identifies it.
     *
     * <p>Exists because Media3 has no queue-item id. The legacy model had one — an app-assigned
     * {@code long} on {@code MediaSessionCompat.QueueItem} — and this module uses it as a matched
     * pair: {@code PlaybackViewModel.getQueue()} publishes it and
     * {@code PlaybackController.skipToQueueItem} sends it straight back. Media3 identifies a queue
     * entry by its index in the timeline, so the index takes over that role and the pair stays
     * consistent. The TYPE is unchanged; the VALUE is now an index rather than an app-chosen id,
     * which matters to anyone who compared it against something obtained elsewhere.
     *
     * <p>Playable is true and browsable false: a queue entry is by construction something the
     * session can play and not something you can descend into.
     */
    @NonNull
    public static MediaItemMetadata fromMedia3QueueItem(@NonNull MediaItem item, long queueIndex) {
        MediaMetadata metadata = item.mediaMetadata;
        return builderOf(item.mediaId, metadata)
                .setQueueId(queueIndex)
                .setBrowsable(false)
                .setPlayable(true)
                .build();
    }

    /**
     * The shape {@link MediaItemMetadata} actually reads from.
     *
     * <p>Media3's {@code displayTitle} takes precedence over {@code title} for the same reason the
     * legacy browse path prefers it: it is the string an app chose for display, where {@code title}
     * may be a canonical track name. Falling back rather than choosing one means an app that sets
     * only one of them still renders.
     */
    @NonNull
    /**
     * The fields every conversion shares, straight onto {@link MediaItemMetadata.Builder}.
     *
     * <p>Stage 4. This used to build a {@code MediaDescriptionCompat} and hand it over -- the
     * modern path constructing a deprecated object purely to satisfy a constructor that has since
     * been replaced. Nothing about the mapping changed: displayTitle still wins over title, and an
     * absent artwork uri or extras is still left unset rather than written as null.
     */
    private static MediaItemMetadata.Builder builderOf(
            @Nullable String mediaId, @NonNull MediaMetadata metadata) {
        CharSequence title = metadata.displayTitle != null ? metadata.displayTitle : metadata.title;
        MediaItemMetadata.Builder builder = new MediaItemMetadata.Builder()
                .setId(mediaId)
                .setTitle(title)
                .setSubtitle(metadata.subtitle)
                .setAlbumTitle(toStringOrNull(metadata.albumTitle))
                .setArtist(toStringOrNull(metadata.artist));
        if (metadata.artworkUri != null) {
            builder.setArtworkUri(metadata.artworkUri);
        }
        if (metadata.extras != null) {
            builder.setExtras(metadata.extras);
        }
        return builder;
    }

    @Nullable
    private static String toStringOrNull(@Nullable CharSequence value) {
        return value == null ? null : value.toString();
    }
}
