# Telemetry

Sprint 1 P3. Both hosts share one event vocabulary (`art.dart.a2ui.telemetry`), enforced
metadata-only: the scrubber rejects anything outside `[A-Za-z0-9_.-]`, so a transcript can never
reach a backend through a miscoded call site.

## Events

| Event | Keys | Emitted from |
|---|---|---|
| `SESSION_START` | `root` (showcase, poi), `host` (a, b) | `A2uiSession.onCreateScreen` (Host A; Host B follows) |
| `POI_SEARCH_SUBMITTED` | `result_count` | `A2uiPoiSearchScreen.openResults` |
| `POI_STEP` | `step` (list, map, detail) | POI `openResults`/`openMap`/`openDetail`, on pushed screens only |
| `POI_CAP_REFUSED` | `depth` | `PoiFlow.push` when the 4-screen cap refuses |
| `VOICE_DECISION` | `outcome` (allowed, refused), `reason` (an `Unavailable` name, or none) | `A2uiVoiceCapture.listen`, once per call |
| `TEMPLATE_RENDERED` | `template` (class simple name), `host` | `A2uiShowcaseScreen.onGetTemplate` |

Rules: exact key set per event (unknown keys throw), values match
`[A-Za-z0-9_.-]` and stay within 64 chars (anything else throws). No counts are
derived host-side; the backend stores what it gets.

## Backends

- Default: no-op. Events go nowhere until a backend is installed.
- `LogBackend`: one logcat line per event. Installed by debuggable builds.
- Firebase (Crashlytics + Analytics): the planned production backend. Not wired yet.

## Firebase follow-up (dashboard: pending)

Creating the project is a console step and cannot land as a PR. The owner runbook:

1. Create the Firebase project, add both app ids (`art.dart.a2ui.carapp`,
   `art.dart.a2ui.carui`), download `google-services.json` per app into the module dir
   (gitignored — it names the project, and the file is per-developer).
2. Add the Google services plugin + Firebase BOM/crashlytics/analytics to the version
   catalog, apply in both hosts, regenerate `gradle/verification-metadata.xml`.
3. Implement `TelemetryBackend` over `FirebaseAnalytics.logEvent` (events map 1:1;
   Crashlytics custom keys take `SESSION_START`), install in both services'
   `onCreate` for non-debuggable builds.
4. Record the crash-free-rate dashboard link here and run the PII review the R7
   acceptance requires (new events against this doc's table).

Until then the crash-free-rate dashboard does not exist; logcat + the JVM suites
(`A2uiTelemetryTest`, `TelemetryHooksTest`) are the verification.
