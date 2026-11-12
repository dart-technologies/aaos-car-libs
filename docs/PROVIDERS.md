# Providers: Claude, ChatGPT, Gemini, Grok, Muse

The vehicle speaks one contract, [`a2ui.gateway.v1`](../a2ui-automotive/agent-gateway-v1.schema.json),
to one HTTPS gateway. `AgentProvider` names the vendor; model ids, keys and conversion stay in the
gateway. No APK carries a vendor key.

| Piece | Where |
|---|---|
| Vehicle client: bounded decoder, HTTPS-only transport, turn generations, trusted actions | `a2ui-model/…/agent/` |
| Dev gateway: `POST /v1`, four adapters, cache, TLS on `127.0.0.1:8787` | `tools/a2ui-gateway/` |
| Model id per provider and execution profile, vendor-fallback policy | `tools/a2ui-gateway/providers.json` |
| Every corpus case × provider × profile, verified on the JVM | `scripts/compare-providers.py` |
| Host B debug lane: provider chip, `AGENT` broadcast, badge | `AgentLane`, `A2uiCarUiActivity` |

## Models

| Selector | API adapter | `quality_cached` | `realtime_low_latency` | Key | Subscription CLI (when the key is empty) |
|---|---|---|---|---|---|
| `claude` | Anthropic Messages | `claude-opus-5-5`, high | `claude-haiku-5-5`, low | `ANTHROPIC_API_KEY` | `claude -p`, same models |
| `chatgpt` | OpenAI Responses | `gpt-6.1-terra`, high | `gpt-6-luna`, low | `OPENAI_API_KEY` | `codex exec`, same models |
| `gemini` | `generateContent` + response schema | `gemini-4-argon`, high | `gemini-3.8-flash`, low | `GEMINI_API_KEY` or `GOOGLE_API_KEY` | `agy -p`, `gemini-3.8-flash` low (thinking returns empty) |
| `grok` | OpenAI-compatible chat | `grok-4.7` | `grok-4.20-0309-non-reasoning` | `XAI_API_KEY` | `grok -p`, `grok-4.7` / `grok-4.7-build-fast` |
| `muse` | OpenAI-compatible chat, `https://api.meta.ai/v1`; Responses for the brief | `muse-spark-1.3`, high, cited brief | `muse-spark-1.3`, minimal | `META_API_KEY` | — |

Realtime waits 10 s for a live answer, then serves the cached quality answer as a labelled
fallback. CLI rows run with tools, plugins and sessions off in an empty directory; their latency
includes process start-up.
OpenAI (`gpt-6.1-terra` via Codex only), Google, xAI and Meta ids were read from their model pages on
2026-11-03 (`verifiedAt`);
Claude's come from the claude-api model table. Meta lists no faster Spark tier, so Muse's profiles
differ only by effort; `minimal` is the lowest Spark accepts (`none` is a 400), and `low` took
~15 s median in an earlier three-prompt check. Measured `minimal` latency is below.
The gateway reads only these key names and `A2UI_GATEWAY_TOKEN`.
Muse's brief (`modes.brief`): `web_search` near New York; ≤3 cited pages become `Source · host` rows.

### Muse latency, measured 2026-11-04

`compare-providers.py --providers muse` from the dev worker at b40854f40 (the sprint branch,
before the restack): its 12 corpus cases, `muse-spark-1.3`, a fresh cache per run so every call
was live and no fallback could answer.
Gateway turn time (vendor call plus validation); percentiles nearest-rank, as the script computes
them.

| Profile | Effort | Calls | p50 | p95 | Max | Over 10 s | Host-valid |
|---|---|---|---|---|---|---|---|
| `realtime_low_latency` | `minimal` | 36 (3 runs × 12) | 8.6 s | 15.3 s | 18.5 s | **7 of 36** | 35/36 |
| `quality_cached`, no web search | `high` | 12 | 32.8 s | 41.7 s | 41.7 s | 12 of 12 (no deadline) | 12/12 |

Muse misses the 10 s realtime deadline on about one turn in five. Those turns get the cached answer
as a labelled fallback, or wait up to 30 s when nothing is cached; none timed out. Per run, p50 was
8.9, 8.6 and 8.1 s and p95 11.7, 15.3 and 18.5 s. The one host-invalid answer, `modal_confirmation`
at 18.5 s, failed Host B's projection (`project.integrity`). The quality row omits the brief's web
search (the comparison drops modes).

## What the gateway enforces

- One system prompt and one portable generation schema for every vendor. The gateway, not the model,
  writes the lifecycle: `createSurface` then `updateComponents`, v0.9, `automotive`, the request's id.
- Nothing is returned or cached before it passes the gateway schema and `BoundedA2uiJsonDecoder`'s
  limits. Anything else is a `gatewayError`: non-2xx, token detail only.
- Cache: `build/a2ui-gateway-cache/<provider>/<profile>/<sha256(model, prompt)>.json`; an output
  replays only for the model that made it. `quality_cached` replays exactly; `realtime_low_latency`
  calls live and, past the 10 s deadline or on a vendor error, serves its own earlier output or the
  quality profile's. Each `surfaceResponse` says which (`source`: `live`, `cache`, `fallback`), and headers
  name the output's model, its profile and the live error.
- `policy.vendorFallback` is false: Anthropic alone offers a server-side model fallback, which would
  credit Claude with another model's output.
- Logs carry provider, profile, status, source, models and latency; never a key, prompt or body.

## Run

```bash
python3 -m pip install --require-hashes -r tools/a2ui-gateway/requirements.txt
cp .env.example .env && chmod 600 .env     # keys for the providers in use
tools/a2ui-gateway/gen-dev-ca.sh             # gitignored: certs/ and Host B's debug raw CA
python3 tools/a2ui-gateway/serve.py          # --offline serves the cache and never calls a vendor
adb reverse tcp:8787 tcp:8787
adb shell am broadcast -a art.dart.a2ui.carui.action.AGENT \
  --es art.dart.a2ui.carui.extra.PROVIDER muse --es art.dart.a2ui.carui.extra.ASK "find coffee"
python3 scripts/compare-providers.py         # --replay all; --inject-failure chatgpt=429
python3 -m unittest discover -s tools/a2ui-gateway -p 'test_*.py'
```

Keys on another machine: `scripts/demo-gateway-tunnel.sh ca` once (the worker's CA; only its public
certificate is copied, into Host B's debug trust; rebuild), then `up` runs the gateway in tmux there
and forwards `127.0.0.1:8787` here over ssh. `adb reverse` then reaches it; no key leaves the worker.

## Host B lane (debuggable, non-user builds only)

- `INTERNET`, the network-security config and the dev CA (`<debug-overrides>`) are in `src/debug/`;
  `A2uiAgentLaneTest` reads both merged manifests. So are the gateway endpoint/token `meta-data`
  overrides; a staging build merges its own (docs/STAGING-CONTRACT.md).
- A separate processor and `TrustedActionRegistry`: a provider switch resets agent surfaces only,
  which render through the corpus path and fail closed on an undelivered reading.
- The badge is `provider · NNN ms` live, else `provider · cached` or `provider · fallback`. Action
  results report the host's decision (`ALLOWED`, `CONFIRMED`, `REFUSED`). A failed turn replays the
  last good response to the identical turn; an action result is never replayed.

## Compare

Keyless providers are skipped; `--replay` serves named providers from the cache, never calling a
vendor, and labels their rows. A 4xx, 5xx or timeout is a failure even when a fallback answered;
the fallback is counted on its own. p50/p95 cover live answers only; Model is the vendor-reported
model or `cache:<model>`. `:a2ui-model:verifyGatewayOutputs` sorts runs into valid (decodes,
applies, projects) and invalid with a reason, per provider; scores rows kept and nodes pruned per
mask and consequential actions offered, crediting a profile only with its own output; and fails on
no valid run, a file that is not an envelope, or a broken host pipeline invariant (a forbidden
component rendered, a consequential action offered while restricted).

**Not production.** A deployed gateway still needs vehicle and user identity, short-lived tokens,
tenancy, quotas, audit, replay protection, retention policy and privacy review.
