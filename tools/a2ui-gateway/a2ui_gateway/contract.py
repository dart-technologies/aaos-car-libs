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
"""Provider-neutral half of the gateway: prompt, generation grammar, normalization, validation.

Every vendor gets the same prompt and the same narrow generation schema, so a comparison measures
the model rather than five different contracts. A provider mode (Muse's cited brief) swaps in the
grounded prompt; comparisons drop modes. The gateway, not the model, writes the lifecycle
envelope (version, catalog, ordering, identity), and the result must then pass the full gateway
schema plus the vehicle decoder's own bounds before it is returned or cached.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import urllib.parse
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[3]
GATEWAY_SCHEMA_PATH = ROOT / "a2ui-automotive" / "agent-gateway-v1.schema.json"
SCHEMA_ID = "a2ui.gateway.v1"
VERSION = "v0.9"
CATALOG_ID = "automotive"
PROVIDERS = ("claude", "chatgpt", "gemini", "grok", "muse")
PROFILES = ("quality_cached", "realtime_low_latency")
SOURCES = ("live", "cache", "fallback")
_ID = re.compile(r"^[A-Za-z0-9._:-]{1,256}$")

CATALOG = [
    "Text", "Image", "Icon", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs",
    "Modal", "Divider", "Button", "TextField", "CheckBox", "ChoicePicker", "Slider",
    "DateTimeInput",
]
# The host's trusted action vocabulary. A label never grants authority; the host registry does.
ACTIONS = ["play", "pause", "stop", "next", "previous", "open", "select", "navigate", "search",
           "edit", "purchase"]

# BoundedA2uiJsonDecoder's limits. The gateway refuses anything the vehicle would refuse.
MAX_BYTES = 256 * 1024
MAX_DEPTH = 64
MAX_NODES = 1_000
MAX_STRING_CHARS = 4_096
MAX_MESSAGES = 64

# Only keywords every vendor's structured-output mode enforces: no anyOf/const/pattern/null/
# numeric or length bounds. Every property is required (OpenAI/Meta strict mode); "" and [] mean
# "absent" and are stripped by normalize().
_COMPONENT = {
    "type": "object",
    "properties": {
        "id": {"type": "string"},
        "component": {"type": "string", "enum": CATALOG},
        "title": {"type": "string"},
        "subtitle": {"type": "string"},
        "action": {"type": "string", "enum": [""] + ACTIONS},
        "voicePhrase": {"type": "string"},
        "children": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["id", "component", "title", "subtitle", "action", "voicePhrase", "children"],
    "additionalProperties": False,
}
GENERATION_SCHEMA = {
    "type": "object",
    "properties": {
        "assistantText": {"type": "string"},
        "surfaces": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "surfaceId": {"type": "string"},
                    "components": {"type": "array", "items": _COMPONENT},
                },
                "required": ["surfaceId", "components"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["assistantText", "surfaces"],
    "additionalProperties": False,
}
PORTABLE_KEYWORDS = {"type", "properties", "required", "additionalProperties", "items", "enum"}

SYSTEM_PROMPT = f"""You compose A2UI {VERSION} surfaces for an Android Automotive head unit. Reply \
with JSON only, matching the response schema.

Surface rules:
- One surface unless the request asks for more. Use the surfaceId the request gives, otherwise a \
short snake_case id.
- 3 to 12 components per surface, as one flat list. Ids are unique within a surface and use only \
letters, digits, ".", "_", ":" or "-".
- One component has id "root" and is a Column. Containers (Column, Row, List, Card) name their \
children by id; every other component has "children": [].
- Component types: {", ".join(CATALOG)}.
- Titles and subtitles are plain text of at most 60 characters; use "" when a field does not apply.
- An interactive component sets "action" to one of: {", ".join(ACTIONS)}. Others use "". \
"voicePhrase" is an optional short spoken alias for an interactive component, else "".

Driving rules:
- The host prunes by the display's activeRestrictions bitmask, removing a forbidden component \
together with its whole subtree. 0x1ff (driving) keeps only Text, Icon, Row, Column, Divider and \
Button. 0x10 removes Video. 0x8 removes TextField and DateTimeInput. 0x2 removes TextField and \
ChoicePicker. 0x40 removes TextField, CheckBox, ChoicePicker, Slider and DateTimeInput. 0x20 \
removes Video, Tabs and Modal. trustworthy=false means no reading: treat it as 0x1ff.
- Put the essential information in Text and Button components directly under root, so the \
surface stays useful when restricted. The host confirms purchases and refuses them while moving.
- Do not invent URLs, coordinates, prices, licences or claims about real businesses; label sample \
content DEMO.

assistantText: one sentence for the driver, at most 120 characters."""

# Grounded variant (a provider mode with web search): facts come from cited findings, and the
# gateway, not the model, writes the source rows. The plain prompt stays byte-identical.
_COUNT_RULE = "3 to 12 components per surface"
_DEMO_RULE = ("- Do not invent URLs, coordinates, prices, licences or claims about real businesses; "
              "label sample content DEMO.")
GROUNDED_PROMPT = SYSTEM_PROMPT.replace(_COUNT_RULE, "3 to 9 components per surface").replace(
    _DEMO_RULE, "- Facts about real places come only from the web findings in the request; add no "
    "others. Write no URLs or source list: the gateway appends the cited sources as rows. Label "
    "anything else sample content DEMO.")
assert _COUNT_RULE in SYSTEM_PROMPT and _DEMO_RULE in SYSTEM_PROMPT
SEARCH_PROMPT = ("You research a request from a driver's in-car assistant. Search the web and open "
                 "the official site first, then one or two other reputable pages. Answer in 3 to 6 "
                 "short factual sentences, citing a source for every fact. Plain prose: no lists, "
                 "no URLs.")
FINDINGS_HEADER = "Web findings (cited; the gateway attaches their sources):"
SOURCE_ROWS = 3
SOURCE_PREFIX = "Source · "
# A2uiValueLimits' fail-closed string length: a source row is never truncated by any reading.
VALUE_LIMIT = 60
_HOST = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)+$")


class ContractError(Exception):
    """Vendor output the gateway must not return. ``detail`` is a token, never vendor text."""

    def __init__(self, detail: str):
        super().__init__(detail)
        self.detail = detail


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def compact(value: Any) -> bytes:
    """Wire bytes, key order preserved."""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def build_prompt(input_text: str, display: dict, grounded: bool = False) -> tuple[str, str]:
    user = (f"Request: {input_text}\n"
            f"Display: id={display['id']} activeRestrictions=0x{display['activeRestrictions']:x} "
            f"trustworthy={'true' if display['trustworthy'] else 'false'}")
    return (GROUNDED_PROMPT if grounded else SYSTEM_PROMPT), user


def cache_key(model: str, system: str, user: str, extra: bytes | None = None) -> str:
    """Model id plus everything the model sees: an output replays only for the call that made it.
    ``extra`` (a mode's search settings and prompts) is appended only when set, so plain keys hold."""
    parts = [model.encode(), system.encode(), user.encode(), canonical(GENERATION_SCHEMA)]
    if extra is not None:
        parts.append(extra)
    return hashlib.sha256(b"\0".join(parts)).hexdigest()


def _reject_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ContractError("duplicate_key")
        out[key] = value
    return out


def _reject_constant(_name):
    raise ContractError("non_finite_number")


def parse_json(text: str | bytes) -> Any:
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicates,
                          parse_constant=_reject_constant)
    except (ValueError, RecursionError):
        raise ContractError("not_json") from None


def _validator(schema: dict):
    import jsonschema  # pinned in requirements.txt
    return jsonschema.Draft202012Validator(schema)


def validate_generated(value: Any) -> None:
    error = next(iter(_validator(GENERATION_SCHEMA).iter_errors(value)), None)
    if error is not None:
        raise ContractError(f"generation_schema.{error.validator}")
    surfaces = value["surfaces"]
    if not surfaces:
        raise ContractError("no_surface")
    seen_surfaces = set()
    for surface in surfaces:
        if surface["surfaceId"] in seen_surfaces:
            raise ContractError("duplicate_surface")
        seen_surfaces.add(surface["surfaceId"])
        ids = [c["id"] for c in surface["components"]]
        if len(ids) != len(set(ids)):
            raise ContractError("duplicate_component")


def normalize(generated: dict) -> dict:
    """The gateway writes the lifecycle: create then update per surface, fixed version/catalog."""
    messages = []
    for surface in generated["surfaces"]:
        components = []
        for c in surface["components"]:
            out = {"id": c["id"], "component": c["component"]}
            for key in ("title", "subtitle", "action", "voicePhrase"):
                if c.get(key):
                    out[key] = c[key]
            if c.get("children"):
                out["children"] = list(c["children"])
            components.append(out)
        sid = surface["surfaceId"]
        messages.append({"version": VERSION,
                         "createSurface": {"surfaceId": sid, "catalogId": CATALOG_ID}})
        messages.append({"version": VERSION,
                         "updateComponents": {"surfaceId": sid, "components": components}})
    output = {"messages": messages}
    if generated.get("assistantText"):
        output["assistantText"] = generated["assistantText"]
    return output


def _clip(text: str, limit: int = VALUE_LIMIT) -> str:
    text = "".join(ch for ch in " ".join(str(text).split()) if ch.isprintable())
    if _utf16_len(text) <= limit:
        return text
    while _utf16_len(text) > limit - 1:
        text = text[:-1]
    return text.rstrip() + "…"


def _host(url: str) -> str | None:
    try:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ""
        host = host.encode("idna").decode("ascii")
    except (ValueError, UnicodeError):
        return None
    if parts.scheme not in ("http", "https"):
        return None
    host = host.removeprefix("www.")
    return host if len(host) <= 253 and _HOST.match(host) else None


def source_rows(citations) -> list[tuple[str, str]]:
    """(title, subtitle) per cited page, at most SOURCE_ROWS: each row once, new hosts first."""
    rows, seen = [], set()
    for url, title in citations:
        host = _host(url) if isinstance(url, str) else None
        if host is None:
            continue
        row = (_clip(SOURCE_PREFIX + host), _clip(title or ""))
        if row not in seen:
            seen.add(row)
            rows.append((host, row))
    hosts, first, rest = set(), [], []
    for host, row in rows:
        (rest if host in hosts else first).append(row)
        hosts.add(host)
    return (first + rest)[:SOURCE_ROWS]


def add_sources(output: dict, citations) -> int:
    """Appends source Text rows as the root's last children (so a content limit cuts them first)
    of the first surface with a container root. Returns how many were added."""
    rows = source_rows(citations)
    for message in output["messages"] if rows else []:
        components = message.get("updateComponents", {}).get("components", [])
        root = next((c for c in components if c["id"] == "root"), None)
        if root is None or root["component"] not in ("Column", "List", "Card"):
            continue
        taken = {c["id"] for c in components}
        for n, (title, subtitle) in enumerate(rows, 1):
            cid, suffix = f"source_{n}", 1
            while cid in taken:
                suffix += 1
                cid = f"source_{n}_{suffix}"
            taken.add(cid)
            row = {"id": cid, "component": "Text", "title": title}
            if subtitle:
                row["subtitle"] = subtitle
            components.append(row)
            root["children"] = root.get("children", []) + [cid]
        return len(rows)
    return 0


def envelope(output: dict, provider: str, session_id: str, turn_id: str,
             source: str = "live") -> dict:
    """``source`` tells the vehicle whether this turn's vendor call produced the output."""
    if source not in SOURCES:
        raise ValueError(f"source must be one of {SOURCES}")
    env = {"schema": SCHEMA_ID, "provider": provider, "sessionId": session_id,
           "turnId": turn_id, "source": source, "messages": output["messages"]}
    if "assistantText" in output:
        env["assistantText"] = output["assistantText"]
    return env


def load_gateway_schema() -> dict:
    return json.loads(GATEWAY_SCHEMA_PATH.read_text(encoding="utf-8"))


def definition(schema: dict, name: str) -> dict:
    """One $def as a standalone schema: a turn must not validate as an "output", for instance."""
    return {"$schema": schema["$schema"], "$defs": schema["$defs"],
            "$ref": f"#/$defs/{name}"}


def _utf16_len(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def check_bounds(value: Any, wire: bytes) -> None:
    """Mirrors BoundedA2uiJsonDecoder so nothing leaves here that the vehicle would reject."""
    if len(wire) > MAX_BYTES:
        raise ContractError("bounds.bytes")
    nodes = 0
    stack = [(value, 1)]
    while stack:
        node, depth = stack.pop()
        nodes += 1
        if depth > MAX_DEPTH:
            raise ContractError("bounds.depth")
        if nodes > MAX_NODES:
            raise ContractError("bounds.nodes")
        if isinstance(node, str):
            if _utf16_len(node) > MAX_STRING_CHARS:
                raise ContractError("bounds.string")
        elif isinstance(node, dict):
            for key, child in node.items():
                if _utf16_len(key) > MAX_STRING_CHARS:
                    raise ContractError("bounds.string")
                stack.append((child, depth + 1))
        elif isinstance(node, list):
            stack.extend((child, depth + 1) for child in node)
    if len(value.get("messages", [])) > MAX_MESSAGES:
        raise ContractError("bounds.messages")


def validate_envelope(env: dict, schema: dict) -> bytes:
    """Full gateway schema (surfaceResponse only) plus vehicle bounds; returns the wire bytes."""
    error = next(iter(_validator(definition(schema, "surfaceResponse")).iter_errors(env)), None)
    if error is not None:
        raise ContractError(f"gateway_schema.{error.validator}")
    wire = compact(env)
    check_bounds(env, wire)
    return wire


def request_kind(request: Any, schema: dict) -> str:
    """'turn' or 'actionResult', or raises ContractError for anything else."""
    for kind in ("turn", "actionResult"):
        if _validator(definition(schema, kind)).is_valid(request):
            return kind
    raise ContractError("request_schema")


def error_envelope(code: str, detail: str | None = None, provider: str | None = None,
                   session_id: str | None = None, turn_id: str | None = None) -> dict:
    env: dict[str, Any] = {"schema": SCHEMA_ID}
    if provider in PROVIDERS:
        env["provider"] = provider
    if isinstance(session_id, str) and _ID.match(session_id):
        env["sessionId"] = session_id
    if isinstance(turn_id, str) and _ID.match(turn_id):
        env["turnId"] = turn_id
    env["error"] = {"code": code}
    if detail:
        env["error"]["detail"] = _token(detail)
    return env


def _token(text: str) -> str:
    cleaned = "".join(c if c.isascii() and (c.isalnum() or c in "._:-") else "_" for c in text)
    return cleaned[:64] or "unknown"
