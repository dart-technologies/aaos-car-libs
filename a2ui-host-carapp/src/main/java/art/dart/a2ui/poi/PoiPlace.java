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
package art.dart.a2ui.poi;

import androidx.annotation.NonNull;

/** One anchored point of interest: fixed data, no backend, no location fix needed. */
public final class PoiPlace {

    private final String mId;
    private final String mName;
    private final String mAddress;
    private final String mCategory;
    private final String mDetails;

    public PoiPlace(@NonNull String id, @NonNull String name, @NonNull String address,
            @NonNull String category, @NonNull String details) {
        mId = id;
        mName = name;
        mAddress = address;
        mCategory = category;
        mDetails = details;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @NonNull
    public String getAddress() {
        return mAddress;
    }

    @NonNull
    public String getCategory() {
        return mCategory;
    }

    @NonNull
    public String getDetails() {
        return mDetails;
    }
}
