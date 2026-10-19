#!/usr/bin/env bash
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.run/shell-server.pid" ] && kill "$(cat "$ROOT/.run/shell-server.pid")" 2>/dev/null
[ -f "$ROOT/.run/svc.pids" ] && xargs kill < "$ROOT/.run/svc.pids" 2>/dev/null
[ -f "$ROOT/.run/nginx.pid" ] && nginx -c "$ROOT/.run/nginx.conf" -s stop 2>/dev/null
exit 0
