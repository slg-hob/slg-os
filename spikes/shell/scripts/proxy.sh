#!/usr/bin/env bash
# Usage: scripts/proxy.sh start|stop|reload   (env CSP_MODE=enforce|report, default report)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/.run/tmp"
"$ROOT/scripts/gen-cert.sh"
CSP="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; frame-src 'self'; form-action 'self'; worker-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'self'"
HDR="Content-Security-Policy"
[ "${CSP_MODE:-report}" = "report" ] && HDR="Content-Security-Policy-Report-Only"
sed -e "s#@ROOT@#$ROOT#g" -e "s#@CSP_HEADER@#$HDR#g" -e "s#@CSP@#$CSP#g" "$ROOT/proxy/nginx.conf.tpl" > "$ROOT/.run/nginx.conf"
case "${1:-start}" in
  start) nginx -c "$ROOT/.run/nginx.conf" ;;
  stop) nginx -c "$ROOT/.run/nginx.conf" -s stop ;;
  reload) nginx -c "$ROOT/.run/nginx.conf" -s reload ;;
esac
