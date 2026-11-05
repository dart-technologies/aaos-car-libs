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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The six Met Open Access works (CC0) as the host knows them, and when each appears in the
 * artwork film: per-work start times from the bundled {@code met/FILM.json}, else a 6 s cadence.
 */
final class MetArtworks {

    static final class Work {
        final long mObjectId;
        @NonNull final String mTitle;
        @NonNull final String mByline;

        Work(long objectId, @NonNull String title, @NonNull String byline) {
            mObjectId = objectId;
            mTitle = title;
            mByline = byline;
        }

        @NonNull
        String drawableName() {
            return "met_" + mObjectId;
        }

        int drawable() {
            switch ((int) mObjectId) {
                case 436535: return R.drawable.met_436535;
                case 437881: return R.drawable.met_437881;
                case 437394: return R.drawable.met_437394;
                case 435809: return R.drawable.met_435809;
                case 436105: return R.drawable.met_436105;
                case 435868:
                default: return R.drawable.met_435868;
            }
        }
    }

    /** Gallery order, matching {@code met_visit} and the film. Captions from SOURCE.json. */
    static final List<Work> WORKS = Collections.unmodifiableList(Arrays.asList(
            new Work(436535, "Wheat Field with Cypresses", "Vincent van Gogh · 1889"),
            new Work(437881, "Young Woman with a Water Pitcher", "Johannes Vermeer · ca. 1662"),
            new Work(437394, "Aristotle with a Bust of Homer",
                    "Rembrandt (Rembrandt van Rijn) · 1653"),
            new Work(435809, "The Harvesters", "Pieter Bruegel the Elder · 1565"),
            new Work(436105, "The Death of Socrates", "Jacques Louis David · 1787"),
            new Work(435868, "The Card Players", "Paul Cézanne · 1890–92")));

    static final long FALLBACK_CADENCE_MS = 6000;
    static final String FILM_ASSET = "met/FILM.json";

    @Nullable private static long[] sStarts;

    private MetArtworks() {
    }

    /** Start of each work in the film, in WORKS order. Cached after the first read. */
    @NonNull
    static synchronized long[] filmStarts(@NonNull Context context) {
        if (sStarts == null) {
            sStarts = parseStarts(readAsset(context, FILM_ASSET));
        }
        return sStarts;
    }

    /**
     * Start times by objectID from FILM.json (any shape holding objects with {@code objectID} and
     * {@code startMs}); works it does not list, or a missing or unreadable file, use the cadence.
     */
    @VisibleForTesting
    @NonNull
    static long[] parseStarts(@Nullable String json) {
        long[] starts = new long[WORKS.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = i * FALLBACK_CADENCE_MS;
        if (json == null) return starts;
        List<JSONObject> entries = new ArrayList<>();
        try {
            Object root = new org.json.JSONTokener(json).nextValue();
            collect(root, entries);
        } catch (JSONException malformed) {
            return starts;
        }
        long[] found = new long[WORKS.size()];
        boolean[] seen = new boolean[WORKS.size()];
        for (JSONObject entry : entries) {
            long id = entry.optLong("objectID", -1);
            long start = entry.optLong("startMs", -1);
            for (int i = 0; i < WORKS.size(); i++) {
                if (WORKS.get(i).mObjectId == id && start >= 0) {
                    found[i] = start;
                    seen[i] = true;
                }
            }
        }
        for (boolean s : seen) {
            if (!s) return starts; // partial timing would desynchronise the card; use the cadence
        }
        return found;
    }

    /** The works as slides, captioned and credited. */
    @NonNull
    static List<ArtworkSlideshow.Slide> slides() {
        List<ArtworkSlideshow.Slide> slides = new ArrayList<>();
        for (Work work : WORKS) {
            slides.add(new ArtworkSlideshow.Slide(work.drawable(), work.mTitle, work.mByline,
                    DemoNativeContract.MET_CREDIT));
        }
        return slides;
    }

    /** The index of the work on screen at {@code positionMs}. */
    static int segmentAt(@NonNull long[] starts, long positionMs) {
        int segment = 0;
        for (int i = 0; i < starts.length; i++) {
            if (positionMs >= starts[i]) segment = i;
        }
        return segment;
    }

    private static void collect(@Nullable Object node, List<JSONObject> out) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("objectID") && object.has("startMs")) out.add(object);
            JSONArray names = object.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                collect(object.get(names.getString(i)), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collect(array.get(i), out);
        }
    }

    @Nullable
    private static String readAsset(@NonNull Context context, @NonNull String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return null;
        }
    }

    @VisibleForTesting
    static synchronized void resetForTesting() {
        sStarts = null;
    }
}
