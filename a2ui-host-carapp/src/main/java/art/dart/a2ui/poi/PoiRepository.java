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
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The anchored POI corpus: a fixed list the flow searches and browses with no network, no
 * location fix, and no backend. A real place provider replaces this class, not the screens.
 */
public final class PoiRepository {

    private final List<PoiPlace> mPlaces;

    private PoiRepository(List<PoiPlace> places) {
        mPlaces = Collections.unmodifiableList(new ArrayList<>(places));
    }

    /** The Sprint 1 anchor set: six Manhattan places the emulator walk and tests share. */
    @NonNull
    public static PoiRepository anchored() {
        List<PoiPlace> places = new ArrayList<>();
        places.add(new PoiPlace("joes_pizza", "Joe's Pizza", "7 Carmine St", "Food",
                "Greenwich Village slice shop since 1975. Cash moves the line fastest.", 1800));
        places.add(new PoiPlace("the_met", "The Met Fifth Avenue", "1000 Fifth Ave", "Museum",
                "Two million square feet; the rooftop closes an hour before the museum.", 800));
        places.add(new PoiPlace("grand_central", "Grand Central Terminal", "89 E 42nd St",
                "Transit", "Head upstairs for the concourse clock, downstairs for the Oyster Bar.",
                500));
        places.add(new PoiPlace("oyster_bar", "Grand Central Oyster Bar",
                "89 E 42nd St, Lower Level", "Food",
                "Open since 1913. The vaulted ceiling carries a whisper corner to corner.", 500));
        places.add(new PoiPlace("nypl", "NY Public Library", "476 Fifth Ave", "Library",
                "The Rose Reading Room seats 624 and stays quiet enough to hear the lenses.", 300));
        places.add(new PoiPlace("boathouse", "Loeb Boathouse", "E 72nd St, Central Park", "Food",
                "Rowboats rent by the hour, April through November, weather permitting.", 2500));
        return new PoiRepository(places);
    }

    /** Every place, in anchor order. */
    @NonNull
    public List<PoiPlace> all() {
        return mPlaces;
    }

    /**
     * Places whose name, address, or category contains the query, case-insensitive. A null or
     * blank query returns everything: the search screen's initial list is the full anchor set.
     */
    @NonNull
    public List<PoiPlace> search(@Nullable String query) {
        if (query == null || query.trim().isEmpty()) {
            return mPlaces;
        }
        String needle = query.toLowerCase(Locale.US);
        List<PoiPlace> matches = new ArrayList<>();
        for (PoiPlace place : mPlaces) {
            if (place.getName().toLowerCase(Locale.US).contains(needle)
                    || place.getAddress().toLowerCase(Locale.US).contains(needle)
                    || place.getCategory().toLowerCase(Locale.US).contains(needle)) {
                matches.add(place);
            }
        }
        return matches;
    }

    /** The place with this id, or null. */
    @Nullable
    public PoiPlace get(@NonNull String id) {
        for (PoiPlace place : mPlaces) {
            if (place.getId().equals(id)) {
                return place;
            }
        }
        return null;
    }
}
