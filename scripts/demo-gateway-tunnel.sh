#!/usr/bin/env bash
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
#
# Runs the dev gateway on the worker, where the vendor keys are, and forwards it to this Mac:
#   emulator --adb reverse--> Mac 127.0.0.1:8787 --ssh -L--> worker 127.0.0.1:8787 (gateway)
# No key leaves the worker. Only the public dev CA certificate is copied here, never its key.
#
#   scripts/demo-gateway-tunnel.sh ca          # once: CA on the worker, its cert into Host B debug
#   scripts/demo-gateway-tunnel.sh up [ARGS]   # gateway in tmux (ARGS to serve.py, e.g. --offline)
#   scripts/demo-gateway-tunnel.sh status | down
# Then: adb reverse tcp:8787 tcp:8787. Rebuild Host B debug after `ca`.
#
# A2UI_WORKER (ssh host), A2UI_WORKER_REPO (path under the worker's $HOME) override the defaults.
set -euo pipefail

host="${A2UI_WORKER:-dartbot-worker-svc}"
remote="${A2UI_WORKER_REPO:-Documents/GitHub/aaos-car-libs}"
port=8787
session=a2ui-gateway
here="$(cd "$(dirname "$0")/.." && pwd)"
raw="$here/a2ui-host-carui/src/debug/devca/res/raw/a2ui_dev_ca.pem"
local_ca="$here/tools/a2ui-gateway/certs/dev-ca.pem"
ctl="${TMPDIR:-/tmp}/a2ui-gateway-tunnel-$port.sock"
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=10)

on_worker() {  # on_worker ARGS... < script: the script gets the repo as $1, then ARGS
  local args
  args="$(printf ' %q' "$remote" "$@")"  # ssh joins words: quote each for the remote shell
  # shellcheck disable=SC2029  # expanded here on purpose, already quoted
  ssh "${ssh_opts[@]}" "$host" "bash -s --$args"
}

install_ca() {
  on_worker <<'EOF'
set -euo pipefail
cd "$HOME/$1"
tools/a2ui-gateway/gen-dev-ca.sh >&2
EOF
  local fetched
  fetched="$(mktemp)"
  scp -q "${ssh_opts[@]}" "$host:$remote/tools/a2ui-gateway/certs/dev-ca.pem" "$fetched"
  if grep -q "PRIVATE KEY" "$fetched" || ! openssl x509 -in "$fetched" -noout 2>/dev/null; then
    rm -f "$fetched"
    echo "worker dev-ca.pem is not a lone certificate; refusing to install it" >&2
    return 1
  fi
  mkdir -p "$(dirname "$raw")"
  # One bundle: the worker's CA, plus this Mac's own dev CA if it has one (local gateway runs).
  { cat "$fetched"
    if [[ -f "$local_ca" ]] && ! cmp -s "$local_ca" "$fetched"; then cat "$local_ca"; fi
  } > "$raw.tmp"
  chmod 644 "$raw.tmp"
  mv "$raw.tmp" "$raw"
  echo "worker CA $(openssl x509 -in "$fetched" -noout -fingerprint -sha256 | cut -d= -f2)"
  rm -f "$fetched"
  echo "installed: $raw (debug builds only; rebuild Host B debug)"
}

start_gateway() {
  on_worker "$port" "$session" "$*" <<'EOF'
set -euo pipefail
cd "$HOME/$1"
port="$2" session="$3" extra="${4:-}"
if [[ ! -f tools/a2ui-gateway/certs/server.pem ]]; then
  echo "no dev certificate on the worker: run scripts/demo-gateway-tunnel.sh ca" >&2; exit 2
fi
py="$HOME/.venvs/a2ui/bin/python"; [[ -x "$py" ]] || py=python3
mkdir -p build
if ! tmux has-session -t "$session" 2>/dev/null; then
  # serve.py binds 127.0.0.1 only and reads keys from this repo's .env; logs carry no key or body.
  tmux new-session -d -s "$session" -c "$PWD" \
    "$py tools/a2ui-gateway/serve.py --port $port $extra 2>&1 | tee -a build/a2ui-gateway.log"
fi
for _ in $(seq 40); do
  if curl -fsS --max-time 2 --cacert tools/a2ui-gateway/certs/dev-ca.pem \
      "https://127.0.0.1:$port/healthz" >/dev/null 2>&1; then
    echo "worker gateway up (tmux $session)" >&2; exit 0
  fi
  sleep 0.5
done
echo "worker gateway did not become healthy; tmux attach -t $session on the worker" >&2
tmux capture-pane -p -t "$session" 2>/dev/null | tail -5 >&2 || true
exit 1
EOF
}

forward() {
  if ssh -S "$ctl" -O check "$host" 2>/dev/null; then
    echo "forward already open"
  else
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "127.0.0.1:$port is taken on this Mac (a local gateway?); stop it first" >&2
      return 1
    fi
    ssh "${ssh_opts[@]}" -f -N -M -S "$ctl" -o ExitOnForwardFailure=yes \
      -o ServerAliveInterval=30 -L "127.0.0.1:$port:127.0.0.1:$port" "$host"
  fi
  if [[ ! -f "$raw" ]]; then
    echo "forward open, TLS unchecked: no Host B dev CA here (run: $0 ca)" >&2
    return 0
  fi
  # The same trust Host B's debug build gets: the installed bundle, hostname 127.0.0.1.
  curl -fsS --max-time 5 --cacert "$raw" "https://127.0.0.1:$port/healthz" >/dev/null
  echo "https://127.0.0.1:$port/v1 -> $host gateway, verified with the installed CA"
  echo "next: adb reverse tcp:$port tcp:$port"
}

case "${1:-up}" in
  ca) install_ca ;;
  up) shift || true; start_gateway "$@"; forward ;;
  status)
    ssh -S "$ctl" -O check "$host" 2>&1 || true
    on_worker "$session" <<'EOF'
cd "$HOME/$1"
tmux has-session -t "$2" 2>/dev/null && echo "worker gateway session: running" \
  || echo "worker gateway session: stopped"
tail -n 5 build/a2ui-gateway.log 2>/dev/null || true
EOF
    ;;
  down)
    ssh -S "$ctl" -O exit "$host" 2>/dev/null || true
    on_worker "$session" <<'EOF'
tmux kill-session -t "$2" 2>/dev/null || true
echo "worker gateway stopped"
EOF
    ;;
  *) sed -n '5,14p' "$0"; exit 2 ;;
esac
