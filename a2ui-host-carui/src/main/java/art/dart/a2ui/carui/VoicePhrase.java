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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Normalises a transcript before the exact-phrase match: speech services write "Reserve entry for
 * 2 at 3 PM." for "reserve entry for two at three". Matching stays exact and still searches only
 * what is on screen; this only removes punctuation, case, digits and clock suffixes.
 */
final class VoicePhrase {

    private static final List<String> NUMBERS = Arrays.asList("zero", "one", "two", "three",
            "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve");

    private VoicePhrase() {
    }

    @Nullable
    static String canonical(@Nullable String spoken) {
        if (spoken == null) return null;
        String text = spoken.toLowerCase(Locale.ROOT)
                .replace("p.m.", " pm ").replace("a.m.", " am ")
                .replaceAll("[^a-z0-9' ]", " ");
        List<String> out = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            boolean clock = ("pm".equals(token) || "am".equals(token) || "o'clock".equals(token))
                    && !out.isEmpty() && NUMBERS.contains(out.get(out.size() - 1));
            if (clock) continue;
            if (token.matches("\\d{1,2}") && Integer.parseInt(token) < NUMBERS.size()) {
                token = NUMBERS.get(Integer.parseInt(token));
            }
            out.add(token);
        }
        if (!out.isEmpty() && "please".equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return String.join(" ", out);
    }
}
