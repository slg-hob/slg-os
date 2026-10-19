#!/usr/bin/env bash
# Generates a throwaway self-signed certificate for https://localhost:8443 (never committed).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .certs
if [ ! -f .certs/localhost.crt ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout .certs/localhost.key -out .certs/localhost.crt \
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  echo "certificate created"
fi
