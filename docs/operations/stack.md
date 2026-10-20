# The compose stack / Der Compose-Stack

Status: milestone M2 of the walking skeleton (PRD-0003). The stack has the proxy, Keycloak in production mode, PostgreSQL and the core backend; the reference module follows with M5. / Stand: Meilenstein M2.

## Start for development and CI / Start für Entwicklung und CI

```bash
deploy/scripts/gen-secrets.sh     # random secret files and the test password under deploy/.generated (never committed)
deploy/scripts/test-ca.sh         # throw-away CA and the proxy certificate for shell.slg.test and sso.slg.test (needs openssl and keytool)
echo "127.0.0.1 shell.slg.test sso.slg.test" | sudo tee -a /etc/hosts
set -a; . deploy/.generated/e2e.env; set +a
docker compose -f deploy/compose.yaml -f deploy/compose.e2e.yaml up -d --build --wait
```

Open `https://shell.slg.test/app/` after trusting `deploy/.generated/tls/ca.crt` in your browser or system. Test users `admin`, `user-a`, `user-b` (and the disabled `disabled`) share the password in `deploy/.generated/e2e.env`. Without `compose.e2e.yaml` the stack uses the production realm file and expects a company directory (`LDAP_*` variables); it refuses test host names when `SLG_ENV=production`.

## Services / Dienste

| Service | Role / Aufgabe | Notes / Hinweise |
|---|---|---|
| `proxy` | nginx on 443 (container port 8443), TLS, two host names | Routes `/core/`, `/app/` to the core and only login, logout, token, key and discovery paths of Keycloak; `/core/internal/` is 404. Host names are fixed in `deploy/proxy/nginx.conf` until production names are decided (Q27). |
| `keycloak` | production mode (`start --optimized --import-realm`), PostgreSQL | Realm files: `deploy/keycloak/realm.json` (production, LDAP federation from `LDAP_*`) and `realm-e2e.json` (test users, federation off). Health on the management port 9000. |
| `postgres` | PostgreSQL 17 | `deploy/postgres/init/10-roles.sh` creates `slg_migrator`, `slg_app` and `keycloak` from the secret files on first start. |
| `core-backend` | BFF, sessions, audit | Configuration: [configuration.md](configuration.md). Readiness `DEGRADED` while Keycloak is unreachable. |
| `ldap` | test directory (e2e only) | Present for the federation scenario (M6a), not used by M2. |

## Hardening and exceptions / Härtung und Ausnahmen

Every container drops all capabilities, sets `no-new-privileges`, limits processes, memory and CPU, rotates JSON logs (10 MB x 5) and publishes no port except the proxy on 443. `scripts/ci/check-compose.mjs` asserts this on the merged configuration in CI. Documented exceptions: Keycloak keeps a writable `/opt/keycloak/data` and runs as the image user; the LDAP test image runs as root and has no health check. Secret files in development are mode 0444 inside a private directory; a production host uses mode 0400 owned by the container user.

## Backup / Datensicherung

The session table is not part of any backup; users sign in again after a restore. The backup and restore scripts follow in milestone M6c (PRD-0003 SK-17).
