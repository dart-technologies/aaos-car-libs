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

/**
 * The event vocabulary both hosts may emit. Each event carries only the keys
 * {@link A2uiTelemetry} allows for it — enums, ids, and counts, never user content. A voice
 * transcript, a search string, or any other free text must not reach this enum; the scrubber
 * rejects values outside {@code [A-Za-z0-9_.\-]} loudly rather than shipping them quietly.
 */
public enum TelemetryEvent {
    /** A host session started. Keys: {@code root} (poi, showcase), {@code host} (a, b). */
    SESSION_START,
    /** A POI search was submitted. Keys: {@code result_count}. */
    POI_SEARCH_SUBMITTED,
    /** The POI flow advanced. Keys: {@code step} (list, map, detail). */
    POI_STEP,
    /** The POI flow refused a fifth screen. Keys: {@code depth}. */
    POI_CAP_REFUSED,
    /**
     * Voice capture was allowed or refused. Keys: {@code outcome} (allowed, refused),
     * {@code reason} (an Unavailable name, or none). Never the transcript.
     */
    VOICE_DECISION,
    /** A template was handed to the host. Keys: {@code template}, {@code host}. */
    TEMPLATE_RENDERED,
}
