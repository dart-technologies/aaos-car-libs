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

import androidx.annotation.NonNull;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.lifecycle.Lifecycle;

import art.dart.a2ui.iot.IotFlow;
import art.dart.a2ui.iot.IotRepository;
import art.dart.a2ui.iot.IotScreen;
import art.dart.a2ui.weather.WeatherFlow;
import art.dart.a2ui.weather.WeatherRepository;
import art.dart.a2ui.weather.WeatherScreen;

import androidx.car.app.Screen;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * The anchored IoT and weather flows on the JVM: the grid lists every device with a working
 * toggle, the weather list carries hourly and daily sections, and both flows share the
 * four-screen push cap.
 */
@RunWith(RobolectricTestRunner.class)
public class IotWeatherScreensTest {

    private TestCarContext mCarContext;

    @Before
    public void setUp() {
        mCarContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
    }

    @Test
    public void listShowsEveryDeviceWithToggleState() {
        A2uiIotListScreen list = startedList(IotRepository.anchored());

        ListTemplate template = (ListTemplate) list.onGetTemplate();

        List<String> titles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Boolean> checked = new ArrayList<>();
        for (Object item : template.getSingleList().getItems()) {
            Row row = (Row) item;
            titles.add(row.getTitle().toString());
            texts.add(rowTexts(row).get(0));
            assertThat(row.getToggle()).isNotNull();
            checked.add(row.getToggle().isChecked());
        }
        assertThat(titles).containsExactly(
                "Living room light", "Porch light", "Hall thermostat");
        assertThat(texts).containsExactly("On · 80%", "Off · 100%", "On · 20.5°C");
        assertThat(checked).containsExactly(true, false, true).inOrder();
    }

    @Test
    public void toggleWritesThroughAndRefreshes() {
        IotRepository repository = IotRepository.anchored();
        A2uiIotListScreen list = startedList(repository);

        list.setDevice("porch_light", true);

        assertThat(repository.device("porch_light").isOn()).isTrue();
        ListTemplate template = (ListTemplate) list.onGetTemplate();
        List<String> texts = new ArrayList<>();
        for (Object item : template.getSingleList().getItems()) {
            texts.add(rowTexts((Row) item).get(0));
        }
        assertThat(texts).contains("On · 100%");

        list.setDevice("porch_light", false);
        assertThat(repository.device("porch_light").isOn()).isFalse();
    }

    @Test
    public void weatherListCarriesHourlyAndDaily() {
        A2uiWeatherListScreen weather = startedWeather(WeatherRepository.anchored());

        ListTemplate template = (ListTemplate) weather.onGetTemplate();

        assertThat(template.getTitle().toString()).contains("Midtown");
        assertThat(template.getTitle().toString()).contains("9°C");
        assertThat(template.getSectionedLists()).hasSize(2);
        List<String> hourly = new ArrayList<>();
        for (Object item : template.getSectionedLists().get(0).getItemList().getItems()) {
            hourly.add(((Row) item).getTitle().toString());
        }
        assertThat(hourly).containsExactly(
                "Now · 9°C", "1 PM · 10°C", "2 PM · 11°C", "3 PM · 11°C");
        List<String> daily = new ArrayList<>();
        for (Object item : template.getSectionedLists().get(1).getItemList().getItems()) {
            daily.add(((Row) item).getTitle().toString());
        }
        assertThat(daily).containsExactly("Today · 11°C", "Tue · 8°C", "Wed · 6°C");
    }

    @Test
    public void bothFlowsRefuseTheFifthPush() {
        A2uiIotListScreen list = startedList(IotRepository.anchored());
        A2uiWeatherListScreen weather = startedWeather(WeatherRepository.anchored());
        Screen next = new A2uiIotListScreen(mCarContext, IotRepository.anchored());

        assertThat(IotFlow.push(list, next)).isTrue();
        assertThat(WeatherFlow.push(weather, next)).isTrue();
        assertThat(IotFlow.push(new DeepIotScreen(), next)).isFalse();
        assertThat(WeatherFlow.push(new DeepWeatherScreen(), next)).isFalse();
    }

    private A2uiIotListScreen startedList(IotRepository repository) {
        A2uiIotListScreen list = new A2uiIotListScreen(mCarContext, repository);
        new ScreenController(list).moveToState(Lifecycle.State.STARTED);
        return list;
    }

    private static List<String> rowTexts(Row row) {
        List<String> texts = new ArrayList<>();
        for (Object line : row.getTexts()) {
            texts.add(line.toString());
        }
        return texts;
    }

    private A2uiWeatherListScreen startedWeather(WeatherRepository repository) {
        A2uiWeatherListScreen weather = new A2uiWeatherListScreen(mCarContext, repository);
        new ScreenController(weather).moveToState(Lifecycle.State.STARTED);
        return weather;
    }

    private final class DeepIotScreen extends Screen implements IotScreen {
        DeepIotScreen() {
            super(IotWeatherScreensTest.this.mCarContext);
        }

        @Override
        public int iotDepth() {
            return IotFlow.MAX_SCREENS;
        }

        @NonNull
        @Override
        public androidx.car.app.model.Template onGetTemplate() {
            throw new UnsupportedOperationException("never rendered");
        }
    }

    private final class DeepWeatherScreen extends Screen implements WeatherScreen {
        DeepWeatherScreen() {
            super(IotWeatherScreensTest.this.mCarContext);
        }

        @Override
        public int weatherDepth() {
            return WeatherFlow.MAX_SCREENS;
        }

        @NonNull
        @Override
        public androidx.car.app.model.Template onGetTemplate() {
            throw new UnsupportedOperationException("never rendered");
        }
    }
}
