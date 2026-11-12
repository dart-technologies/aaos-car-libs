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
import androidx.media3.common.MediaItem;

import java.util.List;

/**
 * The browse tree behind the media session. The service owns the session and the player; this
 * provider owns every item the controllers see. That split is the EAP seam: when the
 * templated-media track lands, it adopts a provider — the service and the player wiring stay
 * untouched.
 */
public interface MediaCatalog {

    /** The browsable library root. */
    @NonNull
    MediaItem libraryRoot();

    /** The children of a browsable parent, paged; empty when the parent is unknown. */
    @NonNull
    List<MediaItem> children(@NonNull String parentId, int page, int pageSize);

    /** One item by id, or null when the id names nothing. */
    @Nullable
    MediaItem item(@NonNull String mediaId);

    /** Playable items whose title matches the query, paged; never null. */
    @NonNull
    List<MediaItem> search(@NonNull String query, int page, int pageSize);
}
