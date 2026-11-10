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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The telemetry entry point both hosts share. Events are metadata-only by construction: every
 * event allows an exact key set, and every value must match {@code [A-Za-z0-9_.\-]} within
 * {@link #MAX_VALUE_LENGTH} characters. Anything else throws — a transcript with a space in it
 * fails the caller's test, not a PII review months later.
 *
 * <p>Without an installed backend events go nowhere. Debuggable builds install
 * {@link LogBackend}; production installs Firebase once its project exists.
 */
public final class A2uiTelemetry {

    /** Values longer than this are rejected, not truncated: truncation hides a miscoded caller. */
    public static final int MAX_VALUE_LENGTH = 64;

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private static final Map<TelemetryEvent, Set<String>> EVENT_KEYS;
    static {
        Map<TelemetryEvent, Set<String>> keys = new EnumMap<>(TelemetryEvent.class);
        keys.put(TelemetryEvent.SESSION_START, setOf("root", "host"));
        keys.put(TelemetryEvent.POI_SEARCH_SUBMITTED, setOf("result_count"));
        keys.put(TelemetryEvent.POI_STEP, setOf("step"));
        keys.put(TelemetryEvent.POI_CAP_REFUSED, setOf("depth"));
        keys.put(TelemetryEvent.VOICE_DECISION, setOf("outcome", "reason"));
        keys.put(TelemetryEvent.TEMPLATE_RENDERED, setOf("template", "host"));
        EVENT_KEYS = Collections.unmodifiableMap(keys);
    }

    private static volatile TelemetryBackend sBackend = (event, params) -> {
    };

    private A2uiTelemetry() {
    }

    /** Installs the sink. A null backend uninstalls back to the no-op. */
    public static void install(TelemetryBackend backend) {
        sBackend = backend == null ? (event, params) -> {
        } : backend;
    }

    /** Uninstalls back to the no-op. Tests use this; production code uses {@link #install}. */
    @VisibleForTesting
    public static void resetForTesting() {
        sBackend = (event, params) -> {
        };
    }

    /** Emits an event with no params. */
    public static void event(@NonNull TelemetryEvent event) {
        event(event, Collections.emptyMap());
    }

    /** Emits an event with one param. */
    public static void event(@NonNull TelemetryEvent event, @NonNull String key,
            @NonNull String value) {
        Map<String, String> params = new HashMap<>();
        params.put(key, value);
        event(event, params);
    }

    /** Emits an event with two params. */
    public static void event(@NonNull TelemetryEvent event, @NonNull String key1,
            @NonNull String value1, @NonNull String key2, @NonNull String value2) {
        Map<String, String> params = new HashMap<>();
        params.put(key1, value1);
        params.put(key2, value2);
        event(event, params);
    }

    /** Emits an event, scrubbed. Unknown keys and non-metadata values throw. */
    public static void event(@NonNull TelemetryEvent event,
            @NonNull Map<String, String> params) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(params);
        Set<String> allowed = EVENT_KEYS.get(event);
        if (allowed == null) {
            // A new enum value without a key set: refuse to guess what it may carry.
            throw new IllegalArgumentException("telemetry event " + event + " has no key set; "
                    + "add one to A2uiTelemetry before emitting it");
        }
        Map<String, String> scrubbed = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !KEY_PATTERN.matcher(key).matches() || !allowed.contains(key)) {
                throw new IllegalArgumentException("telemetry event " + event + " rejects key "
                        + key + "; allowed: " + allowed);
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH
                    || !VALUE_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("telemetry event " + event + " rejects value "
                        + "for " + key + ": metadata only "
                        + "([A-Za-z0-9_.-], max " + MAX_VALUE_LENGTH + " chars)");
            }
            scrubbed.put(key, value);
        }
        sBackend.log(event, Collections.unmodifiableMap(scrubbed));
    }

    private static Set<String> setOf(String... keys) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, keys);
        return Collections.unmodifiableSet(set);
    }
}
