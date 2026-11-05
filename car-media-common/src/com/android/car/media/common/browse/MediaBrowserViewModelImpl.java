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
 *
 * Modified by Dart Technologies, 2026; see FORK.md.
 */

package com.android.car.media.common.browse;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * TODO: rename to MediaBrowserUtils.
 * Provides utility methods for {@link MediaBrowserCompat}.
 */
/*
 * Media3 migration, file 3. These three read ONE thing from the browser — its root extras — so
 * taking the whole MediaBrowserCompat made them part of the legacy surface for a Bundle. They now
 * take the Bundle, which a Media3 client can supply just as well (the root LibraryResult carries
 * its params/extras), so they do not move again at the flip.
 *
 * This is an API change for downstream consumers: three of the nine public signatures that leak
 * legacy types. There are no in-repo callers, so nothing here had to change with them.
 */
public class MediaBrowserViewModelImpl {

    private MediaBrowserViewModelImpl() {
    }

    /**
     * Filters the items that are valid for the root (tabs) or the current node. Returns null when
     * the given list is null to preserve its error signal.
     */
    @Nullable
    public static List<MediaItemMetadata> filterItems(boolean forRoot,
            @Nullable List<MediaItemMetadata> items) {
        if (items == null) return null;
        Predicate<MediaItemMetadata> predicate = forRoot ? MediaItemMetadata::isBrowsable
                : item -> (item.isPlayable() || item.isBrowsable());
        return items.stream().filter(predicate).collect(Collectors.toList());
    }

    /** Returns only the browse-able items from the given list. */
    @Nullable
    public static List<MediaItemMetadata> selectBrowseableItems(
            @Nullable List<MediaItemMetadata> items) {
        if (items == null) return null;
        Predicate<MediaItemMetadata> predicate = MediaItemMetadata::isBrowsable;
        return items.stream().filter(predicate).collect(Collectors.toList());
    }


    public static boolean getSupportsSearch(@Nullable Bundle extras) {
        if (extras == null) {
            return false;
        }
        if (extras.containsKey(MediaConstants.MEDIA_SEARCH_SUPPORTED)) {
            return extras.getBoolean(MediaConstants.MEDIA_SEARCH_SUPPORTED);
        }
        if (extras.containsKey(MediaConstants.MEDIA_SEARCH_SUPPORTED_PRERELEASE)) {
            return extras.getBoolean(MediaConstants.MEDIA_SEARCH_SUPPORTED_PRERELEASE);
        }
        return false;
    }

    public static int getRootBrowsableHint(@Nullable Bundle extras) {
        if (extras == null) {
            return 0;
        }
        if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT)) {
            return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT, 0);
        }
        if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE)) {
            return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 0);
        }
        return 0;
    }

    public static int getRootPlayableHint(@Nullable Bundle extras) {
        if (extras == null) {
            return 0;
        }
        if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT)) {
            return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT, 0);
        }
        if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE)) {
            return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 0);
        }
        return 0;
    }

    /** Returns the elements of oldList that do NOT appear in newList. */
    public static @NonNull Collection<MediaItemMetadata> computeRemovedItems(
            @Nullable List<MediaItemMetadata> oldList, @Nullable List<MediaItemMetadata> newList) {
        if (oldList == null || oldList.isEmpty()) {
            // Nothing was removed
            return Collections.emptyList();
        }

        if (newList == null || newList.isEmpty()) {
            // Everything was removed
            return new ArrayList<>(oldList);
        }

        HashSet<MediaItemMetadata> itemsById = new HashSet<>(oldList);
        itemsById.removeAll(newList);
        return itemsById;
    }
}
