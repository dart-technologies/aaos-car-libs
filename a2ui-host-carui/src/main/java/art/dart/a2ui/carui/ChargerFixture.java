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
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Real fast chargers (OpenStreetMap) from the bundled {@code chargers/chargers.json}, turned into
 * {@code updateDataModel} messages for {@code ev_charge_coffee}, so they reach the screen through
 * the processor and the prune like any agent text. Absent or unreadable: the case's DEMO rows.
 */
final class ChargerFixture {

    static final String ASSET = "chargers/chargers.json";
    static final String SURFACE = "ev_charge_coffee";
    /** Distances are from The Met Fifth Avenue, where chapter two starts. */
    static final double ORIGIN_LAT = 40.7794;
    static final double ORIGIN_LON = -73.9632;
    /** No live occupancy feed: waits are labelled DEMO estimates. */
    private static final int[] DEMO_WAIT_MIN = {4, 9, 2};

    static final class Charger {
        final String mName;
        final double mMiles;
        final double mKw;

        Charger(String name, double miles, double kw) {
            mName = name;
            mMiles = miles;
            mKw = kw;
        }
    }

    private ChargerFixture() {
    }

    /** The data-model messages for the three nearest chargers, or empty to keep the defaults. */
    @NonNull
    static List<A2uiMessage> messages(@NonNull Context context) {
        return messagesFor(parse(readAsset(context)));
    }

    @VisibleForTesting
    @NonNull
    static List<A2uiMessage> messagesFor(@NonNull List<Charger> chargers) {
        List<A2uiMessage> out = new ArrayList<>();
        if (chargers.size() < 3) return out;
        for (int i = 0; i < 3; i++) {
            Charger c = chargers.get(i);
            out.add(A2uiMessage.updateDataModel(UseCaseCorpus.VERSION, SURFACE,
                    "/chargers/" + i + "/name", c.mName));
            out.add(A2uiMessage.updateDataModel(UseCaseCorpus.VERSION, SURFACE,
                    "/chargers/" + i + "/detail", String.format(Locale.US,
                            "%.1f mi · %d kW · est. wait %d min (DEMO)", c.mMiles,
                            Math.round(c.mKw), DEMO_WAIT_MIN[i])));
        }
        return out;
    }

    /** Chargers with a name, a position and a power rating, nearest first. */
    @VisibleForTesting
    @NonNull
    static List<Charger> parse(@Nullable String json) {
        List<Charger> chargers = new ArrayList<>();
        if (json == null) return chargers;
        List<JSONObject> objects = new ArrayList<>();
        try {
            collect(new JSONTokener(json).nextValue(), objects);
        } catch (JSONException malformed) {
            return chargers;
        }
        for (JSONObject o : objects) {
            String name = o.optString("name", "").trim();
            double lat = first(o, "lat", "latitude");
            double lon = first(o, "lon", "lng", "longitude");
            double kw = first(o, "kW", "kw", "powerKw", "power_kw", "maxKw");
            if (name.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon) || !(kw > 0)) continue;
            String operator = o.optString("operator", "").trim();
            String label = operator.isEmpty() || name.contains(operator) ? name
                    : name + " · " + operator;
            chargers.add(new Charger(label, miles(ORIGIN_LAT, ORIGIN_LON, lat, lon), kw));
        }
        chargers.sort(Comparator.comparingDouble(c -> c.mMiles));
        return chargers;
    }

    private static double first(JSONObject o, String... keys) {
        for (String key : keys) {
            if (o.has(key)) {
                double v = o.optDouble(key, Double.NaN);
                if (!Double.isNaN(v)) return v;
            }
        }
        return Double.NaN;
    }

    private static void collect(@Nullable Object node, List<JSONObject> out)
            throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("name")) out.add(object);
            JSONArray names = object.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                Object child = object.get(names.getString(i));
                if (child instanceof JSONArray || child instanceof JSONObject) collect(child, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collect(array.get(i), out);
        }
    }

    /** Great-circle distance in miles. */
    static double miles(double lat1, double lon1, double lat2, double lon2) {
        double r = 3958.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    @Nullable
    private static String readAsset(@NonNull Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return null;
        }
    }
}
