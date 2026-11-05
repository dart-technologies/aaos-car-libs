#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Generate or replay a normalized A2UI response for a prompt file, through the gateway's Claude
adapter (tools/a2ui-gateway). A thin CLI only: there is one Claude code path, the gateway's, and
this keeps scripts/run-bump-gate.sh's credential-free --dry-run preflight working.

Exit codes: 0 ok, 3 offline cache miss, 5 no ANTHROPIC_API_KEY, 6 provider or validation failure.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "a2ui-gateway"))

from a2ui_gateway import contract, core  # noqa: E402

PROFILES = {"quality-cached": "quality_cached", "realtime-low": "realtime_low_latency"}


def portable(node, path="$") -> None:
    """The generation grammar must stay inside what every vendor's structured output enforces."""
    if isinstance(node, dict):
        extra = set(node) - contract.PORTABLE_KEYWORDS
        if extra:
            raise RuntimeError(f"non-portable schema keyword(s) at {path}: {sorted(extra)}")
        for key, child in node.items():
            children = child.items() if key == "properties" else [(key, child)]
            for name, value in children:
                portable(value, f"{path}/{name}")
    elif isinstance(node, list):
        for index, child in enumerate(node):
            portable(child, f"{path}/{index}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prompt-file", required=True, type=pathlib.Path)
    parser.add_argument("--profile", choices=sorted(PROFILES), default="quality-cached")
    parser.add_argument("--cache-dir", type=pathlib.Path, default=core.DEFAULT_CACHE)
    parser.add_argument("--env-file", type=pathlib.Path, default=ROOT / ".env")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    text = re.sub(r"\A\s*<!--.*?-->\s*", "", args.prompt_file.read_text(encoding="utf-8"),
                  flags=re.S).strip()
    profile = PROFILES[args.profile]
    request = {"schema": contract.SCHEMA_ID, "type": "turn", "provider": "claude",
               "executionProfile": profile, "sessionId": "oem-demo", "turnId": "poi-media",
               "inputText": text,
               "capabilities": {"versions": [contract.VERSION], "catalogs": [contract.CATALOG_ID]},
               "display": {"id": 0, "activeRestrictions": 0, "trustworthy": True}}
    portable(contract.GENERATION_SCHEMA)
    config = core.load_config()
    schema = contract.load_gateway_schema()
    try:
        import jsonschema
    except ImportError:
        jsonschema = None
    if jsonschema is not None:
        jsonschema.Draft202012Validator.check_schema(schema)
        contract.request_kind(request, schema)
    elif len(text) > contract.MAX_STRING_CHARS:
        raise RuntimeError("prompt exceeds the 4096-character turn limit")
    chosen = config["providers"]["claude"]["profiles"][profile]
    sha = contract.cache_key(chosen["model"], *contract.build_prompt(text, request["display"]))

    if args.dry_run:
        print(json.dumps({"cacheKey": sha, "profile": profile, "model": chosen["model"],
                          "effort": chosen["effort"], "schemaChecked": jsonschema is not None,
                          "request": "POST /v1/messages output_config.format=json_schema "
                                     "<generation-schema> <prompt>"}, sort_keys=True))
        return 0

    gateway = core.Gateway(config, core.load_env(args.env_file, core.env_names(config)),
                           args.cache_dir, args.offline)
    try:
        if args.refresh:
            gateway.cache._path("claude", profile, sha).unlink(missing_ok=True)
        response = gateway.handle(request)
    finally:
        gateway.close()
    if response.status == 200:
        print(json.dumps(response.body, sort_keys=True))
        return 0
    error = response.body["error"]
    if error["code"] == "cache_miss":
        print(f"cache miss: {sha}", file=sys.stderr)
        return 3
    if error["code"] == "provider_unconfigured":
        print("ANTHROPIC_API_KEY is not set (see .env.example)", file=sys.stderr)
        return 5
    print(f"Claude generation failed: {error['code']} {error.get('detail', '')}".strip(),
          file=sys.stderr)
    return 6


if __name__ == "__main__":
    raise SystemExit(main())
