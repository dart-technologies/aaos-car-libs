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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;

import art.dart.a2ui.poi.PoiFlow;
import art.dart.a2ui.poi.PoiPlace;
import art.dart.a2ui.poi.PoiRepository;
import art.dart.a2ui.poi.PoiScreen;

/**
 * Step 1 of the POI flow: a search box over the anchored corpus. Typing filters the suggestion
 * list in place; submitting, or tapping a suggestion, opens the results list. No location fix is
 * read — AAOS vehicles often lack network location, and the anchor set needs none.
 */
public final class A2uiPoiSearchScreen extends Screen implements PoiScreen {

    private final PoiRepository mRepository;
    private String mQuery = "";

    public A2uiPoiSearchScreen(@NonNull CarContext carContext,
            @NonNull PoiRepository repository) {
        super(carContext);
        mRepository = repository;
    }

    @Override
    public int poiDepth() {
        return 1;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ItemList.Builder items = new ItemList.Builder();
        for (PoiPlace place : mRepository.search(mQuery)) {
            items.addItem(new Row.Builder()
                    .setTitle(place.getName())
                    .addText(place.getAddress())
                    .setOnClickListener(() -> openResults(place.getName()))
                    .build());
        }
        return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
                    @Override
                    public void onSearchTextChanged(@NonNull String searchText) {
                        mQuery = searchText;
                        invalidate();
                    }

                    @Override
                    public void onSearchSubmitted(@NonNull String searchText) {
                        openResults(searchText);
                    }
                })
                .setSearchHint("Search nearby places")
                .setShowKeyboardByDefault(false)
                .setHeaderAction(Action.BACK)
                .setItemList(items.build())
                .build();
    }

    /** Pushes the results list for this query; the flow cap decides whether it lands. */
    @VisibleForTesting
    boolean openResults(@NonNull String query) {
        return PoiFlow.push(this,
                new A2uiPoiListScreen(getCarContext(), mRepository, query));
    }
}
