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
package art.dart.a2ui.carapp;

import static com.google.common.truth.Truth.assertThat;

import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;

import art.dart.a2ui.poi.PoiFlow;
import art.dart.a2ui.poi.PoiPlace;
import art.dart.a2ui.poi.PoiRepository;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * The anchored POI flow on the JVM: each step renders its template, and the fourth screen is the
 * last — the 4-step cap the emulator walk asserts by hand.
 */
@RunWith(RobolectricTestRunner.class)
public class PoiScreensTest {

    private TestCarContext mCarContext;
    private PoiRepository mRepository;

    @Before
    public void setUp() {
        mCarContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        mRepository = PoiRepository.anchored();
    }

    /** Step 1 renders search with the full anchor set behind it. */
    @Test
    public void searchScreenListsEveryPlace() {
        A2uiPoiSearchScreen search = startedSearch();

        SearchTemplate template = (SearchTemplate) search.onGetTemplate();

        assertThat(template.getSearchHint()).contains("Search");
        assertThat(rowTitles(template.getItemList())).containsExactly(
                "Joe's Pizza", "The Met Fifth Avenue", "Grand Central Terminal",
                "Grand Central Oyster Bar", "NY Public Library", "Loeb Boathouse");
        for (Row row : rows(template.getItemList())) {
            assertThat(row.getOnClickDelegate()).isNotNull();
        }
    }

    /** Step 2 renders the matches as a place list; the title says what was asked. */
    @Test
    public void submittedQueryPushesFilteredPlaceList() {
        A2uiPoiSearchScreen search = startedSearch();

        assertThat(search.openResults("pizza")).isTrue();

        Screen top = search.getScreenManager().getTop();
        assertThat(top).isInstanceOf(A2uiPoiListScreen.class);
        PlaceListMapTemplate template = (PlaceListMapTemplate) top.onGetTemplate();
        assertThat(template.getTitle().toString()).contains("pizza");
        assertThat(rowTitles(template.getItemList())).containsExactly("Joe's Pizza");
    }

    /** A results row carries its own push to the map step. */
    @Test
    public void resultsRowsPushTheMapStep() {
        A2uiPoiListScreen list = startedList("42nd");
        PlaceListMapTemplate template = (PlaceListMapTemplate) list.onGetTemplate();
        List<Row> resultRows = rows(template.getItemList());
        assertThat(resultRows).hasSize(2);
        assertThat(resultRows.get(0).getOnClickDelegate()).isNotNull();

        assertThat(list.openMap(mRepository.get("grand_central"))).isTrue();

        Screen top = list.getScreenManager().getTop();
        assertThat(top).isInstanceOf(A2uiPoiMapScreen.class);
        MapWithContentTemplate map = (MapWithContentTemplate) top.onGetTemplate();
        PaneTemplate content = (PaneTemplate) map.getContentTemplate();
        assertThat(content.getTitle().toString()).isEqualTo("Grand Central Terminal");
    }

    /** Step 4 carries the address, the notes, and the way out. */
    @Test
    public void detailPaneCarriesAddressNotesAndNavigate() {
        A2uiPoiMapScreen map = startedMap(mRepository.get("oyster_bar"));

        assertThat(map.openDetail()).isTrue();

        Screen top = map.getScreenManager().getTop();
        assertThat(top).isInstanceOf(A2uiPoiDetailScreen.class);
        Pane pane = ((PaneTemplate) top.onGetTemplate()).getPane();
        assertThat(rowTitles(pane.getRows())).contains("89 E 42nd St, Lower Level");
        assertThat(rowTexts(pane.getRows())).contains("Open since 1913. The vaulted ceiling "
                + "carries a whisper corner to corner.");
        List<String> actions = new ArrayList<>();
        for (Action action : pane.getActions()) {
            actions.add(action.getTitle().toString());
        }
        assertThat(actions).contains("Navigate");
    }

    /**
     * The 4-step cap: search, list, map, detail all land, and a fifth push is refused with the
     * detail screen still on top.
     */
    @Test
    public void fifthPushIsRefusedWithDetailOnTop() {
        A2uiPoiSearchScreen search = startedSearch();
        assertThat(search.openResults("pizza")).isTrue();
        A2uiPoiListScreen list = (A2uiPoiListScreen) search.getScreenManager().getTop();
        assertThat(list.openMap(mRepository.get("joes_pizza"))).isTrue();
        A2uiPoiMapScreen map = (A2uiPoiMapScreen) search.getScreenManager().getTop();
        assertThat(map.openDetail()).isTrue();
        Screen detail = search.getScreenManager().getTop();
        assertThat(detail).isInstanceOf(A2uiPoiDetailScreen.class);

        assertThat(PoiFlow.push(detail,
                new A2uiPoiSearchScreen(mCarContext, mRepository))).isFalse();
        assertThat(search.getScreenManager().getTop()).isSameInstanceAs(detail);
    }

    @Test
    public void screensCarryTheirDepths() {
        assertThat(new A2uiPoiSearchScreen(mCarContext, mRepository).poiDepth()).isEqualTo(1);
        assertThat(new A2uiPoiListScreen(mCarContext, mRepository, "").poiDepth()).isEqualTo(2);
        assertThat(new A2uiPoiMapScreen(mCarContext, mRepository.get("nypl")).poiDepth())
                .isEqualTo(3);
        assertThat(new A2uiPoiDetailScreen(mCarContext, mRepository.get("nypl")).poiDepth())
                .isEqualTo(4);
    }

    private A2uiPoiSearchScreen startedSearch() {
        A2uiPoiSearchScreen search = new A2uiPoiSearchScreen(mCarContext, mRepository);
        new ScreenController(search).moveToState(Lifecycle.State.STARTED);
        return search;
    }

    private A2uiPoiListScreen startedList(String query) {
        A2uiPoiListScreen list = new A2uiPoiListScreen(mCarContext, mRepository, query);
        new ScreenController(list).moveToState(Lifecycle.State.STARTED);
        return list;
    }

    private A2uiPoiMapScreen startedMap(PoiPlace place) {
        A2uiPoiMapScreen map = new A2uiPoiMapScreen(mCarContext, place);
        new ScreenController(map).moveToState(Lifecycle.State.STARTED);
        return map;
    }

    private static List<Row> rows(ItemList items) {
        List<Row> rows = new ArrayList<>();
        for (Object item : items.getItems()) {
            rows.add((Row) item);
        }
        return rows;
    }

    private static List<String> rowTitles(ItemList items) {
        List<String> titles = new ArrayList<>();
        for (Row row : rows(items)) {
            titles.add(row.getTitle().toString());
        }
        return titles;
    }

    private static List<String> rowTitles(List<Row> rows) {
        List<String> titles = new ArrayList<>();
        for (Row row : rows) {
            titles.add(row.getTitle().toString());
        }
        return titles;
    }

    private static List<String> rowTexts(List<Row> rows) {
        List<String> texts = new ArrayList<>();
        for (Row row : rows) {
            for (Object text : row.getTexts()) {
                texts.add(text.toString());
            }
        }
        return texts;
    }
}
