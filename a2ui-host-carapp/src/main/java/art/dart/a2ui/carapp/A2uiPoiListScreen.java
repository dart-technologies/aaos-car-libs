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
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import art.dart.a2ui.poi.PoiFlow;
import art.dart.a2ui.poi.PoiPlace;
import art.dart.a2ui.poi.PoiRepository;
import art.dart.a2ui.poi.PoiScreen;

/**
 * Step 2 of the POI flow: the matches as a place list with a map pane. Tapping a place opens it
 * on the map. No content-refresh listener: the anchor set never changes, and the listener needs
 * car API 5 for nothing.
 */
public final class A2uiPoiListScreen extends Screen implements PoiScreen {

    private final PoiRepository mRepository;
    private final String mQuery;

    public A2uiPoiListScreen(@NonNull CarContext carContext,
            @NonNull PoiRepository repository, @NonNull String query) {
        super(carContext);
        mRepository = repository;
        mQuery = query;
    }

    @Override
    public int poiDepth() {
        return 2;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ItemList.Builder items = new ItemList.Builder();
        for (PoiPlace place : mRepository.search(mQuery)) {
            items.addItem(new Row.Builder()
                    .setTitle(place.getName())
                    .addText(place.getAddress() + " · " + place.getCategory())
                    .setOnClickListener(() -> openMap(place))
                    .build());
        }
        String title = mQuery.trim().isEmpty() ? "Nearby places" : "Results for \"" + mQuery + "\"";
        return new PlaceListMapTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setItemList(items.build())
                .build();
    }

    /** Pushes the map for this place; the flow cap decides whether it lands. */
    @VisibleForTesting
    boolean openMap(@NonNull PoiPlace place) {
        return PoiFlow.push(this, new A2uiPoiMapScreen(getCarContext(), place));
    }
}
