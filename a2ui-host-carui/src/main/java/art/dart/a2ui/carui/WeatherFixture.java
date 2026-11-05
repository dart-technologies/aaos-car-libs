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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.protocol.A2uiMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Met's weather row from the bundled National Weather Service forecast
 * ({@code nws/forecast-*.json}, public domain): the first daytime period after the day it was
 * captured (the visit day; the forecast was taken the day before), short enough to read in a
 * glance, e.g. "Chance of showers · high 74°F". It enters through the processor like the
 * chargers, so the prune and limits apply to it as to any text. Absent or unreadable: the case's
 * own words.
 */
final class WeatherFixture {

    static final String DIR = "nws";
    static final String SURFACE = "met_visit";
    static final String COMPONENT = "weather";
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    /** The row's two lines. */
    static final class Forecast {
        @NonNull final String mTitle;
        @NonNull final String mDetail;

        Forecast(@NonNull String title, @NonNull String detail) {
            mTitle = title;
            mDetail = detail;
        }
    }

    private WeatherFixture() {
    }

    /** The update for the weather row, or empty to keep the case's words. */
    @NonNull
    static List<A2uiMessage> messages(@NonNull Context context) {
        return messagesFor(parse(readLatest(context)));
    }

    @VisibleForTesting
    @NonNull
    static List<A2uiMessage> messagesFor(@Nullable Forecast forecast) {
        if (forecast == null) return Collections.emptyList();
        Map<String, Object> row = A2uiMessage.component(COMPONENT, "Text");
        row.put("title", forecast.mTitle);
        row.put("subtitle", forecast.mDetail);
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(row);
        return Collections.singletonList(
                A2uiMessage.updateComponents(UseCaseCorpus.VERSION, SURFACE, components));
    }

    /** The newest bundled forecast, by its dated name, or null when none is bundled. */
    @Nullable
    static String readLatest(@NonNull Context context) {
        String[] names;
        try {
            names = context.getAssets().list(DIR);
        } catch (IOException absent) {
            return null;
        }
        if (names == null) return null;
        List<String> forecasts = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith("forecast-") && name.endsWith(".json")) forecasts.add(name);
        }
        if (forecasts.isEmpty()) return null;
        Collections.sort(forecasts);
        return ChargerFixture.readAsset(context, DIR + "/" + forecasts.get(forecasts.size() - 1));
    }

    /** The visit day's daytime period, or null when anything it needs is missing. */
    @VisibleForTesting
    @Nullable
    static Forecast parse(@Nullable String json) {
        if (json == null) return null;
        try {
            JSONArray periods = new JSONObject(json).getJSONArray("periods");
            if (periods.length() == 0) return null;
            String captured = day(periods.getJSONObject(0).getString("startTime"));
            for (int i = 0; i < periods.length(); i++) {
                JSONObject period = periods.getJSONObject(i);
                String date = day(period.getString("startTime"));
                if (!period.optBoolean("isDaytime") || date.compareTo(captured) <= 0) continue;
                String sky = shorten(period.getString("shortForecast"));
                if (sky.isEmpty() || period.isNull("temperature")) return null;
                int high = period.getInt("temperature");
                String unit = period.optString("temperatureUnit", "F").trim();
                return new Forecast(sky + " · high " + high + "°" + unit,
                        "National Weather Service · " + LocalDate.parse(date).format(DAY)
                                + " forecast");
            }
            return null;
        } catch (JSONException | DateTimeParseException | StringIndexOutOfBoundsException bad) {
            return null;
        }
    }

    /** "2026-11-05T06:00:00-05:00" → "2026-11-05": the forecast office's own local date. */
    private static String day(@NonNull String startTime) {
        return startTime.substring(0, 10);
    }

    /**
     * NWS short forecasts in sentence case, rain showers as showers: "Chance Rain Showers" →
     * "Chance of showers", "Rain Showers Likely" → "Showers likely". Nothing is added.
     */
    @VisibleForTesting
    @NonNull
    static String shorten(@NonNull String shortForecast) {
        String s = shortForecast.trim().replace("Rain Showers", "Showers")
                .toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return s;
        for (String chance : Arrays.asList("slight chance ", "chance ")) {
            if (s.startsWith(chance) && !s.startsWith(chance + "of ")) {
                s = chance + "of " + s.substring(chance.length());
                break;
            }
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
