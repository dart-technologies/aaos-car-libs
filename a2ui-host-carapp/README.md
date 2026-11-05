# Host A — the `androidx.car.app` A2UI showcase

The `:a2ui-model` surface, pruned by a live UX-restriction reading, bounded by the templates host's
content limit, rendered as templates that host validates. Pipeline and host comparison:
[ARCHITECTURE](../docs/ARCHITECTURE.md).

```bash
cd aaos-apps-gradle-project && ./gradlew :a2ui-host-carapp:assembleDebug
adb install -r -t build/a2ui-host-carapp/outputs/apk/debug/a2ui-host-carapp-debug.apk
adb shell am start --user current -n art.dart.a2ui.carapp/androidx.car.app.activity.CarAppActivity
```

Why it exists: it renders against a host nobody here wrote — Google's templates host, already on
stock GAS images — which negotiates an API level, validates templates and rejects over-limit ones.
Nothing is installed into the system image.

## Findings from running it

- **The templates host renders through car-ui-lib.** Its RRO targets the `car-ui-lib` overlayable, so
  Host A and Host B are stacked, not parallel.
- **The surface sits on a private virtual display** (7 on first launch; it changes per session). Car
  service has no UX-restriction configuration for it, so the genuine reading is `DELIVERED 0x1ff`
  while display 0 reads `0x0`. A debug override drives the other end and is always labelled `DEMO`.
- **Prune applies to instances.** A disallowed container takes its subtree, so a `List`-rooted
  surface disappears under `FULLY_RESTRICTED`; the demo surface is rooted at `Column` (`A2uiPrunerTest`).
- **`ListTemplate.Builder.setActionStrip` is accepted and not drawn** on AAOS, with no warning; the
  Speak action lives in the `Header`.
- **Voice** binds the platform `SpeechRecognizer` (not `CarAudioRecord`). `RECORD_AUDIO` is requested
  through `CarContext.requestPermissions` at first use, at most once per screen; approval retries the press.

## Driving the range

Debuggable, non-`user` builds only. The broadcast also drives Host B.

```bash
adb shell am broadcast --user current -a art.dart.a2ui.demo.action.SET --ei art.dart.a2ui.demo.extra.ACTIVE 511
# 0 BASELINE (18/18) · 16 NO_VIDEO (17) · 32 LIMIT_CONTENT (15) · 64 NO_SETUP (13) · 511 (6) · -1 back to live
```

Screenshots of each level are in `docs/screenshots/host-a-*.png`, with provenance sidecars.

## Versions

`androidx.car.app:app:1.7.0`, the newest stable (`1.8.0-beta01` and `1.9.0-alpha01` exist and are not
used). Minimum Car API 7; against the GAS host (1–9) it negotiates 8 and logs the result, unlike
car-ui-lib's plugin ABI, where a mismatch surfaces as `AbstractMethodError`. Release builds validate
hosts against androidx's `hosts_allowlist_sample`; debug builds allow all hosts. Test count:
`docs/gate-counts.tsv`.
