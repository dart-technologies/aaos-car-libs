# PRD: Modernization and Production with OEM Co-Design via Google Built-in

- Status: Draft for review
- Date: 2026-11-04
- Branch audited: `sprint/meta-met` @ `b40854f40` vs `main` @ `62f00d9b2`
  (merge-base = main HEAD; branch strictly ahead, tree clean). These SHAs predate the
  2026-11-05 restack of `main`; tag `muse2carui-submission-2026-11-04` still reaches them.
- Main commits in scope: all 11 Nov-4 commits on `main`
  (`62f00d9b2` … `809250ff6` — gateway baselines, OSS prep, Java 21 toolchain,
  docs, gates, Host A+B, five-provider gateway, A2UI core, Flutter binding,
  Media3 migration, standalone Gradle)
- Audit method: 9-agent review (git inventory, code correctness, tests/build,
  security/safety, hackathon readiness, prod/GAS research, critic, executed
  gate re-run, synthesis). Every executed gate re-ran green at HEAD.

## 1. Context

This repo is a fork of AOSP `car/libs` plus an A2UI agent-host platform:

- Host A (`:a2ui-host-carapp`, `art.dart.a2ui.carapp`): car-app templates host,
  requires GAS / `templates_host`, POI category, minCarApiLevel 7.
- Host B (`:a2ui-host-carui`, `art.dart.a2ui.carui`): car-ui-lib Views host,
  runs non-GAS (minSdk 28), driver + passenger activities.
- Stack: compileSdk 37 / targetSdk 36 / min 28–30, JDK 21, AGP 9.3.1,
  car-app 1.7.0 (newest stable).

Audit verdict: **conditional pass for demo-sprint scope; not prod-ready.**
The branch delta is 169 files, +14995/−268, and is demo-content heavy
(`run-nyc-demo.py`, render/fetch scripts, fixtures, local gateway).
The prod-hardening surface is essentially untouched.

Verified green at HEAD (re-executed, not just read):

- JVM: 13 suites / 872 tests, 0 fail/err/skip, BUILD SUCCESSFUL (406 tasks).
- API surface: all 12 published AARs match baselines; API lint clean.
- Instrumented: car-ui-lib 504/3 (baseline 3, no newly-failing) plus
  33+100+26 zero-fail; ledger 4 suites / 663 agree.
- Python: café 11/11, demo runner 22/22 incl. ffmpeg, gateway 39/39
  (one in-sandbox bind error proven environmental).
- CI checks: nyc-demo, demo-assets, license-headers (305 files + new S4(b)
  change-notice rule), demo-workflows, gate-metadata.

## 2. Goals

- G1: Ship Host A (GAS/templates) + Host B (non-GAS reach) as signed,
  observable, Play-accepted apps.
- G2: Modernize the stack to Kotlin + Hilt + coroutines/Flow; evaluate
  Compose-for-Auto for Host B Views surfaces (Host A stays templates —
  Compose does not replace the templates host).
- G3: Stand up the live-agent backend: deployed gateway, token issuer,
  provider adapters, production (non-log) action sink, retention policy.
- G4: Establish the OEM co-design path on Google built-in: plugin/theme
  co-design, V3–V7 adapters, V8/V9 publication, Soong/OEM-image validation.

## 3. Non-goals

- GAS image certification itself (OEM-owned, ~12–18 mo via xTS/CDD/OTA).
- New car-app categories beyond the POI-review outcome.
- A Flutter rewrite; streaming parser / wire-level conformance (no upstream
  schema exists yet).
- Rewriting this repo's history for publication (GitHub-first alpha stays a
  restack-into-new-repo runbook, per STATUS.md).

## 4. Users

- Drivers: distraction-optimized agent UI (parked vs. driving per display).
- Passengers: second-display video while the driver view is pruned.
- OEM integrators: plugin/theme adapters, Soong build, RRO theming.
- Demo/sales: one-command NYC scenario replay + video.

## 5. Requirements

### R1 — Distribution / car-app compliance

- R1.1: Add `res/xml/automotive_app_desc.xml`
  (`<automotiveApp><uses name="template"/></automotiveApp>`) — Host A
  currently has no desc file repo-wide, blocking Android Auto distribution.
- R1.2: Confirm category fit for an agent/concierge app: POI vs. NAVIGATION
  vs. parked-app Tier 3 path, before Play review.
- R1.3: Track car-app releases (1.7.0 stable in tree; 1.9.0-alpha02 latest
  pre-release); meet Tier 3 Car-ready / Tier 2 Car-optimized checklists.
- Acceptance: desc file present; Play pre-review checklist signed off.

### R2 — Release engineering

- Signing configs + keystore handling, versioning past 1/1.0, R8/minify/
  shrinkResources, targetSdk currency (36 now; API 37 held per STATUS.md
  until an automotive image exists), OTA/in-app-update story.
- Acceptance: signed release APKs from CI; versioning policy documented.

### R3 — Live-agent backend

- Deployed gateway, token issuer, provider adapters, non-log production
  action sink, retention + privacy policy with a named owner.
- Fixes the audit's top code risk along the way: overlapping `ask()` turns
  in `AgentLane.java:157-193` share one resettable processor (MEDIUM).
- Acceptance: end-to-end live turn against deployed gateway; no demo fixture
  in the prod path; the MEDIUM fixed with a regression test.

### R4 — Client modernization

- Kotlin adoption (today: 4 `.kt` files, Flutter example only; no Kotlin
  Gradle plugin; Java 17 sourceCompat), Hilt DI, coroutines/Flow.
- Production network stack replacing the INTERNET-less release assumption
  (release manifest must lack INTERNET per `A2uiAgentLaneTest`; only
  `src/debug` has a network-security-config today).
- Break up the god activity as new code lands.
- Fix the latent API <33 `registerReceiver(RECEIVER_EXPORTED)` overload
  issue (5 occurrences, pre-existing at `ba5e387e8`) or raise minSdk with
  OEM agreement.
- Acceptance: new client code in Kotlin with DI; release network config;
  minSdk decision recorded.

### R5 — Compliance / safety / security

- Productionize the hosts allowlist (today: release validates against
  `hosts_allowlist_sample`, debug allows all hosts); enforce
  `NO_DIALPAD` / `NO_TEXT_MESSAGE`; remove permissionless debug receivers
  from release (today gated only on debuggable-APK + non-`user` build).
- Penetration review of agent transport + `TrustedActionRegistry`;
  per-display driver-distraction conformance evidence (parked-gated video,
  end card, provider cycling, purchases, voice — all present in Host B
  and must be evidenced per display for GAS).
- Turn on GHAS/CodeQL; flip Scorecard `publish_results` with the public
  change; fix the `check-license-headers.py:29` S4(b) weakness (bare
  "Dart Technologies" line wrongly passes without a "Modified by" notice).
- Acceptance: security review signed; CodeQL + Scorecard publishing;
  distraction evidence pack per display.

Since the audit, the S4(b) weakness is fixed: a bare "Dart Technologies" line no longer counts
as a change notice.

### R6 — Quality / CI / test

- First real CI run; aggregate terminal `gate` job + Tier-2 branch
  protection; instrumented-in-CI or a device farm (today: JVM-only CI by
  design; instrumented needs the AAOS emulator + proxy plugin locally).
- Wire Python gateway/runner suites into automation (today manual-only;
  CI covers scenario data contracts, not runner/gateway behavior).
- Pull `car-uxr-flutter` JVM + Dart suites into ledger + CI; fix the 3
  baselined car-ui-lib failures, `FocusParkingView` 17/18, and the
  intermittent JaCoCo report failure.
- Theming: `values-night`, overlayable/removal conflicts (21 resources),
  dp→sp text sizes, Java-constant copy extraction.
- Hoist the Host A/B provenance rule into `:a2ui-model` (today Host B's
  tests restate Host A's rule inline and would stay green if Host A
  changed — STATUS.md).
- Acceptance: CI green with required gate job; zero baselined failures;
  night theme present.

### R7 — Observability

- Crash + analytics/telemetry SDK (none in the version catalog today),
  demo + prod dashboards, retention-aligned logging (current logging is
  metadata-only — keep that invariant).
- Acceptance: crash-free-rate dashboard live; PII review of new events.

### R8 — OEM co-design (Google built-in)

- Write V3–V7 plugin adapters (~6,700 lines; needs a V3+ plugin to test);
  publish/unblock V8/V9; Soong/OEM-image validation; theme co-design gates
  (RRO-safe overlayables).
- Acceptance: OEM pilot image builds via Soong; co-design theme signed off.

## 6. Milestones

- M1 Repo hygiene: first CI run green, aggregate gate + branch protection,
  desc xml, public/private decision executed, signed demo track.
- M2 Hardening: release pipeline (R2), observability (R7), security review
  (R5), baselined-failure cleanup (R6).
- M3 Backend live: deployed gateway + providers + sink + retention (R3).
- M4 OEM pilot: adapters + Soong + theme co-design (R8, R4 tail).
- M5 Play submission: tier checklists + distraction evidence (R1, R5).

## 7. Risks

- GAS timeline is OEM-owned (12–18 mo guidance); app-side evidence is the
  only lever we control.
- POI category rejection for an agent/concierge app → NAVIGATION or
  parked-Tier-3 fallback (R1.2 decides early).
- Templates-host fragmentation (list limit 100, allowlist variance).
- Non-GAS reach (Host B) vs. GAS feature parity drift.

## 8. Open questions

- O1: OEM partner-program engagement mechanics + design-review gates — no
  citable partner doc found; needs direct Google/OEM sourcing.
- O2: Tier 1 car-quality tier name/requirements — confirm on
  developer.android.com ("Car differentiated" unverified).
- O3: car-app 1.9 pre-release adoption timing.
- O4: Retention/privacy policy owner.
- O5: Device farm vs. emulator-in-CI.

## Appendix — audit evidence map

- Git: `git log --oneline --since="2026-11-04" --until="2026-11-05" main`
  (11 commits); `git diff --stat main...sprint/meta-met` (169 files).
- Gates re-executed at `b40854f40`: JVM 872, instrumented 504/3 + 159,
  API surface 12/12, Python 11/11 + 22/22 + 39/39.
- Correctness: 2 MEDIUM + ~25 LOW + 2 DEBT filed in review; car-*
  library diffs verified license-header-only.
- Security posture: 8 debug receivers gated on debuggable + non-`user`;
  INTERNET debug-only; cleartext=false; https-only gateway; env-only
  secrets; metadata-only logging; parked-gated video/end-card/voice.
- Unresolved from review: O1/O2 above; one uninspected crash-term grep
  hit in `CarUxRestrictionsUtil.java` (likely term collision; SDK
  absence verified via catalog + build files).
