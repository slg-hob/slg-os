#!/usr/bin/env bash
# Generates a per-run test CA and the proxy certificate for shell.slg.test and sso.slg.test (PRD-0003 SK-18 AC7):
#   deploy/.generated/tls/ca.crt, tls.crt, tls.key   mounted into the proxy and Keycloak
#   deploy/.generated/ca/truststore.p12              mounted into the core JVM (password "changeit", it holds only public certificates)
# The CA key never leaves a temporary directory that is removed on exit. Never use this for real host names.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tls="$root/.generated/tls"
ca="$root/.generated/ca"
mkdir -p "$tls" "$ca"
chmod 0755 "$root/.generated" "$tls" "$ca"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

shell_host="${SLG_SHELL_HOST:-shell.slg.test}"
sso_host="${SLG_SSO_HOST:-sso.slg.test}"

openssl req -x509 -newkey rsa:3072 -nodes -days 3 -subj "/CN=SLG OS test CA" \
  -keyout "$work/ca.key" -out "$tls/ca.crt" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

openssl req -newkey rsa:3072 -nodes -subj "/CN=$shell_host" -keyout "$tls/tls.key" -out "$work/tls.csr" 2>/dev/null
cat > "$work/ext.cnf" <<EOF
subjectAltName=DNS:$shell_host,DNS:$sso_host
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF
openssl x509 -req -in "$work/tls.csr" -CA "$tls/ca.crt" -CAkey "$work/ca.key" -CAcreateserial -days 3 \
  -extfile "$work/ext.cnf" -out "$tls/tls.crt" 2>/dev/null

chmod 0444 "$tls/ca.crt" "$tls/tls.crt" "$tls/tls.key"

rm -f "$ca/truststore.p12"
keytool -importcert -noprompt -alias slg-test-ca -file "$tls/ca.crt" -keystore "$ca/truststore.p12" \
  -storetype PKCS12 -storepass changeit >/dev/null
chmod 0444 "$ca/truststore.p12"
echo "test CA and certificates written to $tls and $ca"
