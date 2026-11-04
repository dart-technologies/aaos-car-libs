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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The real café behind {@code ev_charge_coffee}'s usual-coffee order, from the bundled
 * {@code coffee/COFFEE.json} (scripts/muse-curate-coffee.py: an OpenStreetMap café, Muse's cited
 * research, walking minutes and scores computed by the script). The sentences are host templates
 * over the fixture's values; nothing is ever sent to the café. Absent, unreadable or curated for
 * another charger: the generic corpus words.
 */
final class CoffeeFixture {

    static final String ASSET = "coffee/COFFEE.json";
    /** The script's cap, one way; a fixture claiming more is not this demo's. */
    static final int MAX_WALK_MINUTES = 6;
    private static final Pattern PRICE = Pattern.compile("\\$\\d{1,2}(\\.\\d{2})?");
    /** The only lead-ins a source line may have: a cited host, or the café that came second. */
    private static final String[] SOURCE_PREFIXES = {"Source · ", "Runner-up: "};

    static final class Pick {
        @NonNull final String mName;
        final int mWalkMinutes;
        /** "Muse compared 15 cafés · open till 7 · oat milk · 3 min walk": cited fields only. */
        @NonNull final String mWhy;
        /** "Source · host" for the citation behind the hours, or "Runner-up: name". */
        @NonNull final String mSource;
        /** A menu price the research cited, or null: no price is ever made up. */
        @Nullable final String mPrice;
        /** The charger label (as Host B draws it) the walk is measured from. */
        @NonNull final String mCharger;

        Pick(@NonNull String name, int walkMinutes, @NonNull String why, @NonNull String source,
                @Nullable String price, @NonNull String charger) {
            mName = name;
            mWalkMinutes = walkMinutes;
            mWhy = why;
            mSource = source;
            mPrice = price;
            mCharger = charger;
        }

        /** True for the charger this café's walk was measured from. */
        boolean isNear(@Nullable String chargerName) {
            return mCharger.equals(chargerName);
        }

        @NonNull
        String confirmTitle() {
            return mPrice == null ? "Order your usual: oat milk latte?"
                    : "Order your usual: oat milk latte, " + mPrice + "?";
        }

        @NonNull
        String confirmDetail() {
            return mName + " · " + mWalkMinutes + " min walk from the charger";
        }

        /** A demo order: nothing reaches the café, so nothing says the coffee is ready. */
        @NonNull
        String pickupTitle() {
            return "Demo order · " + mName;
        }

        @NonNull
        String pickupDetail() {
            return "Oat milk latte · not sent · " + mWalkMinutes + " min walk";
        }

        @NonNull
        String whyTitle() {
            return "Why " + mName;
        }

        @NonNull
        List<String> whyBody() {
            return Arrays.asList(mWhy, mSource);
        }
    }

    private CoffeeFixture() {
    }

    @Nullable
    static Pick load(@NonNull Context context) {
        return parse(ChargerFixture.readAsset(context, ASSET));
    }

    /** The fixture's {@code display} block, or null when anything it needs is missing or off. */
    @VisibleForTesting
    @Nullable
    static Pick parse(@Nullable String json) {
        if (json == null) return null;
        try {
            JSONObject display = new JSONObject(json).getJSONObject("display");
            String name = display.getString("name").trim();
            int walk = display.getInt("walkMinutes");
            String why = display.getString("why").trim();
            String source = display.getString("source").trim();
            String charger = display.getString("charger").trim();
            String price = display.isNull("price") ? null : display.getString("price").trim();
            if (name.isEmpty() || why.isEmpty() || charger.isEmpty()
                    || !knownSource(source) || walk < 1 || walk > MAX_WALK_MINUTES) {
                return null;
            }
            return new Pick(name, walk, why, source,
                    price != null && PRICE.matcher(price).matches() ? price : null, charger);
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** A known lead-in with something after it; anything else drops the card (fail closed). */
    private static boolean knownSource(@NonNull String source) {
        for (String prefix : SOURCE_PREFIXES) {
            if (source.startsWith(prefix) && !source.substring(prefix.length()).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
