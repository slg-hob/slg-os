#!/usr/bin/env bash
# Starts the spike stack natively (no Docker daemon in this environment): register remotes, shell-server, nginx.
# Env: CSP_MODE=enforce|report (default enforce)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CSP_MODE="${CSP_MODE:-enforce}"
"$ROOT/scripts/down.sh" >/dev/null 2>&1 || true
mkdir -p "$ROOT/.run"
node "$ROOT/scripts/register.mjs"
nohup node "$ROOT/scripts/shell-server.mjs" > "$ROOT/.run/shell-server.log" 2>&1 &
echo $! > "$ROOT/.run/shell-server.pid"
"$ROOT/scripts/proxy.sh" start
sleep 1
curl -sk -o /dev/null -w "shell: %{http_code}\n" https://localhost:8443/
