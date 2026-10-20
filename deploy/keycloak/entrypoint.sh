#!/bin/bash
# Reads the Docker secret files into the environment variables Keycloak and the realm import expect, then starts Keycloak.
# Secrets are never part of the image, the compose file or the realm file (PRD-0003 SK-15 AC4).
set -euo pipefail

secret() {
  local file="/run/secrets/$1"
  if [ ! -r "$file" ]; then
    echo "missing secret $1 (Secret-Datei fehlt)" >&2
    exit 1
  fi
  tr -d '\n' < "$file"
}

optional_secret() {
  local file="/run/secrets/$1"
  if [ -r "$file" ]; then
    tr -d '\n' < "$file"
  fi
}

export KC_DB_PASSWORD="$(secret kc_db_password)"
export KC_BOOTSTRAP_ADMIN_PASSWORD="$(secret kc_bootstrap_admin_password)"
# Substituted into the realm file at import (${...} placeholders of deploy/keycloak/realm*.json).
export SLG_CORE_CLIENT_SECRET="$(secret slg_core_client_secret)"
export LDAP_BIND_PASSWORD="$(optional_secret ldap_bind_password)"
export REFERENCE_CLIENT_SECRET="$(optional_secret reference_client_secret)"

exec /opt/keycloak/bin/kc.sh "$@"
