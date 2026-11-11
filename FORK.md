# Fork provenance

| | |
|---|---|
| Upstream | `platform/packages/apps/Car/libs` — https://android.googlesource.com/platform/packages/apps/Car/libs |
| Fork point | `e120cb74e7d654cd6cb22fe6127a19eaf2075f6f`, branch `main`, 2025-02-28 (an OWNERS removal) |
| History | Complete and unsquashed (13,824 upstream commits) |
| License | Apache-2.0, unchanged |

**Why.** The AOSP tree is frozen, but `car-ui-lib` still ships to head units as binary-only AARs
(Google Maven `car-ui-lib:2.6.0`, `car-ui-lib-plugin-apis:1.5.0`; no sources). The defects fixed here —
fail-open UXR, the composite `isRestricted`, unset `mIsSecure`, no per-display state — are present in
2.6.0 bytecode, and a binary cannot be patched. So: fork the source, adopt the published plugin ABI.

Divergence is measured, not maintained:

```bash
git rev-list --count e120cb74e..HEAD && git diff --shortstat e120cb74e..HEAD
git diff --stat --diff-filter=M e120cb74e -- '*.java'   # every modified inherited Java file
```

## What the fork changes, and why

| Change | Why |
|---|---|
| Standalone Gradle: tracked wrapper properties, Gradle 9.9.0 / AGP 9.5.1, `mavenCentral()`, out-of-repo projects and absolute `/tmp` output removed, `package=` → `namespace` | Upstream built only inside an AOSP checkout |
| `compileSdk 37`, `targetSdk 36` (`settings.gradle`) | Compile level only makes APIs nameable; target waits for Robolectric to run API 37 (4.16.1's SDK picker fails every suite; 4.17 breaks on CI's JDK) and for a 37 automotive image. Floor is 36 (AndroidX AAR metadata). One `dartVersionCode`/`dartVersionName` for both hosts; release R8 + keyed signing in `release-signing.gradle` |
| `CarUxRestrictionsInfo` `@IntDef` owned in `com.android.car.ui.utils` | Removed from the public SDK at API 35 (`@removed accidentally exposed`) |
| `:car-system-stubs`, `compileOnly` for `car-media-common` only | `CarMediaManager` and `Car.CAR_MEDIA_SERVICE` are `@SystemApi`. A real jar at `prebuilts/android.car-system-stubs.jar` takes precedence. Constants are inlined: copy values from `javap -constants`, never invent |
| `CarUxRestrictionsUtil` (library and plugin copy): fail closed, per display, `RestrictionsSource`, `hasAllRestrictions`, restored `mCar`/`disconnect()`, locked delivery | Upstream reported "unrestricted" when car service was unreachable and kept one process-wide reading; see [ARCHITECTURE](docs/ARCHITECTURE.md) |
| `complyString` truncates via `truncateToComply` | Truncated strings were one character over the limit and could split a surrogate pair |
| Vendored `oem-apis` sources → published `car-ui-lib-plugin-apis:1.5.0`; AAR vendored for Soong, SHA-256 pinned | The AOSP tree had `LayoutStyleOEMV1.getSpanSize` as `default` (published: `abstract`): `AbstractMethodError` against every stock image |
| Reference plugin: nine `RecyclerViewOEMV1` methods; `PluginVersionProviderImpl` honours `maxVersion`; `OemApiUtil` advertises V2 and tests highest first | Present in every published ABI, absent from AOSP; negotiation advertised V1 and adapted dual-version factories as V1 |
| `car-rotary-lib` namespace `com.android.car.ui.rotary`, explicit `R` imports | AGP 9 forbids two modules sharing a namespace (Gradle-only divergence) |
| `car-media-common` client on Media3 | `android.support.v4.media` is deprecated; intentional API break recorded in [`docs/api-surface/MEDIA3-FLIP-BREAK.md`](docs/api-surface/MEDIA3-FLIP-BREAK.md) |
| Inherited defects fixed: IME `setChildSurfacePackage(null)` NPE, duplicate search callbacks (both sides of the plugin), `NotificationPayloadHandler` NPEs, plugin `unregisterAdapterDataObserver` calling `register`, `UriUtils` testing a constant | Each found by a red test; see [STATUS](docs/STATUS.md) for what remains |
| AndroidX bumped one library per gate run; `legacy-support-v4` removed (238-byte aggregator; classpath diff and `javap` scan found no use); `lifecycle-extensions` removed; Guava 33.8.0-android; JUnit `compileOnly`; LeakCanary 2.14 debug-only | Stale, vulnerable or unused; each removal measured, not searched |
| Robolectric 4.16.1; `DirectManipulationHelperTest` moved to Robolectric; androidTest fixes | W^X rejects dexmaker's runtime dex on device |
| Added: `a2ui-model/`, `a2ui-host-carapp/`, `a2ui-host-carui/`, `a2ui-automotive/`, `car-uxr-flutter/`, `scripts/`, `.github/`, `docs/` | The A2UI platform and its gate |

Changed build files say why at the site (`Dart fork note:`); whole-file Java reworks do not.

## Soong: changed or drifted, never run here

`scripts/check-soong-parity.py` reports drift and never edits an `Android.bp`. Known:

- The rotary `R` package is named nowhere Soong reads: the manifest `package=` went in the first
  fork commit (`488d60308`) and `car-ui-lib/car-rotary-lib/Android.bp` has no `package_name`.
- `car-apps-common/Android.bp:43` still lists `androidx.lifecycle_lifecycle-extensions`.
- `car-ui-lib`'s 604-item `<overlayable>` ships under Soong only; Gradle excludes `res-overlayable`.
- `car-ui-lib-oem-apis` is an `android_library_import` of the vendored 1.5.0 AAR.

Merge-ready requires an owner with an AOSP tree to run
`m car-ui-lib-oem-apis car-ui-lib car-ui-lib-plugin` and inspect the plugin APK's resources.

## Licensing

Apache-2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)). Inherited files keep their AOSP headers; the ~124
that ship without one (mostly translated `strings.xml`) are left alone. Every added file carries a
Dart Technologies Apache-2.0 header, and every modified upstream file says so ("Modified by Dart
Technologies, 2026"), per Apache-2.0 §4(b). `scripts/check-license-headers.py` enforces both and fails
when a modified file loses its header; exceptions with reasons in `scripts/license-header-baseline.txt`.
Third-party content (A2UI schemas, the plugin-apis AAR, demo media) is listed in NOTICE.

## Re-syncing

`git remote add upstream <URL above> && git fetch upstream && git merge upstream/main`. Expect Java
conflicts; new `android.car` usage may need `:car-system-stubs` members (a compile-time error).

## Upstream reports

Drafted, none filed (deferred until the repo is publishable):

| Draft | Target | Status |
|---|---|---|
| [`AOSP-FAIL-OPEN-REPORT.md`](docs/upstream/AOSP-FAIL-OPEN-REPORT.md) | AOSP | Fileable (needs AOSP account and CLA) |
| [`AOSP-SECURE-DEFAULT-REPORT.md`](docs/upstream/AOSP-SECURE-DEFAULT-REPORT.md) | AOSP | Hold: `setSecure()` is opt-in by design (`fc27816ff`) |
| [`AOSP-PLUGIN-VERSION-REPORT.md`](docs/upstream/AOSP-PLUGIN-VERSION-REPORT.md) | AOSP | Re-verify against current sources first |
| [`A2UI-PR-DRAFT.md`](docs/upstream/A2UI-PR-DRAFT.md), [`A2UI-SPEC-PROPOSAL-DRAFT.md`](docs/upstream/A2UI-SPEC-PROPOSAL-DRAFT.md) | a2ui-project | PR first; proposal after it lands |
| [`FLUTTER-EMBEDDED-DOCS-DRAFT.md`](docs/upstream/FLUTTER-EMBEDDED-DOCS-DRAFT.md) | flutter/website | Drafted |
| [`upstream-reports/0001`](upstream-reports/0001-PreferenceTest-fix-scroll-click-race.md), [`0002`](upstream-reports/0002-gradle-soong-overlayable-parity.md) | AOSP | 0001 not ready as written; 0002 prepared |
