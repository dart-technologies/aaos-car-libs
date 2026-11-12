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
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SectionedItemList;
import androidx.car.app.model.Template;

import art.dart.a2ui.telemetry.A2uiTelemetry;
import art.dart.a2ui.telemetry.TelemetryEvent;
import art.dart.a2ui.weather.WeatherRepository;
import art.dart.a2ui.weather.WeatherScreen;

/**
 * The weather flow's list: current conditions plus hourly and daily sections. Four hourly
 * and three daily rows stay under the six-row per-list limit by construction, in two sections.
 */
public final class A2uiWeatherListScreen extends Screen implements WeatherScreen {

    private final WeatherRepository mRepository;

    public A2uiWeatherListScreen(@NonNull CarContext carContext,
            @NonNull WeatherRepository repository) {
        super(carContext);
        mRepository = repository;
    }

    @Override
    public int weatherDepth() {
        return 1;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        A2uiTelemetry.event(TelemetryEvent.TEMPLATE_RENDERED, "template", "ListTemplate",
                "host", "a");
        ItemList.Builder hourly = new ItemList.Builder();
        for (WeatherRepository.Entry entry : mRepository.getHourly()) {
            hourly.addItem(new Row.Builder()
                    .setTitle(entry.getLabel() + " · " + entry.getTempC() + "°C")
                    .addText(entry.getCondition())
                    .build());
        }
        ItemList.Builder daily = new ItemList.Builder();
        for (WeatherRepository.Entry entry : mRepository.getDaily()) {
            daily.addItem(new Row.Builder()
                    .setTitle(entry.getLabel() + " · " + entry.getTempC() + "°C")
                    .addText(entry.getCondition())
                    .build());
        }
        return new ListTemplate.Builder()
                .setTitle(mRepository.getLocation() + " · " + mRepository.getCurrentTempC()
                        + "°C " + mRepository.getCurrentCondition())
                .setHeaderAction(Action.BACK)
                .addSectionedList(SectionedItemList.create(hourly.build(), "Hourly"))
                .addSectionedList(SectionedItemList.create(daily.build(), "Daily"))
                .build();
    }
}
