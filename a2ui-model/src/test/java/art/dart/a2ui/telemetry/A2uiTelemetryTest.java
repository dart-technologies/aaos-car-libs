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
package art.dart.a2ui.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The metadata-only invariant, enforced: exact key sets per event, values inside
 * {@code [A-Za-z0-9_.\-]} and the length cap, and silence without a backend.
 */
public class A2uiTelemetryTest {

    private final FakeBackend mBackend = new FakeBackend();

    @After
    public void tearDown() {
        A2uiTelemetry.resetForTesting();
    }

    @Test
    public void withoutABackendEventsGoNowhere() {
        A2uiTelemetry.event(TelemetryEvent.SESSION_START, "root", "poi");
        // No backend, no throw, nothing recorded anywhere.
    }

    @Test
    public void installedBackendReceivesScrubbedParams() {
        A2uiTelemetry.install(mBackend);

        Map<String, String> params = new HashMap<>();
        params.put("step", "map");
        A2uiTelemetry.event(TelemetryEvent.POI_STEP, params);

        assertThat(mBackend.events).containsExactly(TelemetryEvent.POI_STEP);
        assertThat(mBackend.lastParams).containsExactly("step", "map");
    }

    @Test
    public void singleParamOverloadDelivers() {
        A2uiTelemetry.install(mBackend);

        A2uiTelemetry.event(TelemetryEvent.POI_SEARCH_SUBMITTED, "result_count", "3");

        assertThat(mBackend.lastParams).containsExactly("result_count", "3");
    }

    @Test
    public void unknownKeyThrows() {
        A2uiTelemetry.install(mBackend);

        assertThrows(IllegalArgumentException.class, () ->
                A2uiTelemetry.event(TelemetryEvent.POI_STEP, "transcript", "open"));
        assertThat(mBackend.events).isEmpty();
    }

    @Test
    public void keyForAnotherEventThrows() {
        A2uiTelemetry.install(mBackend);

        assertThrows(IllegalArgumentException.class, () ->
                A2uiTelemetry.event(TelemetryEvent.POI_STEP, "result_count", "3"));
        assertThat(mBackend.events).isEmpty();
    }

    /** A transcript has spaces; metadata never does. The scrubber refuses it loudly. */
    @Test
    public void freeTextValueThrows() {
        A2uiTelemetry.install(mBackend);

        assertThrows(IllegalArgumentException.class, () ->
                A2uiTelemetry.event(TelemetryEvent.VOICE_DECISION, "outcome", "take me home"));
        assertThat(mBackend.events).isEmpty();
    }

    @Test
    public void overlongValueThrows() {
        A2uiTelemetry.install(mBackend);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i <= A2uiTelemetry.MAX_VALUE_LENGTH; i++) {
            value.append('a');
        }

        assertThrows(IllegalArgumentException.class, () ->
                A2uiTelemetry.event(TelemetryEvent.POI_STEP, "step", value.toString()));
        assertThat(mBackend.events).isEmpty();
    }

    @Test
    public void nullBackendRestoresSilence() {
        A2uiTelemetry.install(mBackend);
        A2uiTelemetry.install(null);

        A2uiTelemetry.event(TelemetryEvent.SESSION_START, "root", "poi");

        assertThat(mBackend.events).isEmpty();
    }

    private static final class FakeBackend implements TelemetryBackend {
        final java.util.List<TelemetryEvent> events = new java.util.ArrayList<>();
        Map<String, String> lastParams = new HashMap<>();

        @Override
        public void log(TelemetryEvent event, Map<String, String> params) {
            events.add(event);
            lastParams = params;
        }
    }
}
