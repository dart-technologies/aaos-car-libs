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
package art.dart.a2ui.weather;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The offline weather fixture: current conditions plus an hourly strip and a daily strip for
 * one anchored location. The gateway client replaces this per the R3 phasing, not the screens.
 */
public final class WeatherRepository {

    /** One forecast entry: a label, a temperature, and a short condition. */
    public static final class Entry {
        private final String mLabel;
        private final int mTempC;
        private final String mCondition;

        public Entry(@NonNull String label, int tempC, @NonNull String condition) {
            mLabel = label;
            mTempC = tempC;
            mCondition = condition;
        }

        @NonNull public String getLabel() { return mLabel; }
        public int getTempC() { return mTempC; }
        @NonNull public String getCondition() { return mCondition; }
    }

    private final String mLocation;
    private final int mCurrentTempC;
    private final String mCurrentCondition;
    private final List<Entry> mHourly;
    private final List<Entry> mDaily;

    /** Midtown, 9 C and clearing — the states the emulator walk starts from. */
    @NonNull
    public static WeatherRepository anchored() {
        List<Entry> hourly = new ArrayList<>();
        hourly.add(new Entry("Now", 9, "Clearing"));
        hourly.add(new Entry("1 PM", 10, "Sunny"));
        hourly.add(new Entry("2 PM", 11, "Sunny"));
        hourly.add(new Entry("3 PM", 11, "Partly cloudy"));
        List<Entry> daily = new ArrayList<>();
        daily.add(new Entry("Today", 11, "Sunny intervals"));
        daily.add(new Entry("Tue", 8, "Showers"));
        daily.add(new Entry("Wed", 6, "Sunny"));
        return new WeatherRepository("Midtown", 9, "Clearing", hourly, daily);
    }

    private WeatherRepository(String location, int currentTempC, String currentCondition,
            List<Entry> hourly, List<Entry> daily) {
        mLocation = location;
        mCurrentTempC = currentTempC;
        mCurrentCondition = currentCondition;
        mHourly = Collections.unmodifiableList(new ArrayList<>(hourly));
        mDaily = Collections.unmodifiableList(new ArrayList<>(daily));
    }

    @NonNull public String getLocation() { return mLocation; }
    public int getCurrentTempC() { return mCurrentTempC; }
    @NonNull public String getCurrentCondition() { return mCurrentCondition; }
    @NonNull public List<Entry> getHourly() { return mHourly; }
    @NonNull public List<Entry> getDaily() { return mDaily; }
}
