# Security triage, November 2026

Point-in-time triage of the open code-scanning alerts on `main` (`ef1ac1157`),
enumerated via the API and verified against the tree. Verdicts: **fix** (queue a
PR), **dismiss** (with reason), **accept** (no action). Counts: 48 open alerts —
2 Scorecard, 3 CodeQL errors, 15 CodeQL warnings, 28 CodeQL notes.

## Supply chain (Scorecard)

### #454 Vulnerabilities: 36 OSV findings — sprint work, not a fire

All 36 hit pins in `aaos-apps-gradle-project/gradle/verification-metadata.xml`.
The shipped runtime (`debugRuntimeClasspath` of both host apps, which pulls the
whole library graph) resolves only two of these coordinates, both **above** the
vulnerable ranges: guava `33.8.0-android` (fixed in 33.7.2) and kotlin-stdlib
`2.2.10` (fixed in 1.6.0). Everything else is build-time only (Gradle / AGP 9.5.1
/ plugin classpaths): it affects the build machine, not the car.

| Pinned (vuln) | Fixed in | Reach |
|---|---|---|
| jackson-core/databind 2.15.0 | 2.18.8 | build-time (2.22.2 also pinned) |
| guava ≤33.7.1 (six pins) | 33.7.2 | resolved 33.8.0 ships clean; old pins stale or buildscript |
| wire-runtime 6.0.0 | 6.4.5 | build-time |
| jsoup 1.12.2 | 1.23.2 | build-time (biggest gap; find the parent plugin) |
| jose4j 0.9.5 / jdom2 2.0.6 / httpclient 4.5.6 / lang3 3.16.0 / bc 1.79 / okio 2.2.2 / stdlib 1.4.21 | 0.9.6 / 2.0.6.1 / 4.5.13 / 3.18.0 / 1.85 / 3.4.0 / 1.6.0 | build-time |
| kotlin-gradle-plugin 2.2.10 | 2.4.20-Beta1 | build-time; fix may be beta-only — verify before bumping |
| protobuf-java 3.25.5 | n/a | **false positive**: GHSA-jwvw-v7c5-m82h covers NuGet/composer/pip/Go, not Maven |

Fix order: (1) regenerate `verification-metadata.xml` from current resolution
and diff — several old pins (jackson 2.15.0, guava 33.3.1-android, okio 2.2.2,
httpclient 4.5.6) look stale and should drop out; (2) bump the parents (AGP,
Kotlin plugin, Gradle, whatever pulls jsoup 1.12.2 — identify via
`./gradlew buildEnvironment`); (3) add dependency constraints where a parent
allows; (4) document the unfixables (protobuf FP, kotlin-plugin beta). No
Dependabot alerts are open (Gradle is not covered), so this file is the tracker.

### #2 Code-Review — dismiss

Fails because no approving review is required. Already decided: deferred as solo
maintainer. Dismiss with that reason; re-open when a second reviewer exists.

## CodeQL errors — fix two, dismiss one

- **#77 `PostalAddress` equals without hashCode** (car-telephony-common/…/PostalAddress.java:74) — **fix**: add `hashCode()` consistent with `equals()` plus a unit test. Real contract violation; breaks hash collections.
- **#75 `RotaryCache` missing clone** (car-ui-lib/car-rotary-lib/…/RotaryCache.java:137) — **dismiss**: private static class, zero `.clone()` callers repo-wide. Dead-code rule on an unreachable path; do not diverge from AOSP for it.
- **#76 `PhoneCallLog.Record` compareTo/equals** (:51, warning) — **fix**: `Record.compareTo` uses only the timestamp while `equals` is identity. Add `equals`/`hashCode` on timestamp+type plus a test. Minor (only matters for sorted sets), same PR as #77.

## CodeQL warnings

- **#38/#37 SearchResultsProvider write ops unprotected** (car-ui-lib manifests + SearchResultsProvider.java:75,110,116) — **fix, the one real security issue**: exported provider with `readPermission` only. `insert()` appends caller data to the result list and `delete()` clears it, so any app on the head unit can spoof or wipe search results (in-memory only). Worse, `READ_SEARCH_RESULTS` is referenced but **declared nowhere**, so reads are same-UID-by-accident. Declare the permission (signature level), add a write permission, update both manifests, and extend `check-aaos-manifest.py` + its tests.
- **#74 `SearchController` null deref** (:189) — **fix carefully**: `setSearchMode(DISABLED)` as the first call leaves `mInflatedView` null and line 189 NPEs. Latent (OEM flow enables first). Minimal fix is an early return for that order; needs a test if the plugin module can host one.
- **#68 `TelecomUtils` lost stack trace** (:591) — **fix, one line**: `L.e(TAG, "markCallLogAsRead failed", e)` binds the `(tag, msg, args...)` overload, so the throwable is silently dropped. Call `L.e(TAG, e, "markCallLogAsRead failed")` instead. Only instance repo-wide (verified by grep).
- **#65 `A2uiHandoff` map exposure** (:111, :117, note) — **fix, trivial**: return unmodifiable views from `decisionsAtSource/Destination`. All current callers are tests (read-only), so it is safe; add an immutability assertion.
- **#469 `CarAppRenderer` null deref** (:270) — **dismiss (false positive)**: `field.getSubtitle()` on the empty-buttons branch is guarded by the throw at :238-241. The analyzer missed the correlation.
- **#73 `TelecomUtils` null deref** (:608) — **dismiss (false positive)**: `TextUtils.isEmpty` guards both `charAt(0)` calls; not modeled by the analyzer.
- **#72 test null deref** (:81) — **dismiss (false positive)**: Truth's `isNotNull()` guards line 81. Test code regardless.
- **#467/#466 implicit string concat in lists** (render-met-artwork-film.py:247, inventory-demo-screens.py:135) — **accept**: both are deliberate line-wraps of long literals (ffprobe args, HTML), functionally correct. Optional one-character `+` cleanup in the hygiene PR.
- **#159-157 rotary package-private** — **accept**: flagged lines do not resolve to a package-private method on current main (likely drifted); AOSP-parity files with working, tested behavior. No action unless a behavior bug appears.
- **#46/#45 int-vs-double comparison** — **accept**: behaviorally correct loops.
- **#78 `CarAppRenderer` unused param** (:128, note) — **expect auto-close**: the five-argument `render` uses every parameter on current main; the alert predates the fix.

## CodeQL notes — dismiss the interface params, hygiene-PR the rest

**Dismiss** (precedent: eight identical notes already dismissed): #470 lambda
param, #465/#25 import style in tests (or tidy), #134/#133/#132-129 interface
and callback params, #133 `isReadingTrustworthy`'s `override` (deliberately
ignored — "Only the source decides" — keep the param for call-site symmetry).

**Hygiene PR** (mechanical, CI-verified): #26 unused `re` import
(check-instrumented-run.py:29), #24 unused local, #468 (verify: the global
looks used — likely false positive), #14 mixed returns (server.py:59, drop the
`return`), #32 `allowBackup` in the Flutter sample manifest, deprecated
first-party calls in tests (#171-179, migrate or accept), #17/#15 empty-except
(accept: deliberate `KeyboardInterrupt`/`OSError` handling).

## Proposed PR slicing

1. **Hardening** (one PR): #77, #76, #65, #68, #38/#37, #74 — each with a test.
2. **Hygiene** (one PR): the mechanical Python/manifest/test cleanups.
3. **Dismissals** with the reasons above: #2, #75, #469, #73, #72, interface
   params, #159-157, #46/#45, #17/#15.
4. **Supply-chain sprint**: metadata regen + parent bumps + constraints + docs.
