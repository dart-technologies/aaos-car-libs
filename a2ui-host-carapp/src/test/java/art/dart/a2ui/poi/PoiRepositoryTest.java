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

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** The anchored corpus answers, search filters, and the launch extra selects the POI root. */
@RunWith(RobolectricTestRunner.class)
public class PoiRepositoryTest {

    private final PoiRepository mRepository = PoiRepository.anchored();

    /** The anchor set is six distinct ids; an empty corpus must not pass. */
    @Test
    public void anchoredCorpusIsSixDistinctPlaces() {
        assertThat(mRepository.all()).hasSize(6);
        HashSet<String> ids = new HashSet<>();
        for (PoiPlace place : mRepository.all()) {
            ids.add(place.getId());
        }
        assertThat(ids).hasSize(6);
    }

    @Test
    public void searchMatchesNameAddressAndCategory() {
        assertThat(names(mRepository.search("pizza"))).containsExactly("Joe's Pizza");
        assertThat(names(mRepository.search("42nd"))).containsExactly(
                "Grand Central Terminal", "Grand Central Oyster Bar");
        assertThat(names(mRepository.search("museum"))).containsExactly("The Met Fifth Avenue");
        assertThat(mRepository.search("no_such_place")).isEmpty();
    }

    @Test
    public void blankQueryReturnsEverything() {
        assertThat(mRepository.search("")).hasSize(6);
        assertThat(mRepository.search("   ")).hasSize(6);
        assertThat(mRepository.search(null)).hasSize(6);
    }

    @Test
    public void getFindsByIdAndNothingElse() {
        assertThat(mRepository.get("nypl").getName()).isEqualTo("NY Public Library");
        assertThat(mRepository.get("joes_pizza").getDistanceMeters()).isEqualTo(1800);
        assertThat(mRepository.get("no_such_place")).isNull();
    }

    @Test
    public void poiLaunchNeedsTheExtra() {
        assertThat(PoiFlow.isPoiLaunch(null)).isFalse();
        assertThat(PoiFlow.isPoiLaunch(new Intent())).isFalse();
        Intent poi = new Intent().putExtra(PoiFlow.EXTRA_POI_LAUNCH, true);
        assertThat(PoiFlow.isPoiLaunch(poi)).isTrue();
    }

    private static List<String> names(List<PoiPlace> places) {
        List<String> names = new ArrayList<>();
        for (PoiPlace place : places) {
            names.add(place.getName());
        }
        return names;
    }
}
