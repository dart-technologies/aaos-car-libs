# Staging contract: Host B against a real gateway

Sprint 2 P5 exit: the hosts run against staging with no demo stubs. This is the
contract between the vehicle client and any gateway it talks to — the dev gateway
today, a staging gateway the same way.

## Wire shape

- One contract, [`a2ui.gateway.v1`](../a2ui-automotive/agent-gateway-v1.schema.json):
  `POST` the turn, get a `surfaceResponse` whose `source` is `live`, `cache`, or
  `fallback`. Headers name the output's model, its profile, and the live error when
  the answer is not live.
- HTTPS only, no redirects followed. The endpoint is an origin/path with no
  credentials, query, or fragment (`AgentGatewayConfig` rejects anything else).
- Auth is `Authorization: Bearer <token>`, where the token is a short-lived
  OEM-issued gateway token — never a model-vendor API key. No APK carries a vendor
  key; model ids, keys, and conversion stay in the gateway (`docs/PROVIDERS.md`).
- A blank, padded, or multi-line token fails closed at send time
  (`AUTHENTICATION`, "gateway token is absent or malformed").

## Client behavior staging must tolerate

- Stateless turns: the host resets its agent processor on every ask (atomically
  with the generation bump — `AgentSession.submitTurnAfterReset`), so the gateway
  re-creates its surfaces every turn. A late response from an older turn is dropped
  by generation, never applied.
- Overlapping asks: only the newest turn's response is applied; the older one's
  arrival after a reset is silently discarded.
- Fallback replay: when a live call fails, the lane replays its last good response
  for that provider+profile and labels the badge `fallback` (or `cached`). Action
  results are never cached or replayed — a failed action-result turn fails, it is
  not acknowledged from cache.
- Timeouts on the lane: 3 s connect, 15 s read. The realtime profile's 10 s live
  deadline is a gateway-side policy (`docs/PROVIDERS.md`).

## Pointing a build at staging

The lane reads two application `meta-data` keys, declared in the debug manifest
with the dev values and asserted absent from release by `A2uiAgentLaneTest`:

| Key | Debug value | Staging |
|---|---|---|
| `art.dart.a2ui.GATEWAY_ENDPOINT` | `https://127.0.0.1:8787/v1` | the staging origin/path |
| `art.dart.a2ui.GATEWAY_TOKEN` | `a2ui-dev-local` | the staging token |

- A staging build merges its own values for both keys; anything else (missing keys,
  an unparseable endpoint) falls back to the dev gateway with a warning, so a
  misconfigured manifest never breaks the host's boot.
- Programmatic callers inject explicitly instead:
  `AgentLane.create(main, listener, endpoint, tokens)`.
- The staging token is still a short-lived OEM token, rotated like any credential;
  the debug value is a local constant the dev gateway checks, not a secret.
- `INTERNET`, the network-security config, and the dev CA stay debug-only; staging
  serves a publicly-trusted certificate, so it needs no `<debug-overrides>`.

## Running the other end

```bash
python3 -m pip install --require-hashes -r tools/a2ui-gateway/requirements.txt
cp .env.example .env && chmod 600 .env     # A2UI_GATEWAY_TOKEN + vendor keys
python3 tools/a2ui-gateway/serve.py --port 8787
adb reverse tcp:8787 tcp:8787              # dev: lane reaches 127.0.0.1:8787
```

Staging runs the same server behind TLS with its own token in `A2UI_GATEWAY_TOKEN`
(or its own gateway speaking `a2ui.gateway.v1`); the vehicle side changes only the
two `meta-data` values above. `--offline` serves the cache and never calls a
vendor, for acceptance runs that must not depend on vendor availability.

## Acceptance

- The host, pointed at staging, completes turns with no demo stubs in the path, and
  `scripts/compare-providers.py` stays green (it verifies every corpus case ×
  provider × profile against the same `a2ui.gateway.v1` contract, in process).
- Every non-live answer is labelled `cached` or `fallback` in the badge; no turn
  applies a response from an older generation.
- The release manifest carries neither override key and requests no `INTERNET`.
