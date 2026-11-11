# Sprint 1 emulator verification (2026-11-11)

POI search → list → map → detail walk on the local AAOS emulator. Sprint 1 exit
criterion: Host A installable as a signed POI app with full template coverage.

## Environment

| | |
|---|---|
| AVD | `AAOS_35` (`automotive_1080p_landscape`), snapshot `booted` (~30 s reboot) |
| Image | `system-images;android-35-ext15;android-automotive;arm64-v8a` r4, SHA-1 `c09e314f49e53b23721147480db60de7dd903399` |
| Platform | Android 15, `ro.hardware.type=automotive`, templates host + media present |
| Build | Debug with temporary `PoiVerify` Log.d instrumentation (**reverted** after the run); release APK smoked separately |
| Session tweaks | DisplayCompat interrupter disabled (`pm disable-user`) — it steals focus mid-walk |

## Walk

Full walk green: showcase → POI search → results list → map pane → detail pane.
Telemetry chain observed on the walk:

- `SESSION_START root=poi`
- `POI_SEARCH_SUBMITTED result_count=1`
- `POI_STEP list`, `POI_STEP map`, `POI_STEP detail`

Release APK smokes clean with silent telemetry. Host B launches but renders no
text — expected pre-broadcast state, not a regression.

## Evidence

Committed under `docs/screenshots/` (see the evidence contract in
`scripts/check-screenshot-evidence.py`):

| Capture | Shows |
|---|---|
| `host-a-poi-showcase.png` | Showcase surface |
| `host-a-poi-search.png` | POI search input |
| `host-a-poi-results.png` | POI results list |
| `host-a-poi-detail.png` | POI detail pane |
| `host-b-poi-baseline.png` | Host B launch, pre-broadcast |

Caveats, stated plainly:

- The raw captures carried adb noise (a multi-display `[Warning]` prefix and a
  trailing stderr fragment); the committed PNGs are the recovered byte ranges,
  CRC-validated. No display id was specified — future captures must pass
  `screencap -d` with an id from `dumpsys SurfaceFlinger --display-id`.
- There is **no committed map-pane capture**. The map step is proven by the
  `POI_STEP map` telemetry event and was seen live; the map-pane screenshot did
  not survive the session.
- The walk ran on a debug build with temporary instrumentation (since reverted).
  Behavior-critical claims (permission declaration, template audit) are pinned by
  JVM tests and CI gates, not by these captures.

## Findings

- **Map permission crash → PR #47 (merged).** The map pane crashed with a
  `MAP_TEMPLATES` `SecurityException`. Fix is a manifest declaration plus a
  template-permission audit rule, not a runtime grant: the image defines no such
  permission to grant. Library-permission fallback lives in
  `CarAppPermission.checkHasLibraryPermission`.
- **Row click-listener crash on the map pane** was an artifact of the temporary
  verification listener; reverted with the instrumentation, not a product bug.
- **DisplayCompat focus theft** required disabling the interrupter for the
  session; re-captures after refocus replaced stale screenshots.

## Follow-ups (Sprint 2)

- MapWithContent host-input behavior: the JVM suite covers the push path only;
  host input needs an emulator proof pass with `-d` captures, including the
  missing map-pane shot.
- Host B content pass: drive Host B with broadcast content and capture text
  rendering; current baseline proves launch only.
