# Verification

`BUILD SUCCESSFUL` is not evidence here: upstream sets `ignoreFailures` and a crashed instrumentation
still reports success. Every check counts what ran and exits 0 pass, 1 fail, 2 could not determine
(never a pass). Read the exit code, not the last lines of output.

## Environment

| | |
|---|---|
| JDK / SDK | 21 / `platforms;android-37.0` |
| AVD | `AAOS_35_1408p_GoogleAPIs` (Play Store image is compatibility-only); `AVD=` overrides |
| Device state | `scripts/ensure-test-env.sh --fix`: boots `-writable-system`, replaces the image's stale car-ui-lib proxy plugin with this repo's `:plugin` (byte-checked), en-US for the foreground user, animations off. Lost on emulator restart; `ensure-test-env.sh` alone verifies |

With the image's own plugin, car-ui-lib instrumented crashes at 227/497 (`AbstractMethodError`) and
failures inflate, with no error anywhere.

## The gate

`scripts/run-bump-gate.sh [--no-env] <label> [group:name=version …]`

| Step | Asserts |
|---|---|
| 0 | Each given coordinate *resolves* to that version everywhere, or the row is in `resolution-exceptions.tsv`; a bump below the transitive floor changes nothing |
| 1 | `-PwithFlutter assembleDebug`; both hosts `assembleRelease` (lint-vital); build coverage; Soong parity; screenshot evidence; demo assets; NYC scenarios; Claude tester `--dry-run`; cited tests; overlayables packaged |
| 1b | Published AAR surfaces unchanged or re-recorded; API lint on an empty baseline |
| 2 | JVM suites from `scripts/gate-modules.tsv` (`--rerun-tasks`), exact counts from `gate-counts.tsv`, 0 failures, 0 skipped; the module set may not shrink against `HEAD` |
| 2b | Per-module coverage status and covered lines against `coverage-baseline.tsv` |
| 3-pre | `ensure-test-env.sh --fix --strict-env`; takes over the emulator (use `--no-env` on hardware) |
| 3 | Exactly one device. car-ui-lib instrumented: count from `instrumented-counts.tsv`; failing set diffed against `baseline-failset.txt` by test and exception type. Newly failing → FAIL, unless all are in `intermittent-tests.txt` → INDETERMINATE, rerun; two consecutive is a regression |
| 4 | `car-media-common`, `car-rotary-lib`, `car-telephony-common` instrumented: count from the ledger, 0 failures |

car-ui-lib's instrumented suite dominates the run (~9 min, ±25% between runs); sharding needs a
second device. Ledgers are re-recorded only from green runs (`record-gate-counts.py --record`,
`record-instrumented-counts.py --record`, `check-coverage.py --record`; a coverage loss needs
`--allow-loss "reason"`).

## Checkers

| `scripts/…` | Guards | Runs in |
|---|---|---|
| `check-a2ui-conformance.py` | `automotive.yaml` and the gateway schema validate against both pinned upstream schemas (shape only) | CI |
| `check-api-lint.py` | Metalava API lint on `:a2ui-model`, both hosts, `:car-uxr-flutter`; empty baseline | gate, CI |
| `check-api-surface.py` | Public/protected surface and annotations of each published AAR vs `docs/api-surface/` | gate, CI |
| `check-build-coverage.py` | Every `.java` under a configured source root is compiled by some module | gate, CI |
| `check-cited-tests.py` | Main-source comments cite only `*Test` classes that exist | gate |
| `check-coverage.py` | JaCoCo per module: MEASURED/BLIND/EMPTY status and covered lines never drop silently | gate, CI |
| `check-demo-assets.py` | Committed Host B demo media match pinned SHA-256 | gate, CI |
| `check-demo-workflows.py` | `record-demo.sh` cases == `UseCaseCorpus` ids, both ways | CI |
| `check-gate-metadata.py` | Ledgers well formed; CI and gate agree; intermittent entries carry evidence; no test in both lists; mutation anchors resolve | CI |
| `check-gate-sensitivity.py` | Each registered mutation (≥125) turns its named test red; each gated module has a mutation or an exemption | weekly, before release |
| `check-instrumented-run.py` | Executed ≥ expected; failures and skips within ceiling | gate, CI |
| `check-license-headers.py` | Added files carry Apache headers; no header lost from a modified file | CI |
| `check-nyc-demo.py` | Scenario timing (60–90 s), action vocabulary, corpus mapping, 10k-credit ceiling; `--require-audio` | gate, CI |
| `check-overlayable-packaged.py` | Each `<overlayable>` reaches its own module's artifact | gate, CI |
| `check-screenshot-evidence.py` | `docs/screenshots/`: PNG structure, inventory, sidecar checksum and provenance | gate, CI |
| `check-soong-parity.py` | `Android.bp` paths resolve; overlayable dirs match Gradle; vendored plugin-apis SHA-256 | gate |
| `check-test-reachability.py` | Fork methods no test names (advisory) | manual |
| `check-workflow-pins.py` | Every Action pinned to a commit SHA | CI |

## Ledgers

Stale entries fail in `resolution-exceptions.tsv` (for a gated coordinate), `mutation-exemptions.tsv`,
`soong-parity-baseline.txt` and both `scripts/` baselines; not in `overlayable-baseline.txt` or
`baseline-failset.txt` (a baselined test that passes is only reported).

| File | Holds | Written by | Read by |
|---|---|---|---|
| `scripts/gate-modules.tsv` | The gated JVM modules | hand | gate 2/2b, CI, `record-gate-counts.py` |
| `docs/gate-counts.tsv` | JVM test count per module | `record-gate-counts.py` | gate 2, CI, `check-gate-metadata.py` |
| `docs/instrumented-counts.tsv` | Instrumented count and failure ceiling | `record-instrumented-counts.py` | gate 3–4 |
| `docs/coverage-baseline.tsv` | Coverage status and lines per module | `check-coverage.py` | gate 2b, CI |
| `docs/baseline-failset.txt` | Known car-ui-lib failures with exception type, sorted | hand | gate 3, `check-gate-metadata.py` |
| `docs/intermittent-tests.txt` | Known flakes, with evidence | hand | gate 3, `check-gate-metadata.py` |
| `docs/resolution-exceptions.tsv` | Allowed version deviations, with reasons | hand | gate 0, `check-gate-metadata.py` |
| `docs/mutation-exemptions.tsv` | Gated modules without a mutation, with removal condition | hand | `check-gate-sensitivity.py` |
| `docs/overlayable-baseline.txt` | Unpackaged overlayables, with reason | hand | `check-overlayable-packaged.py` |
| `docs/soong-parity-baseline.txt` | Known Soong/Gradle drift | hand | `check-soong-parity.py` |
| `docs/api-lint-baseline.txt` | Deliberately empty | — | `check-api-lint.py` |
| `docs/api-surface/*.txt` | Published surface per AAR (`MEDIA3-FLIP-BREAK.md`: the recorded break) | `check-api-surface.py --record` | `check-api-surface.py` |
| `scripts/build-coverage-baseline.txt` | Sources knowingly compiled by no module | hand | `check-build-coverage.py` |
| `scripts/license-header-baseline.txt` | Headerless added files, with reason | hand | `check-license-headers.py` |

## CI and its limits

`.github/workflows/ci.yml` (push and PR to `main`): **Build & JVM tests** (assemble, gated JVM suites,
coverage, executed counts, API surface, API lint, overlayables, build coverage) and **Hygiene**
(actionlint, Action pins, conformance, demo workflows, screenshots, demo assets, NYC scenarios,
licence headers, gate metadata). `sensitivity.yml` runs the mutation sweep weekly and on dispatch, so a
change that blinds the gate can hide up to a week: run it before any release. `codeql.yml` and
`scorecard.yml` are committed; Dependabot covers Actions and Gradle but ignores `car-ui-lib-plugin-apis`
(an ABI decision).

CI cannot run the instrumented suites (they need a writable-system AAOS image carrying this plugin),
so not the plugin boundary, live UXR path, per-display behaviour or recordings either. It does not
run, but could: Soong parity, cited tests, the Claude tester dry-run. Nothing here establishes an OEM
Soong build, passenger-display pixels, real microphone recognition, seat → display mapping, CTS (it
tests the image, not this library), or that release AARs match the local debug AARs behind the baselines.

## Blind spots and seams

Suites observe what a test can **set** and are blind to what it must **stage**: vehicle state, an
obscuring window, a loaded plugin. Add a seam that makes the input settable. car-ui-lib's
instrumented suite cannot see `SecureView`'s obscured-touch filter (upstream's overlay test needs a
second APK that Gradle cannot install; deleted in `627a58b5a`) or `MenuItemAdapterV1.setRestricted`.
For a dependency bump the acceptance test is that sensitivity does not change: every mutation stays
detected, and the bump's unit is the resolved graph, not the edited declaration.

**Plugin ABI.** `OemApiSurfaceTest` pins members of `car-ui-lib-plugin-apis:1.5.0`. Before editing
it, diff the artifacts with synthetic members filtered:

```bash
for v in 1.5.0 NEW; do unzip -p car-ui-lib-plugin-apis-$v.aar classes.jar > $v.jar
  javap -p -cp $v.jar $(unzip -Z1 $v.jar '*.class' | sed 's/\.class$//; s|/|.|g') \
    | grep -vE 'access\$|\$\$Nest|-IA' > $v.txt; done; diff 1.5.0.txt NEW.txt
```
