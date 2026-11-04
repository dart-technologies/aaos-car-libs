#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Offline tests: python3 -m unittest discover -s tools/a2ui-gateway -p 'test_*.py'"""

from __future__ import annotations

import contextlib
import copy
import http.client
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from a2ui_gateway import adapters, contract, core, server  # noqa: E402

KEY = "test-key-not-a-vendor-credential"
GOOD = {"assistantText": "DEMO coffee stop found.", "surfaces": [{"surfaceId": "coffee", "components": [
    {"id": "root", "component": "Column", "title": "", "subtitle": "", "action": "",
     "voicePhrase": "", "children": ["name", "go", "clip"]},
    {"id": "name", "component": "Text", "title": "DEMO cafe", "subtitle": "0.4 mi", "action": "",
     "voicePhrase": "", "children": []},
    {"id": "go", "component": "Button", "title": "Navigate", "subtitle": "", "action": "navigate",
     "voicePhrase": "go there", "children": []},
    {"id": "clip", "component": "Video", "title": "Tour", "subtitle": "", "action": "",
     "voicePhrase": "", "children": []}]}]}


def turn(provider="claude", profile="quality_cached", text="find coffee", turn_id="t1"):
    return {"schema": "a2ui.gateway.v1", "type": "turn", "provider": provider,
            "executionProfile": profile, "sessionId": "s1", "turnId": turn_id,
            "inputText": text, "capabilities": {"versions": ["v0.9"], "catalogs": ["automotive"]},
            "display": {"id": 0, "activeRestrictions": 0, "trustworthy": True}}


class FakeVendor:
    def __init__(self, output=GOOD, delay=0.0, error=None):
        self.output, self.delay, self.error, self.calls = output, delay, error, []

    def __call__(self, adapter, base_url, model, effort, max_tokens, key, system, user, schema,
                 timeout_s, vendor_fallback=False):
        self.calls.append({"adapter": adapter, "model": model, "effort": effort, "key": key,
                           "user": user, "schema": schema})
        time.sleep(self.delay)
        if self.error:
            raise self.error
        text = self.output if isinstance(self.output, str) else json.dumps(self.output)
        return adapters.Completion(text, model)


class GatewayCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.config = core.load_config()
        self.config["profiles"]["realtime_low_latency"]["deadlineMs"] = 100

    def tearDown(self):
        self.tmp.cleanup()

    def gateway(self, vendor, env=None, offline=False, cli=None):
        # which= is explicit so a machine with the claude CLI installed behaves like one without.
        gw = core.Gateway(self.config, {"ANTHROPIC_API_KEY": KEY} if env is None else env,
                          pathlib.Path(self.tmp.name), offline, complete=vendor,
                          which=lambda command: cli)
        self.addCleanup(gw.close)
        return gw


class ContractTest(unittest.TestCase):
    def test_generation_schema_uses_only_keywords_every_vendor_enforces(self):
        def walk(node):
            if isinstance(node, dict):
                self.assertLessEqual(set(node) - {"properties"}, contract.PORTABLE_KEYWORDS)
                if node.get("type") == "object":
                    self.assertIs(node["additionalProperties"], False)
                    self.assertEqual(set(node["required"]), set(node["properties"]))
                for key, child in node.items():
                    walk(child) if key != "properties" else [walk(v) for v in child.values()]
        walk(contract.GENERATION_SCHEMA)

    def test_gateway_writes_lifecycle_and_identity(self):
        contract.validate_generated(GOOD)
        env = contract.envelope(contract.normalize(GOOD), "muse", "s1", "t1")
        contract.validate_envelope(env, contract.load_gateway_schema())
        self.assertEqual([next(k for k in m if k != "version") for m in env["messages"]],
                         ["createSurface", "updateComponents"])
        components = env["messages"][1]["updateComponents"]["components"]
        self.assertEqual(components[1], {"id": "name", "component": "Text",
                                         "title": "DEMO cafe", "subtitle": "0.4 mi"})

    def test_rejects_what_the_vehicle_would_reject(self):
        schema = contract.load_gateway_schema()
        cases = {"duplicate_key": '{"surfaces": [], "surfaces": []}', "not_json": "{"}
        for detail, text in cases.items():
            with self.assertRaises(contract.ContractError) as raised:
                contract.parse_json(text)
            self.assertEqual(raised.exception.detail, detail)
        unknown = copy.deepcopy(GOOD)
        unknown["surfaces"][0]["components"][1]["component"] = "WebView"
        empty = {"assistantText": "", "surfaces": []}
        twice = copy.deepcopy(GOOD)
        twice["surfaces"][0]["components"].append(copy.deepcopy(GOOD["surfaces"][0]["components"][1]))
        for bad, detail in ((unknown, "generation_schema.enum"), (empty, "no_surface"),
                            (twice, "duplicate_component")):
            with self.assertRaises(contract.ContractError) as raised:
                contract.validate_generated(bad)
            self.assertEqual(raised.exception.detail, detail)
        long = copy.deepcopy(GOOD)
        long["surfaces"][0]["components"][1]["title"] = "x" * 4097
        env = contract.envelope(contract.normalize(long), "claude", "s", "t")
        with self.assertRaises(contract.ContractError) as raised:
            contract.validate_envelope(env, schema)
        self.assertEqual(raised.exception.detail, "bounds.string")
        bad_id = copy.deepcopy(GOOD)
        bad_id["surfaces"][0]["surfaceId"] = "has space"
        env = contract.envelope(contract.normalize(bad_id), "claude", "s", "t")
        with self.assertRaises(contract.ContractError):
            contract.validate_envelope(env, schema)

    def test_error_envelopes_validate_and_never_carry_vendor_text(self):
        schema = contract.load_gateway_schema()
        import jsonschema
        env = contract.error_envelope("upstream", "http_400 says: echo of prompt", "grok",
                                      "s1", "bad id")
        jsonschema.validate(env, contract.definition(schema, "gatewayError"))
        self.assertNotIn(" ", env["error"]["detail"])
        self.assertNotIn("turnId", env)


class ConfigTest(unittest.TestCase):
    def test_only_named_variables_reach_the_gateway(self):
        config = core.load_config()
        with tempfile.TemporaryDirectory() as tmp:
            dotenv = pathlib.Path(tmp) / ".env"
            dotenv.write_text("XAI_API_KEY='from-file'\nELEVENLABS_API_KEY=x\n")
            with mock.patch.dict("os.environ", {"OPENAI_API_KEY": "from-env",
                                                "MODEL_API_KEY": "generic", "HOME": "/h"}):
                env = core.load_env(dotenv, core.env_names(config))
        self.assertEqual(env, {"XAI_API_KEY": "from-file", "OPENAI_API_KEY": "from-env"})

    def test_policy_is_uniform_and_meta_has_no_generic_key(self):
        config = core.load_config()
        self.assertIs(config["policy"]["vendorFallback"], False)
        self.assertEqual(config["providers"]["muse"]["keyEnv"], ["META_API_KEY"])
        for name, provider in config["providers"].items():
            self.assertTrue("verifiedAt" in provider or "no vendor page" in provider["source"],
                            name)


class ProfileTest(GatewayCase):
    def test_quality_cached_generates_once_then_replays_exactly(self):
        vendor = FakeVendor()
        gw = self.gateway(vendor)
        first = gw.handle(turn())
        second = gw.handle(turn(turn_id="t2"))
        self.assertEqual((first.status, first.source), (200, "live"))
        self.assertEqual((second.status, second.source), (200, "cache"))
        self.assertEqual(len(vendor.calls), 1)
        self.assertEqual(first.body["messages"], second.body["messages"])
        self.assertEqual(second.body["turnId"], "t2")
        self.assertEqual(vendor.calls[0]["model"], "claude-opus-5-5")

    def test_realtime_serves_cache_after_deadline_and_refreshes_in_background(self):
        self.gateway(FakeVendor()).handle(turn())  # seeds quality_cached
        slow = FakeVendor(delay=0.4)
        gw = self.gateway(slow)
        started = time.monotonic()
        response = gw.handle(turn(profile="realtime_low_latency"))
        self.assertEqual((response.status, response.source), (200, "fallback"))
        self.assertLess(time.monotonic() - started, 0.35)
        # Labelled: whose output, and why the live call did not answer.
        self.assertEqual(response.body["source"], "fallback")
        self.assertEqual((response.output_profile, response.output_model, response.live_error),
                         ("quality_cached", "claude-opus-5-5", "deadline"))
        sha = gw.cache_key("claude", "realtime_low_latency",
                           *contract.build_prompt("find coffee", turn()["display"]))
        for _ in range(50):
            if gw.cache.get("claude", "realtime_low_latency", sha):
                break
            time.sleep(0.02)
        self.assertIsNotNone(gw.cache.get("claude", "realtime_low_latency", sha))
        self.assertEqual(slow.calls[0]["model"], "claude-haiku-5-5")

    def test_realtime_without_cache_waits_for_the_live_answer(self):
        response = self.gateway(FakeVendor(delay=0.2)).handle(turn(profile="realtime_low_latency"))
        self.assertEqual((response.status, response.source), (200, "live"))

    def test_vendor_failure_falls_back_or_returns_token_only_error(self):
        failing = FakeVendor(error=adapters.AdapterError("upstream", "http_500.overloaded"))
        response = self.gateway(failing).handle(turn(profile="realtime_low_latency"))
        self.assertEqual(response.status, 502)
        self.assertEqual(response.body["error"], {"code": "upstream",
                                                  "detail": "http_500.overloaded"})
        self.gateway(FakeVendor()).handle(turn())
        response = self.gateway(failing).handle(turn(profile="realtime_low_latency"))
        self.assertEqual((response.status, response.source), (200, "fallback"))
        self.assertEqual(response.live_error, "upstream:http_500.overloaded")

    def test_the_cache_key_carries_the_model(self):
        system, user = contract.build_prompt("find coffee", turn()["display"])
        self.assertNotEqual(contract.cache_key("a", system, user),
                            contract.cache_key("b", system, user))
        vendor = FakeVendor()
        self.gateway(vendor).handle(turn())
        self.config["providers"]["claude"]["profiles"]["quality_cached"]["model"] = "claude-new"
        response = self.gateway(vendor).handle(turn())
        self.assertEqual((response.source, len(vendor.calls)), ("live", 2))

    def test_live_and_cache_say_so_in_the_envelope(self):
        gw = self.gateway(FakeVendor())
        self.assertEqual(gw.handle(turn()).body["source"], "live")
        self.assertEqual(gw.handle(turn()).body["source"], "cache")

    def test_invalid_vendor_output_is_never_returned_or_cached(self):
        gw = self.gateway(FakeVendor(output={"surfaces": "nope"}))
        response = gw.handle(turn())
        self.assertEqual((response.status, response.body["error"]["code"]),
                         (502, "invalid_output"))
        self.assertEqual(list(pathlib.Path(self.tmp.name).rglob("*.json")), [])

    def test_unexpected_failure_is_an_internal_envelope(self):
        response = self.gateway(FakeVendor(error=RuntimeError("vendor said: secret"))).handle(
            turn())
        self.assertEqual((response.status, response.body["error"]),
                         (500, {"code": "internal", "detail": "RuntimeError"}))

    def test_missing_key_offline_miss_bad_request_and_ack(self):
        gw = self.gateway(FakeVendor(), env={})
        self.assertEqual(gw.handle(turn()).body["error"]["code"], "provider_unconfigured")
        offline = self.gateway(FakeVendor(), offline=True)
        self.assertEqual(offline.handle(turn()).status, 503)
        self.assertEqual(gw.handle(turn(provider="llama")).status, 400)
        ack = gw.handle({"schema": "a2ui.gateway.v1", "type": "actionResult", "provider": "muse",
                         "sessionId": "s", "turnId": "t", "surfaceId": "x", "componentId": "b",
                         "actionId": "play", "outcome": "ALLOWED"})
        self.assertEqual((ack.status, ack.body["ack"], ack.body["provider"]),
                         (200, "actionResult", "muse"))


class SubscriptionCliTest(GatewayCase):
    def test_without_an_api_key_a_logged_in_cli_answers_and_says_so(self):
        vendor = FakeVendor()
        gw = self.gateway(vendor, env={}, cli="/usr/local/bin/claude")
        self.assertEqual(gw.transport("claude"), "cli")
        self.assertIsNone(gw.transport("muse"))  # no CLI configured: still unconfigured
        response = gw.handle(turn())
        self.assertEqual((response.status, response.source), (200, "live"))
        self.assertEqual(vendor.calls[0]["adapter"], "claude_code_cli")
        entry = next(pathlib.Path(self.tmp.name).rglob("*.json"))
        self.assertEqual(json.loads(entry.read_text())["transport"], "cli")

    def test_an_api_key_wins_over_the_cli(self):
        vendor = FakeVendor()
        gw = self.gateway(vendor, cli="/usr/local/bin/claude")
        self.assertEqual(gw.transport("claude"), "api")
        gw.handle(turn())
        self.assertEqual((vendor.calls[0]["adapter"], vendor.calls[0]["key"]),
                         ("anthropic_messages", KEY))


class FakeCli:
    """Real adapters against a fake CLI executable that records its argv, cwd and directory."""

    def fake_cli(self, body: str) -> pathlib.Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        cli = root / "claude"
        cli.write_text("#!/usr/bin/env python3\nimport json, os, sys, time\n"
                       f"open({str(root / 'argv.json')!r}, 'w').write("
                       "json.dumps({'argv': sys.argv[1:], 'cwd': os.getcwd(), "
                       "'files': os.listdir('.')}))\n" + body)
        cli.chmod(0o755)
        return cli


class ClaudeCodeCliAdapterTest(FakeCli, unittest.TestCase):
    def call(self, cli, timeout_s=10):
        return adapters.complete("claude_code_cli", str(cli), "claude-sonnet-5-5", "low", 100,
                                 "\0cli", "sys", "usr", contract.GENERATION_SCHEMA, timeout_s)

    def test_isolated_flags_and_structured_output(self):
        cli = self.fake_cli("print(json.dumps({'is_error': False, 'subtype': 'success', "
                            "'structured_output': {'ok': 1}, "
                            "'modelUsage': {'claude-sonnet-5-5': {}}}))\n")
        result = self.call(cli)
        self.assertEqual((json.loads(result.text), result.served_model),
                         ({"ok": 1}, "claude-sonnet-5-5"))
        seen = json.loads((cli.parent / "argv.json").read_text())
        argv = seen["argv"]
        for flag in ("-p", "--strict-mcp-config", "--disable-slash-commands",
                     "--no-session-persistence"):
            self.assertIn(flag, argv)
        self.assertNotIn("--bare", argv)  # bare mode ignores the subscription login
        self.assertEqual(argv[argv.index("--tools") + 1], "")
        self.assertEqual(argv[argv.index("--model") + 1], "claude-sonnet-5-5")
        self.assertEqual(argv[argv.index("--system-prompt") + 1], "sys")
        self.assertEqual(argv[-1], "usr")
        self.assertEqual(json.loads(argv[argv.index("--json-schema") + 1]),
                         contract.GENERATION_SCHEMA)
        self.assertEqual(seen["files"], [])  # empty cwd: no project CLAUDE.md to discover
        self.assertNotEqual(pathlib.Path(seen["cwd"]).resolve(), pathlib.Path.cwd().resolve())

    def test_failures_are_tokens_only(self):
        cases = [
            ("print(json.dumps({'is_error': True, 'subtype': 'error_max_turns', "
             "'result': 'secret prompt text'}))\n", "upstream:error_max_turns"),
            ("print('not json: secret'); sys.exit(3)\n", "upstream:cli_exit_3"),
            ("print(json.dumps({'is_error': False, 'result': 'x'}))\n",
             "upstream:no_structured_output"),
            ("time.sleep(5)\n", "timeout"),
        ]
        for body, expected in cases:
            with self.subTest(expected=expected):
                with self.assertRaises(adapters.AdapterError) as raised:
                    self.call(self.fake_cli(body), timeout_s=1)
                self.assertEqual(str(raised.exception), expected)
                self.assertNotIn("secret", str(raised.exception))


class OtherCliAdapterTest(FakeCli, unittest.TestCase):
    def run_cli(self, adapter, body, model="m-1"):
        cli = self.fake_cli(body)
        result = adapters.complete(adapter, str(cli), model, "high", 100, "\0cli", "SYS", "USR",
                                   contract.GENERATION_SCHEMA, 10)
        return result, json.loads((cli.parent / "argv.json").read_text())["argv"]

    def test_codex_exec_is_read_only_ephemeral_and_schema_enforced(self):
        result, argv = self.run_cli("codex_exec_cli", (
            "out = sys.argv[sys.argv.index('-o') + 1]\n"
            "schema = json.load(open(sys.argv[sys.argv.index('--output-schema') + 1]))\n"
            "assert schema['type'] == 'object'\n"
            "open(out, 'w').write(json.dumps({'ok': 1}))\n"
            "sys.stderr.write('model: gpt-6-luna\\n')\n"), model="gpt-6-luna")
        self.assertEqual((json.loads(result.text), result.served_model), ({"ok": 1}, "gpt-6-luna"))
        self.assertEqual(argv[0], "exec")
        for flag in ("--ephemeral", "--skip-git-repo-check"):
            self.assertIn(flag, argv)
        self.assertEqual(argv[argv.index("-s") + 1], "read-only")
        self.assertIn("model_reasoning_effort=high", argv)
        self.assertLess(argv[-1].index("SYS"), argv[-1].index("USR"))  # no system flag: prepended

    def test_grok_single_turn_without_tools_plan_or_subagents(self):
        result, argv = self.run_cli("grok_cli", (
            "print(json.dumps({'structuredOutput': {'ok': 1}, "
            "'modelUsage': {'grok-4.7': {}}}))\n"), model="grok-4.7")
        self.assertEqual((json.loads(result.text), result.served_model), ({"ok": 1}, "grok-4.7"))
        self.assertEqual(argv[argv.index("-p") + 1], "USR")
        self.assertEqual(argv[argv.index("--system-prompt") + 1], "SYS")
        self.assertEqual(argv[argv.index("--tools") + 1], "")
        for flag in ("--no-plan", "--no-subagents"):
            self.assertIn(flag, argv)

    def test_antigravity_never_sends_an_empty_enum_and_restores_no_action(self):
        result, argv = self.run_cli("antigravity_cli", (
            "schema = sys.argv[sys.argv.index('--json-schema') + 1]\n"
            "assert '\"\"' not in schema, 'empty enum value reached agy'\n"
            "print(json.dumps({'status': 'SUCCESS', 'error': '', 'response': json.dumps("
            "{'surfaces': [{'components': [{'id': 'a', 'action': 'none'}, "
            "{'id': 'b', 'action': 'play'}]}]})}))\n"), model="gemini-3.8-flash")
        actions = [c["action"] for c in json.loads(result.text)["surfaces"][0]["components"]]
        self.assertEqual(actions, ["", "play"])

    def test_antigravity_parses_a_json_string_response_and_reports_sign_in_failure(self):
        result, argv = self.run_cli("antigravity_cli", (
            "print(json.dumps({'status': 'success', 'response': json.dumps({'ok': 1})}))\n"),
            model="gemini-3.8-flash")
        self.assertEqual(json.loads(result.text), {"ok": 1})
        self.assertEqual(argv[argv.index("--model") + 1], "gemini-3.8-flash")
        fenced, _ = self.run_cli("antigravity_cli", (
            "print(json.dumps({'status': 'SUCCESS', 'response': "
            "'```json\\n' + json.dumps({'ok': 2}) + '\\n```'}))\n"))
        self.assertEqual(json.loads(fenced.text), {"ok": 2})
        prose, _ = self.run_cli("antigravity_cli", (
            "print(json.dumps({'status': 'SUCCESS', 'response': "
            "'Here is the surface: ' + json.dumps({'ok': 3}) + ' Done.'}))\n"))
        self.assertEqual(json.loads(prose.text), {"ok": 3})
        for error, expected in [("authentication failed or timed out", "not_signed_in"),
                                ("Individual quota reached. Resets in 2h.", "quota_exhausted"),
                                ("model x is not recognized as a known model", "unknown_model")]:
            with self.subTest(expected=expected), \
                    self.assertRaises(adapters.AdapterError) as raised:
                self.run_cli("antigravity_cli", (
                    f"print(json.dumps({{'status': 'ERROR', 'error': {error!r}}}));"
                    "sys.exit(1)\n"))
            self.assertEqual(str(raised.exception), f"upstream:{expected}")


class CliProfileOverrideTest(GatewayCase):
    def test_a_cli_profile_overrides_the_api_model(self):
        vendor = FakeVendor()
        gw = self.gateway(vendor, env={}, cli="/usr/local/bin/grok")
        self.assertEqual(gw.model("grok", "realtime_low_latency"), "grok-4.7-build-fast")
        self.assertEqual(gw.model("grok", "quality_cached"), "grok-4.7")  # no override: API id
        gw.handle(turn(provider="grok", profile="realtime_low_latency"))
        self.assertEqual((vendor.calls[0]["adapter"], vendor.calls[0]["model"]),
                         ("grok_cli", "grok-4.7-build-fast"))
        api = self.gateway(FakeVendor(), env={"XAI_API_KEY": KEY}, cli="/usr/local/bin/grok")
        self.assertEqual(api.model("grok", "realtime_low_latency"), "grok-4.20-0309-non-reasoning")


class HttpTest(GatewayCase):
    def test_auth_size_and_log_hygiene(self):
        gw = self.gateway(FakeVendor())
        httpd = server.GatewayServer(gw, "tok", port=0)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        port = httpd.server_address[1]

        def post(body: bytes, token="tok", path="/v1"):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            conn.request("POST", path, body, {"Authorization": f"Bearer {token}",
                                              "Content-Type": "application/json"})
            reply = conn.getresponse()
            data = reply.read()
            conn.close()
            return reply.status, json.loads(data)

        secret_text = "find the secret espresso bar"
        log = io.StringIO()
        with contextlib.redirect_stderr(log):
            self.assertEqual(post(b"{}", token="wrong")[0], 401)
            self.assertEqual(post(b"{}", path="/v2")[0], 404)
            self.assertEqual(post(b"x" * (server.MAX_REQUEST_BYTES + 1))[0], 413)
            status, body = post(json.dumps(turn(text=secret_text)).encode())
        self.assertEqual(status, 200)
        self.assertEqual(body["provider"], "claude")
        for leaked in (secret_text, KEY, "DEMO cafe", "Bearer"):
            self.assertNotIn(leaked, log.getvalue())
        self.assertIn("status=200", log.getvalue())


class AdapterShapeTest(unittest.TestCase):
    def capture(self, reply):
        sent = {}

        def fake_post(url, headers, body, timeout_s):
            sent.update(url=url, headers=headers, body=body)
            return reply
        return sent, mock.patch.object(adapters, "_post", side_effect=fake_post)

    def run_adapter(self, adapter, base, reply, effort="low", vendor_fallback=False):
        sent, patch = self.capture(reply)
        with patch:
            result = adapters.complete(adapter, base, "model-1", effort, 100, KEY, "sys", "usr",
                                       contract.GENERATION_SCHEMA, 5, vendor_fallback)
        return sent, result

    def test_anthropic_uses_structured_output_and_effort_without_vendor_fallback(self):
        sent, result = self.run_adapter("anthropic_messages", "https://api.anthropic.com/v1", {
            "model": "model-1", "stop_reason": "end_turn",
            "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": "{}"}]})
        self.assertEqual(result.text, "{}")
        body = sent["body"]
        self.assertEqual(body["output_config"]["format"]["type"], "json_schema")
        self.assertEqual(body["output_config"]["effort"], "low")
        self.assertNotIn("fallbacks", body)
        self.assertNotIn("anthropic-beta", sent["headers"])
        sent, _ = self.run_adapter("anthropic_messages", "https://x/v1", {
            "stop_reason": "end_turn", "content": [{"type": "text", "text": "{}"}]},
            vendor_fallback=True)
        self.assertEqual(sent["body"]["fallbacks"], "default")
        self.assertNotIn("thinking", body)
        self.assertNotIn("temperature", body)
        self.assertEqual(sent["headers"]["anthropic-version"], "2026-10-21")
        with self.assertRaises(adapters.AdapterError) as raised:
            self.run_adapter("anthropic_messages", "https://x/v1",
                             {"stop_reason": "refusal", "stop_details": {"category": "cyber"}})
        self.assertEqual((raised.exception.code, raised.exception.detail), ("refusal", "cyber"))

    def test_openai_responses_strict_schema_and_refusal(self):
        sent, result = self.run_adapter("openai_responses", "https://api.openai.com/v1", {
            "status": "completed", "model": "model-1", "output": [
                {"type": "reasoning"},
                {"type": "message", "content": [{"type": "output_text", "text": "{}"}]}]})
        self.assertEqual(result.text, "{}")
        self.assertTrue(sent["body"]["text"]["format"]["strict"])
        self.assertIs(sent["body"]["store"], False)
        with self.assertRaises(adapters.AdapterError) as raised:
            self.run_adapter("openai_responses", "https://x/v1", {
                "status": "incomplete", "incomplete_details": {"reason": "max_output_tokens"}})
        self.assertEqual(raised.exception.code, "truncated")

    def test_gemini_response_schema_and_thinking_level(self):
        sent, result = self.run_adapter("gemini_generate_content", "https://g/v1beta", {
            "modelVersion": "model-1", "candidates": [{"finishReason": "STOP", "content": {
                "parts": [{"text": "ignored", "thought": True}, {"text": "{}"}]}}]})
        self.assertEqual(result.text, "{}")
        self.assertTrue(sent["url"].endswith("/models/model-1:generateContent"))
        config = sent["body"]["generationConfig"]
        self.assertEqual(config["responseMimeType"], "application/json")
        self.assertIn("responseJsonSchema", config)
        self.assertEqual(config["thinkingConfig"], {"thinkingLevel": "LOW"})
        self.assertEqual(sent["headers"], {"x-goog-api-key": KEY})

    def test_openai_compatible_chat_serves_grok_and_muse(self):
        reply = {"model": "model-1", "choices": [{"finish_reason": "stop",
                                                  "message": {"content": "{}"}}]}
        sent, result = self.run_adapter("openai_chat", "https://api.meta.ai/v1", reply)
        self.assertEqual(result.text, "{}")
        self.assertEqual(sent["url"], "https://api.meta.ai/v1/chat/completions")
        self.assertEqual(sent["body"]["response_format"]["type"], "json_schema")
        self.assertEqual(sent["body"]["reasoning_effort"], "low")
        sent, _ = self.run_adapter("openai_chat", "https://api.x.ai/v1", reply, effort=None)
        self.assertNotIn("reasoning_effort", sent["body"])

    def test_transport_refuses_plain_http_and_redirects(self):
        with self.assertRaises(adapters.AdapterError):
            adapters._post("http://api.example/v1", {}, {}, 1)
        self.assertIsNone(adapters._NoRedirect().redirect_request(None, None, 302, "", {}, "x"))


class CompareTest(GatewayCase):
    """scripts/compare-providers.py: what each row says produced it, on the real gateway."""

    def setUp(self):
        super().setUp()
        spec = importlib.util.spec_from_file_location(
            "compare_providers", contract.ROOT / "scripts" / "compare-providers.py")
        self.compare = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.compare)

    def row(self, gw, profile, mode="live"):
        case = {"id": "coffee", "intent": "find coffee"}
        response = gw.handle(self.compare.turn("claude", profile, case))
        return self.compare.row_for("claude", profile, case, mode, gw.model("claude", profile),
                                    response, response.status == 200)

    def test_an_injected_429_is_a_failure_even_when_a_fallback_answers(self):
        vendor = FakeVendor()
        self.row(self.gateway(vendor), "quality_cached")
        gw = self.gateway(self.compare.injecting(vendor, self.config, {"claude": "429"}))
        fallback = self.row(gw, "realtime_low_latency")
        self.assertEqual(len(vendor.calls), 1)
        self.assertEqual((fallback["status"], fallback["source"]), (200, "fallback"))
        self.assertTrue(fallback["failure"] and fallback["fallback"])
        self.assertEqual(fallback["liveError"], "upstream:http_429.injected")
        self.assertEqual(fallback["outputModel"], "claude-opus-5-5")
        self.assertIsNone(fallback["latencyMs"])
        self.assertIsNone(self.compare.model_label(fallback))
        cached = self.row(gw, "quality_cached")
        self.assertFalse(cached["failure"])
        self.assertIsNone(cached["latencyMs"])
        self.assertEqual(self.compare.model_label(cached), "cache:claude-opus-5-5")

    def test_only_a_live_answer_is_timed_and_it_is_named_by_the_vendor(self):
        live = self.row(self.gateway(FakeVendor()), "realtime_low_latency")
        self.assertEqual(live["source"], "live")
        self.assertFalse(live["failure"])
        self.assertIsNotNone(live["latencyMs"])
        self.assertEqual(self.compare.model_label(live), "claude-haiku-5-5")

    def test_a_replay_calls_no_vendor_and_a_miss_is_a_failure(self):
        gw = core.Gateway(self.config, {}, pathlib.Path(self.tmp.name), offline=True,
                          complete=self.compare.never)
        self.addCleanup(gw.close)
        for profile in contract.PROFILES:
            missed = self.row(gw, profile, mode="replay")
            self.assertTrue(missed["failure"])
            self.assertEqual(missed["error"], "cache_miss")


if __name__ == "__main__":
    unittest.main()
