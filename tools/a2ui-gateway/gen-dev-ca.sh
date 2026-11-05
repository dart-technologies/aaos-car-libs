#!/usr/bin/env bash
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
#
# Generates a local-only dev CA and a localhost/127.0.0.1 server certificate for the dev gateway.
# Everything written here is gitignored:
#   tools/a2ui-gateway/certs/{dev-ca.pem,dev-ca.key,server.pem,server.key}
#   a2ui-host-carui/src/debug/devca/res/raw/a2ui_dev_ca.pem   (trusted by debug builds only)
# Re-run with --force to replace an existing CA; rebuild Host B afterwards.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
out="$here/certs"
raw="$repo/a2ui-host-carui/src/debug/devca/res/raw"

if [[ -f "$out/dev-ca.pem" && "${1:-}" != "--force" ]]; then
  echo "dev CA already exists in $out (use --force to replace it)" >&2
  exit 0
fi

umask 077
mkdir -p "$out" "$raw"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cat > "$work/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = A2UI dev gateway CA (local only)
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
cat > "$work/server.cnf" <<'EOF'
[v3_srv]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, IP:127.0.0.1
EOF

openssl ecparam -name prime256v1 -genkey -noout -out "$out/dev-ca.key"
openssl req -x509 -new -sha256 -days 825 -key "$out/dev-ca.key" \
  -config "$work/ca.cnf" -extensions v3_ca -out "$out/dev-ca.pem"

openssl ecparam -name prime256v1 -genkey -noout -out "$out/server.key"
openssl req -new -sha256 -key "$out/server.key" -subj "/CN=localhost" -out "$work/server.csr"
openssl x509 -req -sha256 -days 397 -in "$work/server.csr" \
  -CA "$out/dev-ca.pem" -CAkey "$out/dev-ca.key" -set_serial "0x$(openssl rand -hex 16)" \
  -extfile "$work/server.cnf" -extensions v3_srv -out "$out/server.pem"

chmod 644 "$out/dev-ca.pem" "$out/server.pem"
cp "$out/dev-ca.pem" "$raw/a2ui_dev_ca.pem"
chmod 644 "$raw/a2ui_dev_ca.pem"
openssl verify -CAfile "$out/dev-ca.pem" "$out/server.pem" >/dev/null
echo "dev CA ready: $out (CA copied to Host B's debug resources)"
