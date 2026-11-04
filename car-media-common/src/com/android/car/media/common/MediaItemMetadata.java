/*
 * Copyright 2018 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.apps.common.BitmapUtils;
import com.android.car.apps.common.CommonFlags;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageBinder.PlaceholderType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Abstract representation of a media item metadata.
 *
 * For media art, only local uris are supported so downloads can be attributed to the media app.
 * Bitmaps are not supported because they slow down the binder.
 */
public class MediaItemMetadata implements Parcelable {
    private static final String TAG = "MediaItemMetadata";

    static final int INVALID_MEDIA_ART_TINT_COLOR = Color.argb(200, 255, 0, 0);

    // Media3 migration, stage 4. This used to be a single MediaDescriptionCompat and every
    // accessor read it, which put a deprecated type in the artwork loader's path and in the parcel.
    // The fields it actually carried are now held directly, so the deprecated type survives only in
    // the signatures of the three legacy convenience constructors below -- where it is unavoidable,
    // because those constructors exist to accept it.
    @Nullable
    private final String mId;
    @Nullable
    private final CharSequence mTitle;
    @Nullable
    private final CharSequence mSubtitle;
    @Nullable
    private final Uri mArtworkUri;
    @Nullable
    private final Bundle mExtras;
    // Kept only for CommonFlags#shouldFlagImproperImageRefs, which tints bitmaps a browse service
    // should not have sent over the binder in the first place. Nothing else reads it.
    @Nullable
    private final Bitmap mIconBitmap;
    @Nullable
    private final Long mQueueId;
    private final boolean mIsBrowsable;
    private final boolean mIsPlayable;
    private final String mAlbumTitle;
    private final String mArtist;
    private final ArtworkRef mArtworkKey = new ArtworkRef();


    /** Creates an instance based on a {@link MediaMetadataCompat} */
    public MediaItemMetadata(@NonNull MediaMetadataCompat metadata) {
        this(new Builder().from(metadata.getDescription())
                .setAlbumTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM))
                .setArtist(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)));
    }

    /** Creates an instance based on a {@link MediaSessionCompat.QueueItem} */
    public MediaItemMetadata(@NonNull MediaSessionCompat.QueueItem queueItem) {
        this(new Builder().from(queueItem.getDescription())
                .setQueueId(queueItem.getQueueId()).setPlayable(true));
    }

    /** Creates an instance based on a {@link MediaBrowserCompat.MediaItem} */
    public MediaItemMetadata(@NonNull MediaBrowserCompat.MediaItem item) {
        this(new Builder().from(item.getDescription())
                .setBrowsable(item.isBrowsable()).setPlayable(item.isPlayable()));
    }

    /**
     * Creates an instance based on a {@link Parcel}.
     *
     * <p><b>The wire format changed in stage 4</b> and is no longer a marshalled
     * {@code MediaDescriptionCompat} followed by the rest. It is now the fields themselves. This
     * class has always used an ad-hoc hand-written format with no version tag, so it could never
     * safely cross two different builds of this library; that has not become true, it has become
     * visible. Within one app -- which is every use of it in this repository -- both ends are the
     * same build and the change is invisible.
     *
     * <p>Note that {@code javap} cannot see this: the API-surface gate pins signatures, and a parcel
     * format is neither a signature nor something a compiler checks. {@code MediaItemMetadataTest}
     * round-trips one through a real {@link Parcel} for that reason.
     */
    public MediaItemMetadata(@NonNull Parcel in) {
        mId = in.readString();
        mTitle = readCharSequence(in);
        mSubtitle = readCharSequence(in);
        mArtworkUri = in.readByte() == 0x00 ? null : Uri.parse(in.readString());
        mExtras = in.readBundle(getClass().getClassLoader());
        mIconBitmap = null;  // never parcelled: a bitmap over a binder is what the flag exists to catch
        mQueueId = in.readByte() == 0x00 ? null : in.readLong();
        mIsBrowsable = in.readByte() != 0x00;
        mIsPlayable = in.readByte() != 0x00;
        mAlbumTitle = in.readString();
        mArtist = in.readString();
    }

    private MediaItemMetadata(Builder b) {
        mId = b.mId;
        mTitle = b.mTitle;
        mSubtitle = b.mSubtitle;
        mArtworkUri = b.mArtworkUri;
        mExtras = b.mExtras;
        mIconBitmap = b.mIconBitmap;
        mQueueId = b.mQueueId;
        mIsBrowsable = b.mIsBrowsable;
        mIsPlayable = b.mIsPlayable;
        mAlbumTitle = b.mAlbumTitle;
        mArtist = b.mArtist;
    }

    /**
     * Assembles an instance field by field.
     *
     * <p>Replaces the {@code (MediaDescriptionCompat, Long, boolean, boolean, String, String)}
     * constructor. That signature is gone rather than deprecated: keeping it would have kept the
     * deprecated type reachable from every caller, which is the thing stage 4 exists to end, and
     * {@code Media3Adapters} was CONSTRUCTING a {@code MediaDescriptionCompat} out of Media3
     * metadata purely to satisfy it -- building a deprecated object on the modern path.
     *
     * <p>A builder rather than an eleven-argument constructor because eleven positional arguments,
     * of which four are booleans and strings in a row, is a call site where two can be swapped and
     * nothing complains.
     */
    public static final class Builder {
        @Nullable private String mId;
        @Nullable private CharSequence mTitle;
        @Nullable private CharSequence mSubtitle;
        @Nullable private Uri mArtworkUri;
        @Nullable private Bundle mExtras;
        @Nullable private Bitmap mIconBitmap;
        @Nullable private Long mQueueId;
        private boolean mIsBrowsable;
        private boolean mIsPlayable;
        @Nullable private String mAlbumTitle;
        @Nullable private String mArtist;

        public Builder setId(@Nullable String id) { mId = id; return this; }

        public Builder setTitle(@Nullable CharSequence title) { mTitle = title; return this; }

        public Builder setSubtitle(@Nullable CharSequence s) { mSubtitle = s; return this; }

        public Builder setArtworkUri(@Nullable Uri uri) { mArtworkUri = uri; return this; }

        public Builder setExtras(@Nullable Bundle extras) { mExtras = extras; return this; }

        public Builder setIconBitmap(@Nullable Bitmap bitmap) { mIconBitmap = bitmap; return this; }

        public Builder setQueueId(@Nullable Long queueId) { mQueueId = queueId; return this; }

        public Builder setBrowsable(boolean browsable) { mIsBrowsable = browsable; return this; }

        public Builder setPlayable(boolean playable) { mIsPlayable = playable; return this; }

        public Builder setAlbumTitle(@Nullable String t) { mAlbumTitle = t; return this; }

        public Builder setArtist(@Nullable String a) { mArtist = a; return this; }

        /** Reads a legacy description into the fields, for the convenience constructors. */
        Builder from(@Nullable MediaDescriptionCompat d) {
            if (d == null) return this;
            return setId(d.getMediaId()).setTitle(d.getTitle()).setSubtitle(d.getSubtitle())
                    .setArtworkUri(d.getIconUri()).setExtras(d.getExtras())
                    .setIconBitmap(d.getIconBitmap());
        }

        public MediaItemMetadata build() {
            return new MediaItemMetadata(this);
        }
    }

    @Nullable
    private static CharSequence readCharSequence(@NonNull Parcel in) {
        return in.readByte() == 0x00 ? null : in.readString();
    }

    /**
     * The key to access the image to display for this media item.
     * Implemented as a class so that later we can support showing different images for the same
     * item (eg: cover and author) by adding other keys.
     */
    public class ArtworkRef implements ImageBinder.ImageRef {

        private @Nullable Bitmap getBitmapToFlag(Context context) {
            CommonFlags flags = CommonFlags.getInstance(context);
            return flags.shouldFlagImproperImageRefs() ? mIconBitmap : null;
        }


        private int getPlaceholderHash() {
            // Only the title is reliably populated in metadata, since the album/artist fields
            // aren't set in the items retrieved from the browse service (only Title/Subtitle).
            return (getTitle() != null) ? getTitle().hashCode() : 0;
        }

        @Override
        public String toString() {
            return "title: " + getTitle() + " uri: " + getNonEmptyArtworkUri();
        }

        @Override
        public @Nullable Uri getImageURI() {
            return getNonEmptyArtworkUri();
        }

        @Override
        public boolean equals(Context context, Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArtworkRef other = (ArtworkRef) o;

            Bitmap myBitmap = getBitmapToFlag(context);
            Bitmap otherBitmap = other.getBitmapToFlag(context);
            if ((myBitmap != null) || (otherBitmap != null)) {
                return Objects.equals(myBitmap, otherBitmap);
            }

            Uri myUri = getImageURI();
            Uri otherUri = other.getImageURI();
            if ((myUri != null) || (otherUri != null)) {
                return Objects.equals(myUri, otherUri);
            }

            return getPlaceholderHash() == other.getPlaceholderHash();
        }

        @Override
        public @Nullable Drawable getImage(Context context) {
            Bitmap bitmap = getBitmapToFlag(context);
            if (bitmap != null) {
                Resources res = context.getResources();
                return new BitmapDrawable(res, BitmapUtils.createTintedBitmap(bitmap,
                        context.getColor(
                                com.android.car.apps.common.R.color.improper_image_refs_tint_color
                        )));
            }
            return null;
        }

        @Override
        public Drawable getPlaceholder(Context context, @NonNull PlaceholderType type) {
            if (type == PlaceholderType.NONE) return null;

            List<Drawable> placeholders = getPlaceHolders(type, context);
            int random = Math.floorMod(getPlaceholderHash(), placeholders.size());
            return placeholders.get(random);
        }
    }

    /** @return media item id */
    @Nullable
    public String getId() {
        return mId;
    }

    /** @return media item title */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /** @return media item subtitle */
    @Nullable
    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    /** @return the album title for the media */
    @Nullable
    public String getAlbumTitle() {
        return mAlbumTitle;
    }

    /** @return the artist of the media */
    @Nullable
    public CharSequence getArtist() {
        return mArtist;
    }

    /**
     * @return the id of this item in the session queue, or NULL if this is not a session queue
     * item.
     */
    @Nullable
    public Long getQueueId() {
        return mQueueId;
    }


    public ArtworkRef getArtworkKey() {
        return mArtworkKey;
    }

    /**
     * @return a {@link Uri} referencing the artwork's bitmap.
     */
    private @Nullable Uri getNonEmptyArtworkUri() {
        Uri uri = mArtworkUri;
        return (uri != null && !TextUtils.isEmpty(uri.toString())) ? uri : null;
    }

    /**
     * @return optional extras that can include extra information about the media item to be played.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * @return boolean that indicate if media is explicit.
     */
    public boolean isExplicit() {
        Bundle extras = mExtras;
        return extras != null && extras.getLong(MediaConstants.EXTRA_IS_EXPLICIT)
                == MediaConstants.EXTRA_METADATA_ENABLED_VALUE;
    }

    /**
     * @return boolean that indicate if media is downloaded.
     */
    public boolean isDownloaded() {
        Bundle extras = mExtras;
        return extras != null && extras.getLong(MediaConstants.EXTRA_DOWNLOAD_STATUS)
                == MediaDescriptionCompat.STATUS_DOWNLOADED;
    }

    private static Map<PlaceholderType, List<Drawable>> sPlaceHolders = new HashMap<>();

    private static List<Drawable> getPlaceHolders(PlaceholderType type, Context context) {
        List<Drawable> placeHolders = sPlaceHolders.get(type);
        if (placeHolders == null) {
            TypedArray placeholderImages = context.getResources().obtainTypedArray(
                    type == PlaceholderType.FOREGROUND
                            ? R.array.placeholder_images : R.array.placeholder_backgrounds);

            if (placeholderImages == null) {
                throw new NullPointerException("No placeholders for " + type);
            }

            placeHolders = new ArrayList<>(placeholderImages.length());
            for (int i = 0; i < placeholderImages.length(); i++) {
                placeHolders.add(placeholderImages.getDrawable(i));
            }
            placeholderImages.recycle();
            sPlaceHolders.put(type, placeHolders);

            if (sPlaceHolders.size() <= 0) {
                throw new Resources.NotFoundException("Placeholders should not be empty " + type);
            }
        }
        return placeHolders;
    }

    public boolean isBrowsable() {
        return mIsBrowsable;
    }

    /**
     * @return Content style hint for browsable items, if provided as an extra, or
     * 0 as default value if not provided.
     */
    public int getBrowsableContentStyleHint() {
        Bundle extras = mExtras;
        if (extras != null) {
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT, 0);
            } else if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 0);
            }
        }
        return 0;
    }

    public boolean isPlayable() {
        return mIsPlayable;
    }

    /**
     * @return Content style hint for playable items, if provided as an extra, or
     * 0 as default value if not provided.
     */
    public int getPlayableContentStyleHint() {
        Bundle extras = mExtras;
        if (extras != null) {

            if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT, 0);
            } else if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 0);
            }
        }
        return 0;
    }

    /**
     * @return Content style title group this item belongs to, or null if not provided
     */
    public String getTitleGrouping() {
        Bundle extras = mExtras;
        if (extras != null) {
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT)) {
                return extras.getString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT, null);
            } else if (extras.containsKey(
                    MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE)) {
                return extras.getString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE,
                        null);
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaItemMetadata that = (MediaItemMetadata) o;
        return mIsBrowsable == that.mIsBrowsable
                && mIsPlayable == that.mIsPlayable
                && Objects.equals(getId(), that.getId())
                && Objects.equals(getTitle(), that.getTitle())
                && Objects.equals(getSubtitle(), that.getSubtitle())
                && Objects.equals(getAlbumTitle(), that.getAlbumTitle())
                && Objects.equals(getArtist(), that.getArtist())
                && Objects.equals(getNonEmptyArtworkUri(), that.getNonEmptyArtworkUri())
                && Objects.equals(mQueueId, that.mQueueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mQueueId, mIsBrowsable, mIsPlayable);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Mirrors MediaItemMetadata(Parcel) field for field and in the same order. The two are a
        // matched pair with no version tag and no self-description, so a change to either that is
        // not made to the other corrupts every subsequent field rather than failing loudly.
        // MediaItemMetadataTest round-trips a fully populated instance for exactly that reason.
        dest.writeString(mId);
        writeCharSequence(dest, mTitle);
        writeCharSequence(dest, mSubtitle);
        if (mArtworkUri == null) {
            dest.writeByte((byte) 0x00);
        } else {
            dest.writeByte((byte) 0x01);
            dest.writeString(mArtworkUri.toString());
        }
        dest.writeBundle(mExtras);
        // mIconBitmap is deliberately NOT written: it exists to flag services that put a bitmap on
        // the binder, and parcelling it here would commit the same offence.
        if (mQueueId == null) {
            dest.writeByte((byte) (0x00));
        } else {
            dest.writeByte((byte) (0x01));
            dest.writeLong(mQueueId);
        }
        dest.writeByte((byte) (mIsBrowsable ? 0x01 : 0x00));
        dest.writeByte((byte) (mIsPlayable ? 0x01 : 0x00));
        dest.writeString(mAlbumTitle);
        dest.writeString(mArtist);
    }

    private static void writeCharSequence(@NonNull Parcel dest, @Nullable CharSequence value) {
        if (value == null) {
            dest.writeByte((byte) 0x00);
        } else {
            dest.writeByte((byte) 0x01);
            dest.writeString(value.toString());
        }
    }

    @SuppressWarnings("unused")
    public static final Parcelable.Creator<MediaItemMetadata> CREATOR =
            new Parcelable.Creator<MediaItemMetadata>() {
                @Override
                public MediaItemMetadata createFromParcel(Parcel in) {
                    return new MediaItemMetadata(in);
                }

                @Override
                public MediaItemMetadata[] newArray(int size) {
                    return new MediaItemMetadata[size];
                }
            };

    @Override
    public String toString() {
        // Rebuilt in stage 4, and it was BROKEN before: the old body read
        //     + mMediaDescription != null ? mMediaDescription.getTitle().toString() : "-"
        // which Java parses as ("..." + mMediaDescription) != null -- always true -- so the whole
        // expression collapsed to the title and silently dropped every other field. Three of the
        // six branches were dead the same way. Nothing caught it because nothing asserts on
        // toString; it is a debug aid, which is exactly why nobody reads it closely.
        return "[Id: " + orDash(mId)
                + ", Queue Id: " + orDash(mQueueId)
                + ", title: " + orDash(mTitle)
                + ", subtitle: " + orDash(mSubtitle)
                + ", album title: " + orDash(mAlbumTitle)
                + ", artist: " + orDash(mArtist)
                + ", album art URI: " + orDash(mArtworkUri)
                + "]";
    }

    private static String orDash(@Nullable Object value) {
        return value != null ? value.toString() : "-";
    }

}
