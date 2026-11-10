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
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;

import art.dart.a2ui.poi.PoiFlow;
import art.dart.a2ui.poi.PoiPlace;
import art.dart.a2ui.poi.PoiScreen;

/**
 * Step 3 of the POI flow: the place on the map with a summary pane. No map controller yet — the
 * anchored demo draws no tiles, and the navigation backend that will own the surface lands with
 * C2. The content pane is what the tests assert.
 */
public final class A2uiPoiMapScreen extends Screen implements PoiScreen {

    private final PoiPlace mPlace;

    public A2uiPoiMapScreen(@NonNull CarContext carContext, @NonNull PoiPlace place) {
        super(carContext);
        mPlace = place;
    }

    @Override
    public int poiDepth() {
        return 3;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Pane pane = new Pane.Builder()
                .addRow(new Row.Builder()
                        .setTitle(mPlace.getName())
                        .addText(mPlace.getAddress())
                        .build())
                .addRow(new Row.Builder()
                        .setTitle(mPlace.getCategory())
                        .addText("On the map")
                        .build())
                .addAction(new Action.Builder()
                        .setTitle("Details")
                        .setOnClickListener(this::openDetail)
                        .build())
                .build();
        PaneTemplate content = new PaneTemplate.Builder(pane)
                .setTitle(mPlace.getName())
                .setHeaderAction(Action.BACK)
                .build();
        return new MapWithContentTemplate.Builder()
                .setContentTemplate(content)
                .build();
    }

    /** Pushes the detail pane for this place; the flow cap decides whether it lands. */
    @VisibleForTesting
    boolean openDetail() {
        return PoiFlow.push(this, new A2uiPoiDetailScreen(getCarContext(), mPlace));
    }
}
