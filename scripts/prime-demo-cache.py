#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Prime and verify the committed gateway cache behind the recorded demos.

Every scenario agent action is one gateway turn; the device sends it with the driver
display, the scripted car state's restrictions, and the parked-or-moving profile, and
the gateway's exact-key cache decides the take. --prime sends each turn to a live
gateway once (vendor keys required) and commits the real response beside the manifest,
so every later take replays it byte-identically. --check proves each turn has a
committed entry that still validates, offline, with no keys; CI runs it.

  python3 scripts/prime-demo-cache.py --check
  python3 scripts/prime-demo-cache.py --prime --gateway https://127.0.0.1:8787/v1 \\
      --cache-dir build/a2ui-gateway-cache

Record with serve.py pointed at the committed cache:
  python3 tools/a2ui-gateway/serve.py --offline \\
      --cache-dir a2ui-automotive/demo/cache
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import shutil
import ssl
import sys
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))
from a2ui_gateway import contract, core  # noqa: E402

_spec = importlib.util.spec_from_file_location("check_nyc_demo", HERE / "check-nyc-demo.py")
nyc = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = nyc
_spec.loader.exec_module(nyc)

MANIFEST = ROOT / "a2ui-automotive/demo/nyc-scenarios-v1.json"
PROVIDERS = ROOT / "tools/a2ui-gateway/providers.json"
SCHEMA = ROOT / "a2ui-automotive/agent-gateway-v1.schema.json"
COMMITTED_CACHE = ROOT / "a2ui-automotive/demo/cache"
# The driver activity launches on the default display; its restriction reader reports the
# scripted car state (baseline parked, fully restricted moving) through the real manager.
DRIVER_DISPLAY_ID = 0
PARKED_RESTRICTIONS = 0x0
DRIVING_RESTRICTIONS = 0x1FF


def gateway_for_keys() -> core.Gateway:
    """A keyless gateway: cache keys never depend on keys, so --check needs none."""
    return core.Gateway(core.load_config(PROVIDERS), {}, COMMITTED_CACHE, offline=True)


def turns(scenarios: list[dict] | None = None) -> list[dict]:
    """Every scenario agent action as the turn the device sends for it."""
    if scenarios is None:
        scenarios = json.loads(MANIFEST.read_text(encoding="utf-8"))["scenarios"]
    out = []
    for scenario in scenarios:
        for action in scenario.get("actions", []):
            if action.get("type") != "agent":
                continue
            driving = nyc.driving_at(scenario, int(action["atMs"]))
            out.append({"scenario": scenario["id"], "provider": action["provider"],
                        "profile": ("realtime_low_latency" if driving else "quality_cached"),
                        "inputText": action["ask"],
                        "display": {"id": DRIVER_DISPLAY_ID,
                                    "activeRestrictions": (DRIVING_RESTRICTIONS if driving
                                                           else PARKED_RESTRICTIONS),
                                    "trustworthy": True}})
    return out


def cache_key(gw: core.Gateway, turn: dict) -> str:
    system, user = contract.build_prompt(
        turn["inputText"], turn["display"],
        grounded=gw.search(turn["provider"], turn["profile"]) is not None)
    return gw.cache_key(turn["provider"], turn["profile"], system, user)


def entry_path(provider: str, profile: str, key: str) -> pathlib.Path:
    return COMMITTED_CACHE / provider / profile / f"{key}.json"


def check() -> int:
    gw = gateway_for_keys()
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    found = turns()
    if not found:
        print("FAIL: the manifest has no agent action to prime", file=sys.stderr)
        return 1
    for turn in found:
        key = cache_key(gw, turn)
        path = entry_path(turn["provider"], turn["profile"], key)
        shown = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
        if not path.is_file():
            print(f"FAIL: {turn['scenario']} ask has no committed cache entry: {shown}",
                  file=sys.stderr)
            return 1
        try:
            entry = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            print(f"FAIL: {shown} is not JSON", file=sys.stderr)
            return 1
        if (entry.get("cacheKey") != key or entry.get("provider") != turn["provider"]
                or entry.get("profile") != turn["profile"]
                or not isinstance(entry.get("output"), dict)):
            print(f"FAIL: {shown} is not this turn's entry", file=sys.stderr)
            return 1
        versions = {m.get("version") for m in entry["output"].get("messages", [])}
        if versions != {"1.0"}:
            print(f"FAIL: {shown} stamps {sorted(versions)}, not 1.0", file=sys.stderr)
            return 1
        try:
            contract.validate_envelope(contract.envelope(
                entry["output"], turn["provider"], "check", "check", "cache"), schema)
        except contract.ContractError as error:
            print(f"FAIL: {shown} no longer validates: {error.detail}", file=sys.stderr)
            return 1
        print(f"OK: {turn['scenario']} ask replays {turn['provider']}/"
              f"{turn['profile']}/{key[:12]}… ({entry.get('servedModel')})")
    return 0


DEV_TOKEN = "a2ui-dev-local"  # serve.py's default; not a vendor credential


def prime(url: str, cache_dir: pathlib.Path, token: str) -> int:
    gw = gateway_for_keys()
    context = ssl._create_unverified_context()
    for index, turn in enumerate(turns()):
        body = {"schema": contract.SCHEMA_ID, "type": "turn", "provider": turn["provider"],
                "executionProfile": turn["profile"], "sessionId": "prime",
                "turnId": f"prime-{index}", "inputText": turn["inputText"],
                "capabilities": {"versions": ["1.0"], "catalogs": ["automotive"]},
                "display": turn["display"]}
        request = urllib.request.Request(
            url, data=contract.compact(body),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {token}"}, method="POST")
        try:
            with urllib.request.urlopen(request, context=context,
                                        timeout=180) as response:
                served = json.loads(response.read().decode("utf-8"))
        except Exception as error:
            print(f"FAIL: {turn['scenario']} ask did not reach the gateway: "
                  f"{type(error).__name__}", file=sys.stderr)
            return 1
        if served.get("source") != "live":
            print(f"FAIL: {turn['scenario']} ask served {served.get('source')}, not live; "
                  f"refusing to commit a replay or an error", file=sys.stderr)
            return 1
        key = cache_key(gw, turn)
        primed = cache_dir / turn["provider"] / turn["profile"] / f"{key}.json"
        if not primed.is_file():
            print(f"FAIL: live answer left no cache entry at {primed}", file=sys.stderr)
            return 1
        dest = entry_path(turn["provider"], turn["profile"], key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(primed, dest)
        committed = json.loads(dest.read_text(encoding="utf-8"))
        print(f"OK: primed {turn['scenario']} ask -> {dest.relative_to(ROOT)} "
              f"({committed.get('servedModel')})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true")
    group.add_argument("--prime", action="store_true")
    parser.add_argument("--gateway", default="https://127.0.0.1:8787/v1")
    parser.add_argument("--cache-dir", type=pathlib.Path,
                        default=ROOT / "build" / "a2ui-gateway-cache")
    parser.add_argument("--token", default=DEV_TOKEN,
                        help="gateway auth value (serve.py default shown; "
                             "overrides via its env file)")
    args = parser.parse_args(argv)
    if args.check:
        return check()
    return prime(args.gateway, args.cache_dir, args.token)


if __name__ == "__main__":
    raise SystemExit(main())
