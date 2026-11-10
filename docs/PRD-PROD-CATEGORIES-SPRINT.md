# PRD: Production Readiness Across All AAOS App Categories

- Status: Draft for review
- Date: 2026-11-10
- Branch audited: `main` @ `470311a`
- Related: [PRD-MODERNIZATION-PROD-OEM](PRD-MODERNIZATION-PROD-OEM.md) (R1–R8 still the backend/release spine),
  [STATUS](STATUS.md), [FORK](../FORK.md)
- Sources: [supported app categories](https://developer.android.com/training/cars),
  [car app library vs Android Automotive OS](https://developer.android.com/training/cars/apps),
  [template catalog](https://developer.android.com/design/ui/cars/guides/templates/overview),
  [car app quality tiers](https://developer.android.com/docs/quality-guidelines/car-app-quality),
  [AAOS platform requirements](https://developer.android.com/training/cars/platforms/automotive-os)

## 0. Scope amendment: AAOS only (2026-11-10)

Android Auto (projected) is out of scope for this sequence; no DHU work. Consequences: C5
dropped (Auto-only messaging notifications); C4/C7 acceptance is AAOS-only;
`automotive_app_desc.xml` is not built (a projected-mode construct) — C7 is the AAOS manifest
contract (`uses-feature android.hardware.type.automotive`, car-app categories, `minCarApiLevel`).
Template testing moves from DHU to the AAOS emulator. AAOS platform constraints now in scope:
AN-1 back affordance in Host B activities (no guaranteed system back), coarse-location-only
positioning, fixed-volume audio behavior.

## 1. Context

The modernization PRD took the fork from "conditional demo pass" to a hardened trunk: dependencies
current, 10 required CI checks (JVM, Python, lint, SBOM, zizmor, 5 CodeQL tier jobs), code-scanning
374 → 42 open, committed wrapper + SBOM per host, dependency verification in flight. What trunk still
is, product-wise: a POI demo (Host A renders List + Message templates, `minCarApiLevel 7`) and a
Views demo host (Host B) with media/video UI but no shippable category surface.

Google's car program gates distribution by app category and quality tier. Each driving category needs
its templates, its manifest declarations, and its quality checklist; parked categories need Views +
parked gating; every category needs `automotive_app_desc.xml` (still missing — R1.1). This PRD turns
that matrix into the next sprint sequence.

## 2. Goal and non-goals

Goal: both hosts shippable to production tracks with declared categories, all-templates coverage for
every all-tracks driving category (POI, navigation, IoT, weather), a media service, a parked video
surface, and Tier 2 (car-optimized) quality evidence per category.

Non-goals for this sequence: labs/testing-track surfaces (templated messaging, calling, browsers) —
tracked, not built; a games engine (Tier 3 car-ready compliance for games is out of scope); Tier 1
car-differentiated multi-display/cluster work (phase 2); OEM image integration (covered by R8).

## 3. Coverage matrix (current → target)

Category (tracks) | Host A templates today | Host B Views today | Target
---|---|---|---
POI (Auto+AAOS, all tracks) | List, Message; POI-declared | n/a | + Place List map, Search, Map+Content |
Navigation (Auto+AAOS, all tracks) | none | n/a | Navigation, Map, Route Preview; nav-declared |
IoT (Auto+AAOS, all tracks) | none | n/a | Grid, Pane, List; IoT-declared |
Weather (Auto+AAOS, all tracks) | none | n/a | List, Grid, Map+Content; weather-declared |
Media audio (Auto+AAOS, all tracks) | n/a (service, not templates) | browse/playback UI only | MediaLibraryService + session; EAP-ready |
Messaging notifications (Auto only) | n/a | n/a | Out of scope (AAOS-only amendment) |
Video parked (AAOS only) | n/a | VideoView/Media3 demo UI | Parked-gated video surface + manifest |
Templated messaging/calling (labs) | none | n/a | Not built; tracked |
Games parked | none | none | Not built |
Browsers labs (AAOS internal) | none | none | Not built |

Car-app library version in tree: 1.7.0 (stable; 1.9 alphas carry no template this plan needs —
recheck at implementation). Template hard limits (6 rows, 2 sections, 4 tabs, 5 actions, 4-step
flow cap) are already enforced in-tree by ItemCap/ContentLimiting; every new screen reuses them.

## 4. Category requirements

- C1 POI complete. PlaceListMapTemplate, SearchTemplate, Map+Content for Host A; keep List/Message.
  Acceptance: AAOS emulator walk of search → list → map → detail; 4-step flow cap proven by test.
- C2 Navigation. NavigationTemplate + MapTemplate + route preview; second `CarAppService` category
  (or category split per host, decided in O1); turn-by-turn data contract.
  Acceptance: AAOS emulator nav session, cluster-safe glanceability per the nav template rules.
- C3 IoT + Weather. Grid/Pane/List screens behind the IoT and weather categories; device/weather
  data via the gateway client (offline fixtures first, per R3 phasing).
  Acceptance: one controllable device type, one weather surface, both AAOS-emulator-verified.
- C4 Media service. `MediaLibraryService` + Media3 session in a host module; browse tree served
  from the existing Media3 ViewModel stack; manifest service declaration; EAP design pass so the
  templated-media track needs no rework.
  Acceptance: AAOS controller tests (play/pause/browse/search), media quality checklist.
- C5 Messaging notifications: DROPPED by the AAOS-only amendment (Auto-only category).
- C6 Parked video (AAOS). Parked-only gating on the existing Host B player (UXR Parked state),
  audio-while-driving policy per platform rules, video manifest declaration.
  Acceptance: driving transition pauses; parked checklist green.
- C7 AAOS manifests. `uses-feature android.hardware.type.automotive` for Play distribution,
  every car-app category declared, `minCarApiLevel` re-verified per template used (7 today;
  navigation/map needs drive the bump); no `automotive_app_desc.xml` (projected-only).
  Acceptance: manifest audit passes in CI (extend check-gate-metadata).

## 5. Cross-cutting prod requirements

- P1 Release engineering (R2 remainder). Signing configs, versioning past 1/1.0, R8/minify on both
  hosts, targetSdk-37 decision recorded. Acceptance: signed release APKs from CI.
- P2 Quality evidence per category. Tier 2 (car-optimized) checklists for POI/nav/IoT/weather/media
  as committed markdown + automated subset in CI where possible. Acceptance: one checklist per
  category, all boxes evidenced or waived with reason.
- P3 Crash/telemetry + ANR (R7). SDK wired in both hosts, dashboard linked from STATUS.
  Acceptance: staged rollout with live crash feed before any production track.
- P4 Security review (R5 remainder). Hosts allowlist productionized, NO_DIALPAD/NO_TEXT_MESSAGE
  enforced, debug receivers stripped from release, agent-transport pen review.
  Acceptance: review sign-off recorded; 42 open alerts revisited (target: warnings at fix-if-touching).
- P5 Backend contract (R3). Deployed gateway + token issuer + action sink + retention owner; the
  `AgentLane` overlapping-turns MEDIUM fixed. Acceptance: hosts run against staging with no demo stubs.
- P6 Kotlin/Hilt for new code (R4), JUnit 5 migration plan executed for JVM suites (vintage engine
  first, then rules-to-extensions), instrumented tier on schedule in CI.
  Acceptance: new category code in Kotlin; weekly emulator run green.

## 6. Sprint plan

Sprint 1 — Foundation + POI ship-shape: C7 (descriptions/manifests), C1 (POI templates), P1
(signing/version/R8), P3 SDK wiring. Exit: Host A installable as a signed POI app with full
template coverage; release APKs from CI.

Sprint 2 — Driving categories: C2 (navigation), C4 (media service), C3 (IoT + weather), P5
(staging backend). Exit: all all-tracks driving categories demoable on the AAOS emulator; media passes controller
tests on Auto + AAOS.

Sprint 3 — Parked + evidence: C6 (parked video), P2 (Tier 2 evidence per category), P4
(security sign-off), P6 (Kotlin/JUnit5/instrumented-schedule).
Exit: Tier 2 evidence complete; production-track submission packet ready.

Sequencing logic: manifests and signing first (everything else ships through them); all-tracks
driving categories before parked (widest distribution); labs never on the critical path.

## 7. Test strategy

- AAOS emulator per category screen (manual, scripted in the category checklist).
- JVM suites for template builders/limits (mapper-level, no device).
- Instrumented: existing gate extended per new screen; weekly scheduled CI tier (P6).
- Quality checklists double as release gates; unchecked boxes block the production track.

## 8. Risks

- Template availability vs head-unit car-app API levels: `minCarApiLevel` bumps shrink the
  addressable fleet; verify per template against the 1.7.0 API table before building.
- OEM plugin variance (V1/V2 reference only): new templates must render against both; V3+ per R8.
- Play category review is per-category and human; submit POI first (smallest surface) to calibrate.
- Media EAP track may demand UI rework if the service-first design drifts from templated patterns.

## 9. Open questions

- O1: one `CarAppService` with many categories, or split services per host? (Single service is the
  default; split if Play review or OEM behavior demands it.)
- O2 (resolves modernization-PRD O2): Tier 1 is "Car differentiated" per the quality guidelines —
  confirmed; phase 2 scope only.
- O3: video audio-while-driving policy for the Host B content set — product call before C6.
- O4: minimum fleet API level the business will accept (drives C7's `minCarApiLevel`).

## 10. Deliverables checklist

- [ ] C1–C4, C6–C7 implemented with AAOS emulator evidence
- [ ] P1–P6 closed with recorded sign-offs
- [ ] Tier 2 packets per category; submission order POI → media → nav → IoT/weather → video
- [ ] STATUS.md + registry updated; modernization PRD R1–R8 cross-checked and closed out
