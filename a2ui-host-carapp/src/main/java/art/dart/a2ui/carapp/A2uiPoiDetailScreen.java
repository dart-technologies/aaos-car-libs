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
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import art.dart.a2ui.poi.PoiPlace;
import art.dart.a2ui.poi.PoiScreen;

/**
 * Step 4 of the POI flow, and the last: the full detail pane. Nothing pushes from here, so the
 * flow cap holds structurally as well as in {@code PoiFlow}. Navigate announces a demo toast;
 * real routing lands with C2.
 */
public final class A2uiPoiDetailScreen extends Screen implements PoiScreen {

    private final PoiPlace mPlace;

    public A2uiPoiDetailScreen(@NonNull CarContext carContext, @NonNull PoiPlace place) {
        super(carContext);
        mPlace = place;
    }

    @Override
    public int poiDepth() {
        return 4;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Pane pane = new Pane.Builder()
                .addRow(new Row.Builder()
                        .setTitle(mPlace.getAddress())
                        .addText(mPlace.getCategory())
                        .build())
                .addRow(new Row.Builder()
                        .setTitle("Good to know")
                        .addText(mPlace.getDetails())
                        .build())
                .addAction(new Action.Builder()
                        .setTitle("Navigate")
                        .setOnClickListener(() -> CarToast.makeText(getCarContext(),
                                "Navigating to " + mPlace.getName() + " (demo)",
                                CarToast.LENGTH_SHORT).show())
                        .build())
                .build();
        return new PaneTemplate.Builder(pane)
                .setTitle(mPlace.getName())
                .setHeaderAction(Action.BACK)
                .build();
    }
}
