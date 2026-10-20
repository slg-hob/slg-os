#!/usr/bin/env bash
# Generates the secret files for development and CI under deploy/.generated/secrets and the test credentials in
# deploy/.generated/e2e.env. Every call creates new random values; nothing here is committed or reused anywhere else
# (PRD-0003 section 8). Production uses secrets provided by the operator, see docs/operations/configuration.md.
#
# The files are world-readable inside a private directory so that containers with different user ids can read them;
# a production host uses mode 0400 owned by the container user.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/.generated"
secrets="$out/secrets"
if [ -e "$secrets/pg_superuser" ] && [ "${1:-}" != "--force" ]; then
  echo "Secrets already exist in $secrets." >&2
  echo "New values would no longer match the roles and the realm inside an existing database volume." >&2
  echo "Run 'docker compose down -v' first and call this script with --force." >&2
  exit 1
fi
mkdir -p "$secrets"
chmod 0755 "$out" "$secrets"

random() { openssl rand -hex "${1:-24}"; }

write() {
  local name="$1" value="$2"
  printf '%s' "$value" > "$secrets/$name"
  chmod 0444 "$secrets/$name"
}

write pg_superuser "$(random)"
write db_app_password "$(random)"
write db_migrator_password "$(random)"
write kc_db_password "$(random)"
write kc_bootstrap_admin_password "$(random)"
write slg_core_client_secret "$(random)"
write ldap_bind_password "$(random)"
write reference_client_secret "$(random)"
write session_vault_keys "k1=$(openssl rand -base64 32)"
write session_csrf_key "$(random 32)"

umask 077
printf 'E2E_USER_PASSWORD=%s\n' "$(random 12)" > "$out/e2e.env"
chmod 0644 "$out/e2e.env"
echo "secrets written to $secrets, test credentials to $out/e2e.env"
