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
"""HTTPS dev gateway on 127.0.0.1:8787: POST /v1 (turn, actionResult), GET /healthz.

The emulator reaches it through `adb reverse tcp:8787 tcp:8787`. Logs carry provider, profile,
status, source, latency and size only: never a key, header, prompt or body.
"""

from __future__ import annotations

import argparse
import hmac
import http.server
import pathlib
import ssl
import sys
import time

from . import contract, core

HOST = "127.0.0.1"
DEFAULT_PORT = 8787
DEFAULT_TOKEN = "a2ui-dev-local"  # Matches Host B's debug constant; not a vendor credential.
MAX_REQUEST_BYTES = 64 * 1024
CERT_DIR = core.TOOL_DIR / "certs"


def log(**fields) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(stamp + " " + " ".join(f"{k}={v}" for k, v in fields.items() if v is not None),
          file=sys.stderr, flush=True)


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "a2ui-gateway"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 30

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, contract.compact({"ok": True}))
        else:
            self._error("not_found")

    def do_POST(self):
        if self.path != "/v1":
            return self._error("not_found")
        supplied = self.headers.get("Authorization", "")
        expected = f"Bearer {self.server.token}"
        if not hmac.compare_digest(supplied.encode(), expected.encode()):
            return self._error("unauthorized")
        if not self.headers.get("Content-Type", "").lower().startswith("application/json"):
            return self._error("bad_request", "content_type")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self._error("bad_request", "content_length")
        if length < 0 or length > MAX_REQUEST_BYTES:
            return self._error("too_large")
        try:
            request = contract.parse_json(self.rfile.read(length))
        except contract.ContractError as error:
            return self._error("bad_request", error.detail)
        response = self.server.gateway.handle(request)
        headers = {"X-A2UI-Source": response.source}
        for name, value in (("X-A2UI-Output-Model", response.output_model),
                            ("X-A2UI-Output-Profile", response.output_profile),
                            ("X-A2UI-Live-Error", response.live_error)):
            if value and response.status == 200:
                headers[name] = value
        self._send(response.status, response.wire, headers)
        log(provider=response.provider, profile=response.profile, status=response.status,
            source=response.source, ms=response.latency_ms, genMs=response.generation_ms,
            bytes=len(response.wire), output=response.output_model,
            liveError=response.live_error if response.status == 200 else None,
            detail=response.detail)

    def _error(self, code: str, detail: str | None = None):
        body = contract.compact(contract.error_envelope(code, detail))
        status = core._STATUS.get(code, 400)
        self._send(status, body)
        log(status=status, error=code, detail=detail)

    def _send(self, status: int, payload: bytes, headers: dict | None = None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(payload)

    def log_request(self, code="-", size="-"):
        pass  # The default line is replaced by log() above.

    def log_error(self, fmt, *args):
        pass  # Can quote request lines; status lines from log() are enough.


class GatewayServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, gateway: core.Gateway, token: str, port: int = DEFAULT_PORT,
                 tls: ssl.SSLContext | None = None):
        super().__init__((HOST, port), Handler)
        self.gateway = gateway
        self.token = token
        if tls is not None:
            # Handshake lazily in the handler thread, so one slow client cannot stall accept().
            self.socket = tls.wrap_socket(self.socket, server_side=True,
                                          do_handshake_on_connect=False)

    def handle_error(self, request, client_address):
        log(error=type(sys.exc_info()[1]).__name__)


def tls_context(cert: pathlib.Path, key: pathlib.Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    return context


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--env-file", type=pathlib.Path, default=contract.ROOT / ".env")
    parser.add_argument("--providers", type=pathlib.Path, default=core.DEFAULT_PROVIDERS)
    parser.add_argument("--cache-dir", type=pathlib.Path, default=core.DEFAULT_CACHE)
    parser.add_argument("--offline", action="store_true",
                        help="serve cached outputs only; never call a vendor")
    args = parser.parse_args(argv)

    cert, key = CERT_DIR / "server.pem", CERT_DIR / "server.key"
    if not cert.is_file() or not key.is_file():
        print("no dev certificate: run tools/a2ui-gateway/gen-dev-ca.sh", file=sys.stderr)
        return 2
    config = core.load_config(args.providers)
    env = core.load_env(args.env_file, core.env_names(config))
    gateway = core.Gateway(config, env, args.cache_dir, args.offline)
    server = GatewayServer(gateway, env.get(core.GATEWAY_TOKEN_ENV) or DEFAULT_TOKEN, args.port,
                           tls_context(cert, key))
    keyed = [p for p in contract.PROVIDERS if gateway.has_key(p)]
    log(listening=f"https://{HOST}:{args.port}/v1", offline=args.offline,
        keys=",".join(keyed) or "none")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        gateway.close()
    return 0
