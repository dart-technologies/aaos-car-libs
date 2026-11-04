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
package art.dart.fixtures;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaBrowser;

import com.android.car.media.common.Media3Adapters;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaSource;
import com.google.common.util.concurrent.ListenableFuture;

/** Compiles only when public Media3 and Guava types are exported to an ordinary consumer. */
public abstract class CarMediaConsumerCompileFixture extends MediaBrowserConnector {
    protected CarMediaConsumerCompileFixture(@NonNull Context context,
            @NonNull Callback callback) {
        super(context, callback);
    }

    @NonNull
    public static MediaItemMetadata adapt(@NonNull MediaItem item) {
        return Media3Adapters.fromMedia3(item);
    }

    @Override
    @NonNull
    protected abstract ListenableFuture<MediaBrowser> createMediaBrowser(
            @NonNull MediaSource source, @NonNull MediaBrowser.Listener listener);
}
