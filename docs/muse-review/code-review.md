# Muse Code review of sprint/meta-met (2026-11-04)

Muse Code 1.4.14, `muse exec --json`, muse-spark-1.3, reasoning effort xhigh, read-only detached worktree of
origin/sprint/meta-met at b40854f40. 27 model calls. Prompt: `code-review-prompt.md` beside this file.

## Part 1 — findings

**1. Medium — realtime worker crash skips the cached fallback.** [core.py](tools/a2ui-gateway/a2ui_gateway/core.py:289) catches only `TimeoutError` then `(TimeoutError, AdapterError, ContractError)` around `future.result()`. Any other exception from the worker thread (a validation-dependency bug, a `TypeError`) propagates to `handle()`'s blanket `except` and returns `internal` 500 even when a good cached output exists. Observed in this session: with `jsonschema` uninstalled every realtime call went `internal` instead of `fallback`. Fix: catch `Exception` around both `future.result()` calls and serve `cached()` as `fallback` with `live_error`, keeping `internal` for the no-cache case.

**2. Medium — Host B safety-parity tests restate the rule instead of calling it.** [A2uiCarUiActivity.java](a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java:2905): *"duplicated so :a2ui-model needs no car-ui-lib type… it would not notice Host A changing"* (same caveat at [2921](a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java:2921)). Scenario: Host A tightens a restriction mapping; Host B's copy stays green while the two displays enforce different rules. Fix: move the provenance rule into `:a2ui-model` on JVM-only types (source enum + int mask) so both hosts call one function, or generate both from `automotive.yaml` like `A2uiCatalogContractTest` does.

**3. Low — STT gate normalizes differently than its writer.** [run-nyc-demo.py](scripts/run-nyc-demo.py:138) checks `data.get("transcriptNormalized") != expected.casefold().strip()`, but [prepare-elevenlabs-demo-audio.py](scripts/prepare-elevenlabs-demo-audio.py:96) writes `normalized_transcript()` (`re.findall(r"[\w']+")`, punctuation stripped). Today's guest lines have no punctuation so they agree; the first future guest line with a comma fails the gate ("no longer matches") despite a correct STT fixture. Fix: import and reuse `normalized_transcript` in the check.

**4. Low — scoring/narration parse model JSON without fence handling.** [muse-curate-coffee.py](scripts/muse-curate-coffee.py:290) and [muse-audio-guide.py](scripts/muse-audio-guide.py:134) call raw `json.loads(...)` on the structured answer, while the gateway's own [adapters.py](tools/a2ui-gateway/a2ui_gateway/adapters.py:344) provides `_structured()`/`_first_object()` exactly for fenced or prose-wrapped replies. Scenario: one fenced scoring answer aborts the script with a traceback instead of a retry. Fix: route both through `adapters._structured()`.

**5. Low — gateway config test fails on any ambient credential, and failure diffs echo it.** [test_gateway.py](tools/a2ui-gateway/test_gateway.py:140) uses `mock.patch.dict("os.environ", {...})` without `clear=True`, so a machine-exported `META_API_KEY` leaks into `load_env` and the equality assert fails (reproduced here). The assert diff then prints credential values to the log. Fix: patch with `clear=True` plus the needed vars, and assert subset/redact values on mismatch.

**Checked and cleared:** server logs carry provider/profile/status only ([server.py](tools/a2ui-gateway/a2ui_gateway/server.py:84)); adapter errors are `code:token` with no prompt/body ([adapters.py](tools/a2ui-gateway/a2ui_gateway/adapters.py:81)); tunnel script copies only the CA cert ([demo-gateway-tunnel.sh](scripts/demo-gateway-tunnel.sh:43)); parked-only confirm re-decision, end-card gate ([A2uiCarUiActivity.java](a2ui-host-carui/src/main/java/art/dart/a2ui/carui/A2uiCarUiActivity.java:2786)), DEMO-vs-DELIVERED provenance, and `CoffeeFixture` fail-closed parsing all read correctly; the committed brief's cache key recomputes exactly (`match: True`); "compared 15 cafés" matches 15 scorable cafés; manifest `nyc_met` is 132 s as docs say.

## Part 2 — gaps to A+

| Criterion | What costs points in 2 min | Concrete fix |
|---|---|---|
| Demo 25% | `README.md`: "Demo video: (link to come)" — an empty player slot reads as no demo | Upload tonight, replace with `Demo video: [nyc_met, 132 s](URL)` plus one dual-display still under it |
| Use of Meta 25% | Muse's work is buried in `fixtures/`; nothing shows *why two calls* or the cited-evidence pipeline | Add 4-line block to README: "Brief mode = web_search call → cited prose → structured call (annotations are lost when combined, verified 2026-11-04)" linking `tools/a2ui-gateway/a2ui_gateway/adapters.py` |
| Technical 20% | `compare-providers.py` exists but no rendered output is checked in, so "model-agnostic" is a claim | Check in `docs/SCOREBOARD.md` (one cached run: model, ms, source per provider) and link it from `docs/PROVIDERS.md` |
| Impact 30% | Fork framing: the AAOS module table dominates the README; the safety thesis ("Any model can design it. The car decides.") appears only at the demo's end card | Move that sentence to the README header paragraph verbatim; cut the module table below the demo section |

Also: `docs/DEMO.md` should state keyless reproducibility in one line — "All three gate checks replay committed fixtures with no keys" — since judges won't run anything.

## Part 3 — enhancement opportunities

1. **Muse checks its own citations against `doNotClaim`** (~1 h, tonight). New `scripts/check-muse-claims.py`: every evidence sentence in `BRIEF.json`/`COFFEE.json` must resolve to a cited URL; every price/hour must carry a source. Judge sees: a green gate plus one "citation coverage: 100%" line in README. Files: new script + `docs/DEMO.md`.
2. **Publish the provider scoreboard on the same Met case** (~1 h, tonight if cached outputs exist, else later). `scripts/compare-providers.py` → `docs/SCOREBOARD.md` with latency and refusal columns; Muse's source-rows vs others' unsourced prose is the honest differentiator. Files: `scripts/compare-providers.py`, new doc.
3. **Effort-tier latency table** (~30 min, tonight). `providers.json` already runs `minimal` vs `high`; paste the measured 6–9 s vs ~15 s numbers into `docs/PROVIDERS.md`. Shows deliberate use of reasoning-effort tiers.
4. **Multilingual voice transcribe** (~1–2 h, tonight). `transcribe_with_muse` already parameterizes `languageBias`; add one Spanish guest clip fixture and show the transcript in `docs/DEMO.md`. Files: `scripts/prepare-elevenlabs-demo-audio.py`, manifest, DEMO.md.
5. **Vision on a recorded frame** (later). Muse vision review of the demo video exists only as prose in `docs/muse-review/`; a scripted pass (`scripts/check-demo-frames.py`) asserting "no confirmation dialog visible while driving" in sampled frames would be novel. Needs API time tonight — defer.
6. **Tool/function calling for agent actions** (later). Gateway currently emits static action vocabularies; a `navigate`/`reserve` function-call round-trip through the gateway would deepen the agentic story but needs host + schema work.
7. **Nightly Muse Code headless review** (later). `docs/muse-code/` prompts exist; wiring one as a scheduled `--stt`-style gate adds process, not demo impact, and risks night-before churn.

Net: do 1–3 tonight (≈2 h, all visible in README/docs without touching safety code); 4 if audio time remains; 5–7 after the deadline.