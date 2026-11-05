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
package art.dart.a2ui.carui;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A host-owned confirm-then-arrive story over exact corpus surfaces: after a confirmed DEMO
 * commitment a glanceable confirmation card (pickup, ticket), and after park → drive → park an
 * arrival card. The words come from the trace's data model; the host owns which pointers.
 */
final class HostJourney {

    @NonNull final String mId;
    /** The surface whose data model holds the card words. */
    @NonNull final String mDataSurface;
    @NonNull final Set<String> mSurfaces;
    @NonNull final String mConfirmTitle;
    @NonNull final List<String> mConfirmBody;
    @NonNull final String mArrivalTitle;
    @NonNull final String mArrivalDetail;
    @DrawableRes final int mConfirmGlyph;
    @DrawableRes final int mArrivalArt;
    /** Large arrival image for the visual panel, or 0 for the list card alone. */
    @DrawableRes final int mHeroArt;

    private HostJourney(String id, String dataSurface, Set<String> surfaces, String confirmTitle,
            List<String> confirmBody, String arrivalTitle, String arrivalDetail,
            int confirmGlyph, int arrivalArt, int heroArt) {
        mId = id;
        mDataSurface = dataSurface;
        mSurfaces = Collections.unmodifiableSet(surfaces);
        mConfirmTitle = confirmTitle;
        mConfirmBody = Collections.unmodifiableList(confirmBody);
        mArrivalTitle = arrivalTitle;
        mArrivalDetail = arrivalDetail;
        mConfirmGlyph = confirmGlyph;
        mArrivalArt = arrivalArt;
        mHeroArt = heroArt;
    }

    private static final HostJourney FIFTH_HOUSE = new HostJourney("fifth_house",
            "poi_discovery_order",
            new LinkedHashSet<>(Arrays.asList("poi_discovery_order", "big_buck_bunny")),
            "/order/status", Arrays.asList("/order/eta", "/order/pickup"),
            "/arrival/title", "/arrival/detail",
            R.drawable.a2ui_ic_pickup, R.drawable.demo_media_poster, 0);

    private static final List<HostJourney> ALL = Collections.singletonList(FIFTH_HOUSE);

    /** The journey an exact corpus surface belongs to, or null. */
    @Nullable
    static HostJourney forSurface(@NonNull String surfaceId) {
        for (HostJourney journey : ALL) {
            if (journey.mSurfaces.contains(surfaceId)) return journey;
        }
        return null;
    }
}
