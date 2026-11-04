#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Runs UseCaseCorpus cases x providers x execution profiles through the gateway (in process),
verifies every response on the JVM, and writes build/provider-scoreboard.md.

  scripts/compare-providers.py [--providers claude,muse] [--profiles quality_cached]
                               [--cases now_playing] [--replay claude|all]
                               [--inject-failure chatgpt=429] [--env-file .env]
                               [--cache-dir DIR] [--skip-verify]

A provider calls its vendor only with its own named key; without one it is SKIPPED. --replay
serves the named providers from the gateway cache, never calling a vendor, and labels their rows
"replay". --inject-failure fails a provider's vendor call (429, 500, 503 or timeout) in process,
without a network call, to exercise the failure columns.

Every row says what produced it. A 4xx/5xx/timeout is a FAILURE even when a cached fallback
answered; the fallback is counted on its own. Latency percentiles cover live answers only. The
Model column is the vendor-reported model for live answers and cache:<model> for cached ones.
Quality (rows kept while driving, consequential actions offered) is credited only to output the
profile itself produced, never to a fallback.

Writes build/provider-runs/<provider>/<profile>/<case>.json (the exact body the vehicle would
receive), build/provider-runs/index.json, verification.json and the scoreboard. The run
directory is rebuilt each time.

Exit codes: 0 verified (or --skip-verify), 1 verifier failure, 2 setup.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import pathlib
import shutil
import statistics
import subprocess
import sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))

from a2ui_gateway import adapters, contract, core  # noqa: E402

GP = ROOT / "aaos-apps-gradle-project"
BUILD = ROOT / "build"
RUNS = BUILD / "provider-runs"
CORPUS = BUILD / "a2ui-corpus" / "use-cases.json"
SCOREBOARD = BUILD / "provider-scoreboard.md"
DRIVING = "0x1ff"
INJECTED = {"429": ("upstream", "http_429.injected"), "500": ("upstream", "http_500.injected"),
            "503": ("upstream", "http_503.injected"), "timeout": ("timeout", None)}
INJECTED_KEY = "injected-failure-never-sent"


def gradle(task: str, **properties: str) -> bool:
    command = [str(GP / "gradlew"), "-q", task] + [f"-P{k}={v}" for k, v in properties.items()]
    result = subprocess.run(command, cwd=GP, capture_output=True, text=True)
    if result.returncode:
        print(f"{task} failed:\n" + "\n".join(result.stderr.splitlines()[-15:]), file=sys.stderr)
    return result.returncode == 0


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def turn(provider: str, profile: str, case: dict) -> dict:
    return {"schema": contract.SCHEMA_ID, "type": "turn", "provider": provider,
            "executionProfile": profile, "sessionId": "compare", "turnId": case["id"],
            "inputText": f"{case['intent']}. surfaceId: {case['id']}",
            "capabilities": {"versions": [contract.VERSION], "catalogs": [contract.CATALOG_ID]},
            "display": {"id": 0, "activeRestrictions": 0, "trustworthy": True}}


def injecting(complete, config: dict, failures: dict[str, str]):
    """The vendor call, failing before any I/O for the providers named in ``failures``."""
    by_url = {config["providers"][p]["baseUrl"]: INJECTED[kind] for p, kind in failures.items()}

    def call(adapter, base_url, *args, **kwargs):
        if base_url in by_url:
            raise adapters.AdapterError(*by_url[base_url])
        return complete(adapter, base_url, *args, **kwargs)

    return call


def never(*_args, **_kwargs):
    """A replay has no key and no vendor; offline already never calls, and this makes sure."""
    raise adapters.AdapterError("upstream", "replay_calls_no_vendor")


def model_label(row: dict) -> str | None:
    if row["source"] == "live":
        return row["servedModel"] or f"{row['model']} (unreported)"
    if row["source"] == "cache":
        return f"cache:{row['outputModel']}"
    return None


def row_for(provider: str, profile: str, case: dict, mode: str, model: str,
            response: core.Response, schema_valid: bool) -> dict:
    return {"provider": provider, "profile": profile, "case": case["id"], "mode": mode,
            "model": model, "status": response.status, "source": response.source,
            "servedModel": response.served_model, "outputModel": response.output_model,
            "outputProfile": response.output_profile,
            "failure": response.status != 200 or response.live_error is not None,
            "fallback": response.source == "fallback", "liveError": response.live_error,
            "latencyMs": response.latency_ms if response.source == "live" else None,
            "schemaValid": schema_valid, "bytes": len(response.wire),
            "error": None if response.status == 200 else response.body["error"]["code"],
            "detail": response.detail}


def scoreboard(rows: list[dict], skipped: dict, verification: dict | None, cases: int,
               policy: dict) -> str:
    checked = {(r["provider"], r["profile"], r["case"]): r
               for r in (verification or {}).get("runs", [])}
    lines = ["# Provider scoreboard", "",
             f"{dt.datetime.now(dt.timezone.utc):%Y-%m-%d %H:%M} UTC · {cases} corpus case(s) · "
             f"vendor fallback {'on' if policy['vendorFallback'] else 'off'} · a failure is any "
             f"4xx/5xx/timeout, fallback or not · p50/p95 over live answers only · fitness is "
             f"Host B rows kept driving ({DRIVING}) over parked, own output only · invariants "
             f"must be 0", "",
             "| Provider | Profile | Mode | Model | Runs | Failures | Fallbacks | Live p50 ms | "
             "Live p95 ms | Host-valid | Fitness | Consequential | Invariants | Errors |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for provider in contract.PROVIDERS:
        for profile in contract.PROFILES:
            if provider in skipped:
                lines.append("| " + " | ".join(
                    [provider, profile, "skipped", "—", "0", f"no key ({skipped[provider]})"]
                    + [""] * 8) + " |")
                continue
            group = [r for r in rows if r["provider"] == provider and r["profile"] == profile]
            if not group:
                continue
            models = Counter(m for m in map(model_label, group) if m)
            fallbacks = Counter(r["outputModel"] for r in group if r["fallback"])
            live = [r["latencyMs"] for r in group if r["source"] == "live" and not r["failure"]]
            host = {r["case"]: checked.get((provider, profile, r["case"])) for r in group}
            valid = [h for h in host.values() if h and h["valid"]]
            own = [host[r["case"]] for r in group
                   if r["source"] in ("live", "cache") and r["outputProfile"] == profile
                   and host[r["case"]] and host[r["case"]]["valid"]]
            invariants = sum(h["hostPipelineInvariants"]["leaks"]
                             + h["hostPipelineInvariants"]["policyViolations"]
                             for h in host.values() if h)
            errors = Counter(r["liveError"] or r["error"] for r in group if r["failure"])
            cells = [provider, profile, group[0]["mode"],
                     ", ".join(models) or "—", str(len(group)),
                     f"{sum(r['failure'] for r in group)}/{len(group)}",
                     ", ".join(f"{n} ({m})" for m, n in fallbacks.items()) or "0",
                     str(percentile(live, 0.5) or "—"), str(percentile(live, 0.95) or "—"),
                     f"{len(valid)}/{len(group)}" if verification else "not run",
                     f"{statistics.mean(h['fitness'].get(DRIVING, 0) for h in own):.2f}"
                     if own else "—",
                     f"{statistics.mean(h['consequentialOffered'] for h in own):.1f}"
                     if own else "—",
                     str(invariants) if verification else "not run",
                     ", ".join(f"{k} ×{n}" for k, n in errors.most_common(2)) or "—"]
            lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def parse_failures(spec: list[str]) -> dict[str, str] | None:
    failures = {}
    for item in spec:
        provider, _, kind = item.partition("=")
        if provider not in contract.PROVIDERS or kind not in INJECTED:
            return None
        failures[provider] = kind
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--providers", default=",".join(contract.PROVIDERS))
    parser.add_argument("--profiles", default=",".join(contract.PROFILES))
    parser.add_argument("--cases", default="", help="comma-separated case ids (default: all)")
    parser.add_argument("--replay", default="",
                        help="providers served from the cache only, or 'all'")
    parser.add_argument("--inject-failure", action="append", default=[],
                        metavar="PROVIDER=429|500|503|timeout")
    parser.add_argument("--env-file", type=pathlib.Path, default=ROOT / ".env")
    parser.add_argument("--cache-dir", type=pathlib.Path, default=core.DEFAULT_CACHE)
    parser.add_argument("--skip-verify", action="store_true")
    args = parser.parse_args()
    providers = [p for p in args.providers.split(",") if p]
    profiles = [p for p in args.profiles.split(",") if p]
    replay = set(contract.PROVIDERS) if args.replay == "all" else {
        p for p in args.replay.split(",") if p}
    failures = parse_failures(args.inject_failure)
    if (not set(providers) <= set(contract.PROVIDERS)
            or not set(profiles) <= set(contract.PROFILES)
            or not replay <= set(contract.PROVIDERS) or failures is None
            or replay & set(failures)):
        print(f"providers (and --replay) must be from {contract.PROVIDERS}, profiles from "
              f"{contract.PROFILES}, --inject-failure PROVIDER={'|'.join(INJECTED)}, and a "
              f"replayed provider makes no call to fail", file=sys.stderr)
        return 2

    if not gradle(":a2ui-model:exportUseCaseCorpus", **{"a2ui.corpusExport": str(CORPUS)}):
        return 2
    cases = json.loads(CORPUS.read_text(encoding="utf-8"))["cases"]
    if args.cases:
        wanted = set(args.cases.split(","))
        cases = [c for c in cases if c["id"] in wanted]
        if {c["id"] for c in cases} != wanted:
            print(f"unknown case id(s): {sorted(wanted - {c['id'] for c in cases})}",
                  file=sys.stderr)
            return 2

    shutil.rmtree(RUNS, ignore_errors=True)
    config = core.load_config()
    env = core.load_env(args.env_file, core.env_names(config))
    for provider in failures:
        # A key only so the injected call is attempted; injecting() raises before any I/O.
        env.setdefault(config["providers"][provider]["keyEnv"][0], INJECTED_KEY)
    live_gateway = core.Gateway(config, env, args.cache_dir,
                                complete=injecting(adapters.complete, config, failures))
    replay_gateway = core.Gateway(config, {}, args.cache_dir, offline=True, complete=never)
    import jsonschema  # pinned in tools/a2ui-gateway/requirements.txt
    surface_schema = jsonschema.Draft202012Validator(
        contract.definition(live_gateway.schema, "surfaceResponse"))
    rows, skipped = [], {}
    try:
        for provider in providers:
            mode = "replay" if provider in replay else "live"
            gateway = replay_gateway if mode == "replay" else live_gateway
            if mode == "live" and not live_gateway.has_key(provider):
                skipped[provider] = " or ".join(live_gateway.key_env(provider))
                print(f"SKIP {provider}: no {skipped[provider]} in the environment or "
                      f"{args.env_file}; --replay {provider} serves its cached outputs")
                continue
            for profile in profiles:
                for case in cases:
                    response = gateway.handle(turn(provider, profile, case))
                    path = RUNS / provider / profile / f"{case['id']}.json"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(response.wire)
                    valid = response.status == 200 and surface_schema.is_valid(
                        json.loads(response.wire))
                    row = row_for(provider, profile, case, mode,
                                  gateway.model(provider, profile), response, valid)
                    rows.append(row)
                    print(f"{provider:8} {profile:21} {case['id']:20} {mode:6} "
                          f"{response.status} {response.source:8} "
                          f"{'FAILURE ' if row['failure'] else ''}"
                          f"{model_label(row) or ''} {response.live_error or ''}".rstrip())
    finally:
        live_gateway.close()
        replay_gateway.close()
    RUNS.mkdir(parents=True, exist_ok=True)
    (RUNS / "index.json").write_text(json.dumps(rows, indent=1) + "\n", encoding="utf-8")

    verification, status = None, 0
    if rows and not args.skip_verify:
        report = RUNS / "verification.json"
        passed = gradle(":a2ui-model:verifyGatewayOutputs",
                        **{"a2ui.providerRuns": str(RUNS),
                           "a2ui.providerVerification": str(report)})
        if report.is_file():
            verification = json.loads(report.read_text(encoding="utf-8"))
        if not passed:
            print("VERIFY FAILED: no valid run, a corrupt run file, or a host pipeline "
                  "invariant broken; see verification.json", file=sys.stderr)
            status = 1
    SCOREBOARD.write_text(scoreboard(rows, skipped, verification, len(cases), config["policy"]),
                          encoding="utf-8")
    print(f"{len(rows)} run(s) -> {RUNS.relative_to(ROOT)}; scoreboard -> "
          f"{SCOREBOARD.relative_to(ROOT)}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
