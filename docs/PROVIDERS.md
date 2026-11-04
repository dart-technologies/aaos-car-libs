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

| Selector | Adapter | `quality_cached` | `realtime_low_latency` | Key |
|---|---|---|---|---|
| `claude` | Anthropic Messages, or Claude Code headless (`claude -p`) on the logged-in subscription when the key is empty | `claude-opus-5-5`, effort high | `claude-sonnet-5-5`, low | `ANTHROPIC_API_KEY` |
| `chatgpt` | OpenAI Responses | `gpt-6-astra`, high | `gpt-6-luna`, low | `OPENAI_API_KEY` |
| `gemini` | `generateContent` + response schema | `gemini-4-argon`, high | `gemini-3.8-flash`, low | `GEMINI_API_KEY` or `GOOGLE_API_KEY` |
| `grok` | OpenAI-compatible chat | `grok-4.7` | `grok-4.20-0309-non-reasoning` | `XAI_API_KEY` |
| `muse` | OpenAI-compatible chat, `https://api.meta.ai/v1` | `muse-spark-1.3`, high | `muse-spark-1.3`, low | `META_API_KEY` |

OpenAI, Google, xAI and Meta ids were read from their model pages on 2026-11-03 (`verifiedAt`);
Claude's come from the claude-api model table. Meta lists no faster Spark tier, so Muse's profiles
differ only by effort. The gateway reads only these key names and `A2UI_GATEWAY_TOKEN`.

## What the gateway enforces

- One system prompt and one portable generation schema for every vendor. The gateway, not the model,
  writes the lifecycle: `createSurface` then `updateComponents`, v0.9, `automotive`, the request's id.
- Nothing is returned or cached before it passes the gateway schema and `BoundedA2uiJsonDecoder`'s
  limits. Anything else is a `gatewayError`: non-2xx, token detail only.
- Cache: `build/a2ui-gateway-cache/<provider>/<profile>/<sha256(model, prompt)>.json`; an output
  replays only for the model that made it. `quality_cached` replays exactly; `realtime_low_latency`
  calls live and, past 2,500 ms or on a vendor error, serves its own earlier output or the quality
  profile's. Each `surfaceResponse` says which (`source`: `live`, `cache`, `fallback`), and headers
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

## Host B lane (debuggable, non-user builds only)

- `INTERNET`, the network-security config and the dev CA (`<debug-overrides>`) are in `src/debug/`;
  `A2uiAgentLaneTest` reads both merged manifests.
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
