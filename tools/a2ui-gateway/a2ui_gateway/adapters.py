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
"""Vendor adapters: Anthropic Messages, OpenAI Responses, Gemini generateContent, one
OpenAI-compatible chat-completions adapter shared by xAI (Grok) and Meta (Muse), and Claude Code
headless for a subscription baseline.

Raw HTTPS through urllib rather than five SDKs, deliberately: one transport with no hidden
retries keeps latency comparable across vendors and makes the realtime deadline mean what it says.
Redirects are refused because urllib would replay the credential header to the new host. Errors
carry the HTTP status and the vendor's error code token only; a vendor body can echo the prompt.
"""

from __future__ import annotations

import json
import re
import socket
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass

MAX_RESPONSE_BYTES = 4 * 1024 * 1024
SCHEMA_NAME = "a2ui_surfaces"
# Claude: current API version, and refusal fallback opted in by default (claude-api skill).
ANTHROPIC_VERSION = "2026-10-21"
ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"

_TOKEN = re.compile(r"^[A-Za-z0-9_.:-]{1,48}$")
_MODEL = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


class AdapterError(Exception):
    """``code`` is a gatewayError code (upstream, timeout, refusal, truncated)."""

    def __init__(self, code: str, detail: str | None = None):
        super().__init__(code if detail is None else f"{code}:{detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class Completion:
    text: str
    served_model: str | None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(
    _NoRedirect, urllib.request.HTTPSHandler(context=ssl.create_default_context()))


def _token(value) -> str | None:
    return value if isinstance(value, str) and _TOKEN.match(value) else None


def _error_detail(error: urllib.error.HTTPError) -> str:
    detail = f"http_{error.code}"
    try:
        body = json.loads(error.read(65536))
    except Exception:
        return detail
    if isinstance(body, list) and body:
        body = body[0]
    err = body.get("error") if isinstance(body, dict) else None
    if isinstance(err, dict):
        for field in ("type", "code", "status"):
            token = _token(err.get(field))
            if token:
                return f"{detail}.{token}"
    return detail


def _post(url: str, headers: dict, body: dict, timeout_s: float) -> dict:
    if not url.startswith("https://"):
        raise AdapterError("upstream", "non_https_endpoint")
    request = urllib.request.Request(
        url, data=json.dumps(body, separators=(",", ":")).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", **headers})
    try:
        with _OPENER.open(request, timeout=timeout_s) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise AdapterError("upstream", _error_detail(error)) from None
    except urllib.error.URLError as error:
        if isinstance(error.reason, (TimeoutError, socket.timeout)):
            raise AdapterError("timeout") from None
        raise AdapterError("upstream", "network") from None
    except (TimeoutError, socket.timeout):
        raise AdapterError("timeout") from None
    except OSError:
        raise AdapterError("upstream", "network") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise AdapterError("upstream", "response_too_large")
    try:
        parsed = json.loads(raw)
    except ValueError:
        raise AdapterError("upstream", "not_json") from None
    if not isinstance(parsed, dict):
        raise AdapterError("upstream", "not_object")
    return parsed


def anthropic_messages(base_url, model, effort, max_tokens, key, system, user, schema, timeout_s,
                       vendor_fallback=False):
    output_config = {"format": {"type": "json_schema", "schema": schema}}
    if effort:
        output_config["effort"] = effort
    # No "thinking" field: Opus 5.5 and Sonnet 5.5 run adaptive thinking by default, and both
    # reject "disabled". Effort is the latency/quality control.
    body = {"model": model, "max_tokens": max_tokens, "system": system,
            "messages": [{"role": "user", "content": user}],
            "output_config": output_config, "cache_control": {"type": "ephemeral"}}
    headers = {"x-api-key": key, "anthropic-version": ANTHROPIC_VERSION}
    if vendor_fallback:
        # Server-side re-run on another model after a refusal. Off for comparisons (providers.json
        # policy): only Anthropic offers it, so leaving it on would credit Claude with other models.
        body["fallbacks"] = "default"
        headers["anthropic-beta"] = ANTHROPIC_FALLBACK_BETA
    response = _post(f"{base_url}/messages", headers, body, timeout_s)
    stop = response.get("stop_reason")
    if stop == "refusal":
        details = response.get("stop_details")
        raise AdapterError("refusal", _token(details.get("category"))
                           if isinstance(details, dict) else None)
    if stop == "max_tokens":
        raise AdapterError("truncated")
    for block in response.get("content") or []:
        if isinstance(block, dict) and block.get("type") == "text":
            return Completion(str(block.get("text", "")), _token(response.get("model")))
    raise AdapterError("upstream", "no_text")


def openai_responses(base_url, model, effort, max_tokens, key, system, user, schema, timeout_s):
    body = {"model": model, "instructions": system,
            "input": [{"role": "user", "content": user}],
            "text": {"format": {"type": "json_schema", "name": SCHEMA_NAME, "schema": schema,
                                "strict": True}},
            "store": False, "max_output_tokens": max_tokens}
    if effort:
        body["reasoning"] = {"effort": effort}
    response = _post(f"{base_url}/responses", {"Authorization": f"Bearer {key}"}, body,
                     timeout_s)
    status = response.get("status")
    if status == "incomplete":
        reason = (response.get("incomplete_details") or {}).get("reason")
        if reason == "content_filter":
            raise AdapterError("refusal")
        raise AdapterError("truncated", _token(reason))
    if status != "completed":
        raise AdapterError("upstream", _token(f"status_{status}"))
    texts = []
    for item in response.get("output") or []:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        for part in item.get("content") or []:
            if isinstance(part, dict) and part.get("type") == "refusal":
                raise AdapterError("refusal")
            if isinstance(part, dict) and part.get("type") == "output_text":
                texts.append(str(part.get("text", "")))
    if not texts:
        raise AdapterError("upstream", "no_text")
    return Completion("".join(texts), _token(response.get("model")))


def gemini_generate_content(base_url, model, effort, max_tokens, key, system, user, schema,
                            timeout_s):
    config = {"responseMimeType": "application/json", "responseJsonSchema": schema,
              "maxOutputTokens": max_tokens}
    if effort:
        config["thinkingConfig"] = {"thinkingLevel": effort.upper()}
    body = {"systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": config}
    response = _post(f"{base_url}/models/{model}:generateContent", {"x-goog-api-key": key},
                     body, timeout_s)
    if (response.get("promptFeedback") or {}).get("blockReason"):
        raise AdapterError("refusal", _token(response["promptFeedback"]["blockReason"]))
    candidates = response.get("candidates") or []
    if not candidates or not isinstance(candidates[0], dict):
        raise AdapterError("upstream", "no_candidate")
    finish = candidates[0].get("finishReason")
    if finish == "MAX_TOKENS":
        raise AdapterError("truncated")
    if finish not in (None, "STOP"):
        raise AdapterError("refusal", _token(finish))
    parts = (candidates[0].get("content") or {}).get("parts") or []
    text = "".join(str(p.get("text", "")) for p in parts
                   if isinstance(p, dict) and not p.get("thought"))
    if not text:
        raise AdapterError("upstream", "no_text")
    return Completion(text, _token(response.get("modelVersion")))


def openai_chat(base_url, model, effort, max_tokens, key, system, user, schema, timeout_s):
    del max_tokens  # Parameter name differs across compatible vendors; the read bound applies.
    body = {"model": model,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": SCHEMA_NAME, "schema": schema,
                                                "strict": True}}}
    if effort:
        body["reasoning_effort"] = effort
    response = _post(f"{base_url}/chat/completions", {"Authorization": f"Bearer {key}"}, body,
                     timeout_s)
    choices = response.get("choices") or []
    if not choices or not isinstance(choices[0], dict):
        raise AdapterError("upstream", "no_choice")
    finish = choices[0].get("finish_reason")
    message = choices[0].get("message") or {}
    if message.get("refusal") or finish == "content_filter":
        raise AdapterError("refusal")
    if finish == "length":
        raise AdapterError("truncated")
    content = message.get("content")
    if isinstance(content, list):
        content = "".join(str(p.get("text", "")) for p in content if isinstance(p, dict))
    if not isinstance(content, str) or not content:
        raise AdapterError("upstream", "no_text")
    return Completion(content, _token(response.get("model")))


def claude_code_cli(base_url, model, effort, max_tokens, key, system, user, schema, timeout_s):
    """Claude Code headless on the logged-in subscription. ``base_url`` is the CLI command.

    Not --bare: bare mode reads only ANTHROPIC_API_KEY. Isolation instead: no tools, MCP or slash
    commands, an explicit system prompt, and an empty working directory (no project CLAUDE.md).
    """
    del key, max_tokens  # auth is the CLI's login; the CLI sizes its own output
    command = [base_url, "-p", "--output-format", "json",
               "--json-schema", json.dumps(schema, separators=(",", ":")), "--model", model,
               "--tools", "", "--strict-mcp-config", "--disable-slash-commands",
               "--no-session-persistence", "--permission-mode", "dontAsk",
               "--system-prompt", system]
    if effort:
        command += ["--effort", effort]
    with tempfile.TemporaryDirectory(prefix="a2ui-claude-") as cwd:
        try:
            done = subprocess.run(command + [user], cwd=cwd, stdin=subprocess.DEVNULL,
                                  capture_output=True, text=True, timeout=timeout_s)
        except subprocess.TimeoutExpired:
            raise AdapterError("timeout") from None
        except OSError:
            raise AdapterError("upstream", "cli_missing") from None
    try:
        wrapper = json.loads(done.stdout)
    except ValueError:
        raise AdapterError("upstream", f"cli_exit_{done.returncode}") from None
    if not isinstance(wrapper, dict) or wrapper.get("is_error"):
        detail = wrapper.get("subtype") if isinstance(wrapper, dict) else None
        raise AdapterError("upstream", _token(detail) or f"cli_exit_{done.returncode}")
    structured = wrapper.get("structured_output")
    if not isinstance(structured, dict):
        raise AdapterError("upstream", "no_structured_output")
    usage = wrapper.get("modelUsage")
    served = next(iter(usage), None) if isinstance(usage, dict) else None
    return Completion(json.dumps(structured), _token(served))


ADAPTERS = {
    "anthropic_messages": anthropic_messages,
    "claude_code_cli": claude_code_cli,
    "openai_responses": openai_responses,
    "gemini_generate_content": gemini_generate_content,
    "openai_chat": openai_chat,
}


def complete(adapter: str, base_url: str, model: str, effort: str | None, max_tokens: int,
             key: str, system: str, user: str, schema: dict, timeout_s: float,
             vendor_fallback: bool = False) -> Completion:
    if adapter not in ADAPTERS:
        raise AdapterError("upstream", "unknown_adapter")
    if not _MODEL.match(model):
        raise AdapterError("upstream", "bad_model_id")
    call = ADAPTERS[adapter]
    args = (base_url.rstrip("/"), model, effort, max_tokens, key, system, user, schema, timeout_s)
    if call is anthropic_messages:
        return call(*args, vendor_fallback=vendor_fallback)
    return call(*args)
