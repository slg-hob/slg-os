#!/usr/bin/env bash
# Usage: scripts/keycloak.sh start|stop   (native Keycloak; no Docker daemon in this environment)
# Test passwords are generated per run into .run/secrets (never committed).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KC="$ROOT/keycloak/dist/keycloak-26.8.1"
mkdir -p "$ROOT/.run/secrets" "$KC/data/import"
case "${1:-start}" in
  start)
    for u in ALICE BOB ADMIN; do
      [ -f "$ROOT/.run/secrets/$u" ] || openssl rand -base64 18 | tr -d '/+=' > "$ROOT/.run/secrets/$u"
    done
    sed -e "s#@ALICE_PW@#$(cat "$ROOT/.run/secrets/ALICE")#" -e "s#@BOB_PW@#$(cat "$ROOT/.run/secrets/BOB")#" \
      "$ROOT/keycloak/realm.template.json" > "$KC/data/import/slg-realm.json"
    export KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$ROOT/.run/secrets/ADMIN")"
    nohup "$KC/bin/kc.sh" start-dev --http-port=8080 --http-host=127.0.0.1 --http-relative-path=/auth \
      --hostname=https://localhost:8443/auth --hostname-backchannel-dynamic=true --proxy-headers=xforwarded \
      --import-realm --features=token-exchange \
      > "$ROOT/.run/keycloak.log" 2>&1 &
    echo $! > "$ROOT/.run/keycloak.pid" ;;
  stop) [ -f "$ROOT/.run/keycloak.pid" ] && kill "$(cat "$ROOT/.run/keycloak.pid")" 2>/dev/null || true ;;
esac
