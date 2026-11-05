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
"""Turn handling: execution profiles, the response cache, and error envelopes.

quality_cached replays an exact cached output, generating (and caching) only on a miss.
realtime_low_latency calls the vendor and, if no valid answer arrives within the profile deadline
(providers.json deadlineMs), serves a cached output labelled ``fallback`` while the live call finishes and refreshes
the cache. Every response says what produced it: ``source`` in the envelope, the output's own model
and profile on the Response. Nothing is cached or returned before contract.validate_envelope.
"""

from __future__ import annotations

import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import re
import shutil
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Mapping

from . import adapters, contract

TOOL_DIR = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_PROVIDERS = TOOL_DIR / "providers.json"
DEFAULT_CACHE = contract.ROOT / "build" / "a2ui-gateway-cache"
GATEWAY_TOKEN_ENV = "A2UI_GATEWAY_TOKEN"
CLI_KEY = "\0cli"  # sentinel: no API key, so the provider's logged-in CLI answers
_SHA = re.compile(r"^[0-9a-f]{64}$")
_ENV_LINE = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$")


def env_names(config: dict) -> set[str]:
    """The only variables the gateway reads: each provider's named key, and its own token."""
    names = {GATEWAY_TOKEN_ENV}
    for provider in config["providers"].values():
        names.update(provider["keyEnv"])
    return names


def load_env(path: pathlib.Path | None, names: Iterable[str]) -> dict[str, str]:
    """The named variables from .env, overridden by non-empty process values. Never printed."""
    wanted = set(names)
    values: dict[str, str] = {}
    if path is not None and path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            match = _ENV_LINE.match(line)
            if match and match.group(1) in wanted and not line.lstrip().startswith("#"):
                value = match.group(2)
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                    value = value[1:-1]
                values[match.group(1)] = value
    for name in wanted:
        if os.environ.get(name):
            values[name] = os.environ[name]
    return values


def load_config(path: pathlib.Path = DEFAULT_PROVIDERS) -> dict:
    config = json.loads(path.read_text(encoding="utf-8"))
    providers = config.get("providers", {})
    if set(providers) != set(contract.PROVIDERS):
        raise ValueError(f"providers.json must configure exactly {contract.PROVIDERS}")
    for name, provider in providers.items():
        if provider.get("adapter") not in adapters.ADAPTERS:
            raise ValueError(f"{name}: unknown adapter")
        if not str(provider.get("baseUrl", "")).startswith("https://"):
            raise ValueError(f"{name}: baseUrl must be https")
        if set(provider.get("profiles", {})) != set(contract.PROFILES):
            raise ValueError(f"{name}: configure both {contract.PROFILES}")
    if set(config.get("profiles", {})) != set(contract.PROFILES):
        raise ValueError("providers.json must define both execution profiles")
    if not isinstance(config.get("policy", {}).get("vendorFallback"), bool):
        raise ValueError("providers.json must state policy.vendorFallback")
    return config


class ResponseCache:
    """build/a2ui-gateway-cache/<provider>/<profile>/<cache_key(model, prompt)>.json; outputs."""

    def __init__(self, root: pathlib.Path):
        self.root = root

    def _path(self, provider: str, profile: str, key: str) -> pathlib.Path:
        if (provider not in contract.PROVIDERS or profile not in contract.PROFILES
                or not _SHA.match(key)):
            raise ValueError("invalid cache key")
        return self.root / provider / profile / f"{key}.json"

    def get(self, provider: str, profile: str, key: str) -> dict | None:
        path = self._path(provider, profile, key)
        try:
            entry = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(entry, dict) or not isinstance(entry.get("output"), dict):
            return None
        return entry

    def put(self, provider: str, profile: str, key: str, entry: dict) -> None:
        path = self._path(provider, profile, key)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-", suffix=".json")
        with os.fdopen(fd, "wb") as handle:
            handle.write(contract.canonical(entry) + b"\n")
        os.replace(tmp, path)


@dataclass(frozen=True)
class Response:
    status: int
    body: dict
    wire: bytes
    source: str                 # live | cache | fallback | ack | error
    latency_ms: int             # this request, wall clock
    generation_ms: int | None = None   # vendor time of the output served (the original call)
    provider: str | None = None
    profile: str | None = None
    model: str | None = None           # the model this profile asks for
    served_model: str | None = None    # the model the vendor reported for the output served
    detail: str | None = None
    output_profile: str | None = None  # the profile whose call produced the output served
    output_model: str | None = None    # the model that call asked for
    live_error: str | None = None      # this turn's own vendor call failed: code[:detail]


_STATUS = {"bad_request": 400, "unauthorized": 401, "not_found": 404, "too_large": 413,
           "provider_unconfigured": 503, "cache_miss": 503, "timeout": 504, "internal": 500}


class Gateway:
    def __init__(self, config: dict, env: Mapping[str, str], cache_dir: pathlib.Path = DEFAULT_CACHE,
                 offline: bool = False,
                 complete: Callable[..., adapters.Completion] = adapters.complete,
                 clock: Callable[[], float] = time.monotonic,
                 which: Callable[[str], str | None] = shutil.which):
        self.config = config
        self.offline = offline
        self.schema = contract.load_gateway_schema()
        self.cache = ResponseCache(cache_dir)
        self._env = dict(env)
        self._complete = complete
        self._clock = clock
        self._which = which
        self._pool = concurrent.futures.ThreadPoolExecutor(
            max_workers=8, thread_name_prefix="a2ui-vendor")

    def close(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    def key_env(self, provider: str) -> list[str]:
        return list(self.config["providers"][provider]["keyEnv"])

    def has_key(self, provider: str) -> bool:
        return self._key(provider) is not None

    def _key(self, provider: str) -> str | None:
        for name in self.key_env(provider):
            if self._env.get(name):
                return self._env[name]
        cli = self.config["providers"][provider].get("cli")
        if cli and self._which(cli["command"]):
            return CLI_KEY
        return None

    def transport(self, provider: str) -> str | None:
        """'api', 'cli' (no API key, logged-in CLI), or None when the provider cannot be called."""
        key = self._key(provider)
        return None if key is None else ("cli" if key == CLI_KEY else "api")

    def settings(self, provider: str, profile: str) -> dict:
        """The profile's model/effort; a CLI may override them (its login may not offer the API id)."""
        chosen = dict(self.config["providers"][provider]["profiles"][profile])
        if self.transport(provider) == "cli":
            chosen.update(self.config["providers"][provider]["cli"].get("profiles", {})
                          .get(profile, {}))
        return chosen

    def model(self, provider: str, profile: str) -> str:
        return self.settings(provider, profile)["model"]

    def cache_key(self, provider: str, profile: str, system: str, user: str) -> str:
        return contract.cache_key(self.model(provider, profile), system, user)

    def handle(self, request: Any) -> Response:
        started = self._clock()
        try:
            return self._handle(request, started)
        except Exception as error:  # An envelope, never a traceback or vendor text.
            return self._error("internal", started, request, detail=type(error).__name__)

    def _handle(self, request: Any, started: float) -> Response:
        try:
            kind = contract.request_kind(request, self.schema)
        except contract.ContractError as error:
            return self._error("bad_request", started, detail=error.detail)
        if kind == "actionResult":
            body = {"schema": contract.SCHEMA_ID, "ack": "actionResult",
                    "provider": request["provider"], "sessionId": request["sessionId"],
                    "turnId": request["turnId"]}
            return Response(200, body, contract.compact(body), "ack", self._ms(started),
                            provider=request["provider"])
        return self._turn(request, started)

    def _turn(self, request: dict, started: float) -> Response:
        provider, profile = request["provider"], request["executionProfile"]
        system, user = contract.build_prompt(request["inputText"], request["display"])
        own = self.cache_key(provider, profile, system, user)
        ids = {"request": request, "started": started}
        key = self._key(provider)
        if profile == "quality_cached":
            entry = self.cache.get(provider, profile, own)
            if entry is not None:
                return self._serve(entry, "cache", **ids)
            if self.offline:
                return self._error("cache_miss", started, request)
            if key is None:
                return self._error("provider_unconfigured", started, request)
            try:
                entry = self._generate(provider, profile, system, user, own, key)
            except (adapters.AdapterError, contract.ContractError) as error:
                return self._failure(error, started, request)
            return self._serve(entry, "live", **ids)

        def cached():
            # This profile's own earlier output first, then the quality profile's. Either is
            # served as a labelled fallback; the Response names whose output it is.
            return (self.cache.get(provider, profile, own)
                    or self.cache.get(provider, "quality_cached",
                                      self.cache_key(provider, "quality_cached", system, user)))

        if self.offline or key is None:
            entry = cached()
            if entry is not None:
                return self._serve(entry, "cache", **ids)
            return self._error("cache_miss" if self.offline else "provider_unconfigured",
                               started, request)
        timing = self.config["profiles"][profile]
        future = self._pool.submit(self._generate, provider, profile, system, user, own, key)
        try:
            entry = future.result(timeout=timing["deadlineMs"] / 1000)
            return self._serve(entry, "live", **ids)
        except concurrent.futures.TimeoutError:
            entry = cached()
            if entry is not None:
                # The live call keeps running and refreshes the cache when it validates.
                return self._serve(entry, "fallback", live_error="deadline", **ids)
            remaining = max(0.0, timing["timeoutMs"] / 1000 - (self._clock() - started))
            try:
                return self._serve(future.result(timeout=remaining), "live", **ids)
            except concurrent.futures.TimeoutError:
                return self._error("timeout", started, request)
            except (adapters.AdapterError, contract.ContractError) as error:
                return self._failure(error, started, request)
        except (adapters.AdapterError, contract.ContractError) as error:
            entry = cached()
            if entry is not None:
                return self._serve(entry, "fallback", live_error=_describe(error), **ids)
            return self._failure(error, started, request)

    def _generate(self, provider: str, profile: str, system: str, user: str, key_hash: str,
                  key: str) -> dict:
        settings = self.config["providers"][provider]
        chosen = self.settings(provider, profile)
        timing = self.config["profiles"][profile]
        began = self._clock()
        adapter, base_url = settings["adapter"], settings["baseUrl"]
        if key == CLI_KEY:
            adapter, base_url = settings["cli"]["adapter"], settings["cli"]["command"]
        completion = self._complete(
            adapter, base_url, chosen["model"], chosen.get("effort"),
            int(chosen.get("maxTokens", 8000)), key, system, user, contract.GENERATION_SCHEMA,
            timing["timeoutMs"] / 1000,
            vendor_fallback=self.config["policy"]["vendorFallback"])
        elapsed = self._ms(began)
        generated = contract.parse_json(completion.text)
        contract.validate_generated(generated)
        output = contract.normalize(generated)
        # Validated before it is cached, with placeholder identity; re-validated when served.
        contract.validate_envelope(contract.envelope(output, provider, "cache", "cache"),
                                   self.schema)
        entry = {"cacheVersion": 2, "provider": provider, "profile": profile,
                 "model": chosen["model"], "servedModel": completion.served_model,
                 "transport": "cli" if key == CLI_KEY else "api",
                 "cacheKey": key_hash, "elapsedMs": elapsed,
                 "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                 "output": output}
        self.cache.put(provider, profile, key_hash, entry)
        return entry

    def _serve(self, entry: dict, source: str, request: dict, started: float,
               live_error: str | None = None) -> Response:
        provider, profile = request["provider"], request["executionProfile"]
        env = contract.envelope(entry["output"], provider, request["sessionId"],
                                request["turnId"], source)
        try:
            wire = contract.validate_envelope(env, self.schema)
        except contract.ContractError as error:
            return self._error("invalid_output", started, request, status=502,
                               detail=error.detail)
        return Response(200, env, wire, source, self._ms(started), entry.get("elapsedMs"),
                        provider, profile, self.model(provider, profile),
                        entry.get("servedModel"), output_profile=entry.get("profile"),
                        output_model=entry.get("model"), live_error=live_error)

    def _failure(self, error: Exception, started: float, request: dict) -> Response:
        if isinstance(error, contract.ContractError):
            return self._error("invalid_output", started, request, status=502,
                               detail=error.detail)
        return self._error(error.code, started, request, status=_STATUS.get(error.code, 502),
                           detail=error.detail)

    def _error(self, code: str, started: float, request: dict | None = None,
               status: int | None = None, detail: str | None = None) -> Response:
        request = request if isinstance(request, dict) else {}
        provider = request.get("provider")
        profile = request.get("executionProfile")
        body = contract.error_envelope(code, detail, provider, request.get("sessionId"),
                                       request.get("turnId"))
        known = provider in contract.PROVIDERS and profile in contract.PROFILES
        token = body["error"].get("detail")
        return Response(status or _STATUS.get(code, 502), body, contract.compact(body), "error",
                        self._ms(started), provider=provider if known else None,
                        profile=profile if known else None,
                        model=self.model(provider, profile) if known else None,
                        detail=token, live_error=f"{code}:{token}" if token else code)

    def _ms(self, since: float) -> int:
        return round((self._clock() - since) * 1000)


def _describe(error: Exception) -> str:
    if isinstance(error, contract.ContractError):
        return f"invalid_output:{error.detail}"
    return f"{error.code}:{error.detail}" if error.detail else error.code
