# Status

Open at HEAD, one line each. Investigations and evidence live in git history and the archive bundle.

## Publication (blocked on decisions, not work)

- Not pushed: `origin/main` stops at the third fork commit (`12274f1e7`); see `git rev-list --count origin/main..HEAD`.
- CI has never run: `origin/main` has no `.github/`. Workflows are pinned and actionlint-clean locally only.
- No aggregate CI job (`needs:`), so nothing to require: after the first run add a terminal `gate` job,
  then Tier-2 branch protection on the real job names (expected `Build & JVM tests`, `Hygiene`).
- `sensitivity.yml` runs weekly with no `pull_request` subset; run it by hand before any release.
- CodeQL yields nothing while the repo is private with GHAS disabled.
- Scorecard has `publish_results: false`; flip it in the same change that makes the repo public.
- Upstream reports drafted, none filed; per-draft status in [FORK](../FORK.md#upstream-reports).
- OEM demo is "conditionally ready": local gate and canonical emulator pass; OEM/AOSP Soong, remote CI
  and passenger-display pixels are open exceptions. Do not shorten to "OEM-ready".
- GitHub-first A2UI alpha is a deferred runbook: new public repo from an allowlisted export with a
  seven-commit restack and provenance manifest (never force-rewrite this repo); coordinates
  `art.dart.a2ui:a2ui-core`, `a2ui-automotive-policy`, `a2ui-agent-client`, `a2ui-bom`, `a2ui-model` as a
  one-cycle facade; a Maven-layout ZIP on a GitHub prerelease; Maven Central later under `dart.art`.
- Its publish gate: a human-scored Host B voice/SFX recording; split modules pass API baselines; a
  consumer compiles from the staged ZIP alone; clean-clone gates pass; no keys, provider audio, MP4s or
  signing material in the release; Soong labelled external.
- Its release notes must exclude: streaming, production voice, deployed providers, a non-log action
  sink, passenger-display presentation, OEM Soong validation.

## Deferred decisions

- Media3 bridge: 7 public `car-media-common` signatures still use `android.support.v4.media` types
  (`MediaItemMetadata` ×3, `Media3Adapters.toPlaybackState` ×2, `PlaybackTiming.from`, `getStateCompat`).
  Removal is a major-version break, blocked on an out-of-repo consumer check. The flip's own 8-member
  break shipped: [`api-surface/MEDIA3-FLIP-BREAK.md`](api-surface/MEDIA3-FLIP-BREAK.md).
- `targetSdk` stays 36 until an API 37 automotive image exists (newest: `android-35-ext15`).
- Plugin adapters V3–V7 not written (~6,700 lines, untestable without a V3+ plugin). Negotiation stays
  V2; the fallback names the plugin's class. The deployed image's V8/V9 are published nowhere.
- `:car-apps-common-testutils` resolves lifecycle 2.2.0, the last pin (Dependabot now sees it).
- Version splits: `lifecycle-livedata` 2.6.2 (`car-apps-common`) vs 2.8.0 (`car-media-common`), both
  `api`; `mockito-core` 2.19.0 in car-ui-lib androidTest vs 5.24.0 elsewhere. No version catalog.
- `androidx.media` 1.6.0 on Host A's runtime classpath via `androidx.car.app` 1.7.0 (recorded exception).
- `CarUiContentListItem.mIsSecure` stays `false`: `setSecure()` is opt-in by design (`fc27816ff`); a
  `true` default was reverted after touches dropped intermittently for an unidentified reason.
  `CarUiContentListItemSecureTest` pins it.
- `MenuItemOEMV1.Builder` defaults `mIsRestricted = false`; only `MenuItemAdapterV1:62` overwrites it.
- Theming held back: 21 resources both overlayable and in `removed_resources.xml` (removal breaks OEM
  RROs); driver-facing copy in Java constants (`REFUSED_NOTE`, voice hint, "Confirm"); no
  `values-night` in car-ui-lib; seven IME text sizes in `dp`; `car_ui_color_accent` overlayable but valueless.
- Do not split `car-ui-lib` yet (all 14 packages form one cycle); extract the 19-file instrumented
  harness first. `org.gradle.parallel` is commented out.
- Soong left unedited: rotary `R` package, `lifecycle-extensions` in `car-apps-common/Android.bp:43`.
- Host A release builds validate against androidx's `hosts_allowlist_sample`; debug builds allow all hosts.
- Debug receivers are permissionless; their only gate is debuggable APK plus non-`user` build.
- No streaming parser (needs a transport); no wire-level conformance (no upstream message schema).
- `NO_DIALPAD` and `NO_TEXT_MESSAGE` are not enforced: no catalog component maps to them.
- `DemoRestrictions` global setters are deprecated but kept for binary compatibility; hosts ignore them.
- Host B's provenance/limits rule duplicates Host A's and its tests restate Host A's rule inline, so
  they would stay green if Host A changed. Fix: hoist the rule into `:a2ui-model`.

## Verified, unfixed

- `enableAndroidTestCoverage` in car-ui-lib and car-rotary-lib writes a 0-byte `coverage.ec`;
  `aaos-apps-gradle-project/build.gradle:94` wrongly calls it untouched. Diagnose before reporting.
- `UriUtils` subaction-dialog/account-image family (13 shipped `@hide` methods): bug fixed; deletion
  needs a usage check in `car-dialer-app` and `car-messenger-app`, outside this repo.
- UXR forgery residuals: the gate's inputs (`mDebuggable`, `Build.TYPE`) are unmutated; the plugin
  copy's `deliverForTesting` is package-private and ungated; `setFailClosedFactoryForTesting` still
  ships with default access (the surface checker sees only public and protected).
- `Contact.fromCursor` needs a 17-column projection declared nowhere: a missing column throws, a null
  MIMETYPE NPEs. Pinned as characterisation; only `Contact.java` of `car-telephony-common` is tested.
- `Conversation.fromBundle` silently drops malformed messages and reports success (pinned; shared API).
- `NotificationPayloadHandler.getMessages(Bundle)` passes a possibly-null notification to a `@NonNull`
  androidx call; untested (`mutation-exemptions.tsv` waits on it).
- `MessagingUtils.directSend` is untested; the `car-messenger-common` parent is in no ledger.
- `MediaItemsRepository.clearNodes()` leaves live data already handed out showing the old source
  (inherited; pinned).
- After reconnect the search query stays set and is not re-issued (pinned as a choice).
- Media3 bridge losses: a legacy session's `CONNECTING` reads as `NONE` (spinner stops, button offers
  play); `ACTION_SET_CAPTIONING_ENABLED` has no Media3 action.
- The NYC context snapshot (captured 2026-09-07) is past its own `liveRefreshRequiredAfter`
  (2026-09-10) and its event date; nothing checks expiry. Refresh or relabel before the next visit.

## Known failures

- `AlertDialogBuilderTest#test_setCursor_works`: the platform `AlertController` list is 177 px in a
  357 px dialog, so row two never inflates. Real, low severity; baselined.
- `CarUiRecyclerViewTest#testAlphaJumpToMiddleForThumbWhenTrackClicked[0]`, `[1]`: deterministic and
  unexplained; the tap lands on the measured track centre and the list does not move. Baselined.
- `PreferenceTest#testListPreference`: intermittent focus-establishment race after leaving touch
  mode. One failure → INDETERMINATE; two consecutive → regression.
- These 3 are what remains of 13 inherited car-ui-lib failures; IME (6) and duplicate search (4) were fixed.
- car-rotary-lib instrumented: 101 declared, 100 executed (`FocusParkingViewTest` 17/18); unexplained.
- `:car-media-common:createDebugUnitTestCoverageReport` sometimes fails "Unable to generate Jacoco
  report" in the combined run; it fails loud, never silently.

## Gate and measurement gaps

- The gate ignores `dump-failing-set.py`'s exit status, and its glob is not recursive.
- A deleted baselined test is reported as "baseline failure passed", not as an error.
- Stale `overlayable-baseline.txt` entries are not reported.
- Ledger rows recorded from a `-dirty` tree are accepted.
- `check-gate-metadata.py` runs in CI, not the gate; nothing asserts `api-lint-baseline.txt` stays empty.
- `car-uxr-flutter`'s JVM (`CarUxrPluginTest`) and Dart (`flutter test`) suites are in no ledger and not in CI.
- Surface baselines come from local debug AARs, not published release artifacts.
- `record-demo.sh` infers "the screen changed" from file size when `ffmpeg` is absent.
- No mutation coverage for `oem-apis`, `car-rotary-lib`, `car-assist-lib`, `car-telephony-common`
  (reasons and removal conditions in `docs/mutation-exemptions.tsv`).

## Not provable on this setup

- Passenger-display pixels: display 4 is virtual, owned by another uid, without `FLAG_PRESENTATION`.
- A rear-display handoff endpoint: emulator displays 2 and 3 are `FLAG_PRIVATE`.
- Seat → display mapping: the emulator declares one occupant zone.
- Real microphone transcription: no emulator mic input, and no image offers offline recognition.
- `SecureView`'s obscured-touch defence: its test needs a companion APK that Gradle cannot install.
- An OEM/AOSP Soong build; CTS (it tests the image, not this library).

## Live agent

Client foundation only: no deployed gateway, token issuer, provider adapters, production action sink
or retention policy. See [PROVIDERS](PROVIDERS.md).
