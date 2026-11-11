# Architecture

An agent describes a surface; the vehicle decides what may be drawn and pressed; a host draws it.
Every safety decision lives once, in `:a2ui-model`. Hosts only acquire a reading and render.

```
agent → OEM gateway → agent client → A2uiMessageProcessor → policy → Host A | Host B → display
         (PROVIDERS)    ↑ TrustedActionRegistry                 ↑ CarUxRestrictionsUtil (per display)
```

## Model (`:a2ui-model`, `art.dart.a2ui`)

| Package | Role |
|---|---|
| `protocol` | A2UI v0.9 + v1.0 `createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`, routed by message version. v1.0 adds the `@` envelope mixins, null-deletes, and the extended `createSurface` (optional catalog, inline components/data model). Batches apply atomically; surfaces deep-copy on ingest and return immutable snapshots; depth ≤64, ≤1,000 nodes, no cycles, diamonds or duplicate children |
| `model` | `A2uiComponent`, `ComponentType`: the 18-type basic catalog — Text Button Card Tabs TextField CheckBox ChoicePicker Slider DateTimeInput Image Icon Divider Video AudioPlayer Row Column List Modal |
| `policy` | Projection, value limits, action policy, visible tree, handoff |
| `agent` | Provider-neutral gateway client |
| `demo` | `UseCaseCorpus` (10 cases, all 18 types), `DemoRestrictions` |

`a2ui-automotive/conformance/automotive.yaml` states each restriction level as an A2UI v1.0 prune
case; `A2uiCatalogContractTest` pins `A2uiRestrictionPolicy` to it component for component.

## Policy pipeline

| # | Stage | Code | Rule |
|---|---|---|---|
| 0 | Acquire | host | Reading and `RestrictionsSource` for the host's own display |
| 1 | Gate | host call site | Only `DELIVERED` unlocks; otherwise fail-closed bits **and** limits |
| 2 | Prune | `A2uiRestrictionPolicy`, `A2uiPruner.prune` | Each bit removes what it forbids (intersection). `FULLY_RESTRICTED` (0x1ff) leaves Text Icon Row Column Divider Button. A pruned component takes its subtree |
| 3 | Limit | `A2uiValueLimits`, `A2uiPruner.applyLimits` | String length, cumulative items, depth from the live reading; a set bit reporting 0 gets the fork's fallback 60/6/2. After prune; truncates, drops or cuts, never flattens |
| 4 | Fit | host | Host A: templates-host list limit (100), over-limit templates rejected. Host B: vehicle `maxCumulativeContentItems` via car-ui-lib `ItemCap` (automatic) or `ContentLimiting` (opt-in, needs `start()`, disables `ItemCap`) |
| 5 | Offer | `A2uiActionPolicy.decide` via `A2uiActionBinder` | Table below; a refused control gets no listener and shows `REFUSED_NOTE` |
| 6 | Invoke | `A2uiMessageProcessor.dispatchAction`, `confirmAction` | The sink runs only on ALLOW; CONFIRM is re-decided when answered |

Action classes, by consequence: `SAFE < NAVIGATION < TEXT_ENTRY < CONSEQUENTIAL < DESTRUCTIVE`.

| Reading | SAFE, NAVIGATION | TEXT_ENTRY | CONSEQUENTIAL, DESTRUCTIVE |
|---|---|---|---|
| untrusted (decided as `FULLY_RESTRICTED`) | ALLOW | REFUSE | REFUSE |
| trusted `BASELINE` | ALLOW | ALLOW | CONFIRM |
| trusted, restricted | ALLOW | REFUSE iff `NO_KEYBOARD` | REFUSE (a dialog at speed doubles the glance) |

- **Classification**: the component must own the dispatched action; declared classes may raise, never
  lower. Recorded demos use a name table (unknown → CONSEQUENTIAL); live sessions use
  `TrustedActionRegistry` (host-owned exact ids; unknown → DESTRUCTIVE, never dispatched).
- **Voice**: `dispatchByVoice` resolves against `A2uiVisibleTree` (what the host actually drew), read
  when the phrase arrives, then calls the same `decide` as touch; `NO_MATCH` and `AMBIGUOUS` run
  nothing. Listening needs `allowsListening` (transcription bit and provenance). Both hosts bind the
  platform `SpeechRecognizer`; every image surveyed (AAOS 35 Google APIs and Play Store, Harman
  IGNITE) ships a recogniser, none offline. Transcripts are not logged.
- **Re-decision**: across time, `confirmAction` re-reads at answer time. Across displays,
  `A2uiHandoff.reproject` projects retained protocol state per `Endpoint` and decides per side, and
  `A2uiDisplayBinder` authorises a touch by the display it is drawn on (pruned there: no decision).

## Hosts

| | Host A `a2ui-host-carapp` | Host B `a2ui-host-carui` |
|---|---|---|
| Renders with | `androidx.car.app` 1.7.0; Google templates host, out of process | `car-ui-lib` views from the OEM plugin, in process |
| Display read | templates host's private virtual display (7, 8, …): no UXR config, so `0x1ff` | display 0, the real driving state |
| Content limit | template property, host-enforced | vehicle property, app-enforced |
| Confirmation | `A2uiConfirmScreen` (`MessageTemplate`) | car-ui-lib `AlertDialogBuilder` |
| Minimum | SDK 30, Car API 7 (negotiated 8 on the GAS host) | SDK 28 |
| Role | Portability proof (Android Auto and AAOS) | Primary demo: console, native search/tabs/media, handoff |

Per host: stages 0 and 4, the stage-1 call site, the confirmation view; the rest is shared. The
templates host links car-ui-lib and is themed via its `car-ui-lib` overlayable, so Host A renders
through it too. The debug bus (`SET`, `LISTEN`, `CASE`, `SPEAK`, `HANDOFF`; Host B console) exists
only in debuggable APKs on non-`user` builds; synthetic readings carry `DEMO`.

## UX restrictions fail closed

`CarUxRestrictionsUtil` (car-ui-lib; a second copy in the reference plugin; read by
`car-uxr-client-lib`, the `Uxr*` widgets, both hosts, and Dart via `car-uxr-flutter`):

- Before car service delivers, and after it is lost: `FULLY_RESTRICTED`, distraction optimisation
  required (upstream reported BASELINE on `SecurityException`).
- `RestrictionsSource`: `NEVER_DELIVERED`, `DELIVERED`, `DEMO`, `DISCONNECTED`. Only `DELIVERED` is a
  measurement; `RestrictionsSnapshot` reads bits and source atomically.
- One instance per display (`getInstance(Context)`, non-visual contexts → default display;
  `getInstanceForDisplay`; `disconnect()`; evicted on display removal).
- Seeds on register (the platform listener is change-only); delivery commits under a lock; observers
  are notified outside it; UI consumers post to the main thread.
- Composites: `hasAllRestrictions`, i.e. `(active & 0x1ff) == 0x1ff`, never `!= 0` or `==`.
- `setUxRestrictions`, the only non-car-service route to `DELIVERED`, works only in debuggable apps
  on non-`user` builds; synthetic state goes through `setDemoUxRestrictions` as `DEMO`.

Seven fail-open shapes were found here, each invisible from the layer above: (1) error treated as
permission; (2) absence indistinguishable from a benign reading; (3) no event mistaken for no change;
(4) a correction emitted before the pipeline that carries it; (5) partial success reported as
success; (6) a secure default downgraded at bind time; (7) "0 failures" read as coverage.
Counter-example: `CarPackageManagerUtils.isDistractionOptimized` defaults to `false`, so a missing
manager restricts. Rules: phrase safety predicates as grants; keep provenance apart from the bits; a
permissive default that one call site happens to overwrite is *cleared-conditionally*, not safe.

## Per-display

Car service holds independent states per display (observed: display 0 `UxR 0`, cluster display 3
`UxR 511`). `CarUxRestrictionsPerDisplayTest` proves distinct, isolated instances on two reachable
displays and fails rather than skips with fewer (unless `-e allowSingleDisplay true`). Emulator
`-multidisplay` displays are `FLAG_PRIVATE` (apps see 0 and 4); display 4 is virtual and refuses app
windows, so handoff enforcement is verified and destination pixels are not. Seat → display mapping
(`CarOccupantZoneManager`) is unproven: the emulator declares one driver zone.

## Agent client boundary

The vehicle speaks one provider-neutral contract, `a2ui.gateway.v1`
(`a2ui-automotive/agent-gateway-v1.schema.json`), to one OEM-owned HTTPS gateway. Vendor keys, model
choice and vendor-specific conversion live behind the gateway, never in an APK. The client
(`art.dart.a2ui.agent`) binds each response to provider, session and turn, applies only the newest
generation, decodes within hard size and depth bounds, and cannot run until the host installs its
`TrustedActionRegistry`. Agent text, labels included, never grants authority: current UXR and the
host registry decide. Providers, profiles and the dev gateway: [PROVIDERS](PROVIDERS.md).

## Theming

RROs target packages and car-ui-lib is a static library, so Host B's A2UI content takes the OEM
plugin's views; app chrome is overlayable per host (`A2uiHostCarApp`, `A2uiHostCarUi`). car-ui-lib's
604-item overlayable ships under Soong only (Gradle excludes `res-overlayable`: resource collision).
