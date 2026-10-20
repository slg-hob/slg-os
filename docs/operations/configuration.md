# Configuration of the core / Konfiguration des Core

The core is configured only through environment variables and secret files (PRD-0003 SK-16 AC5). Secrets are never values: the `*_FILE` variables name Docker secret files (mode 0400, owned by the container user). The core refuses to start when a mandatory setting is missing or invalid and names every variable in German and English ("Ungültige Konfiguration / Invalid configuration").

Der Core wird nur über Umgebungsvariablen und Secret-Dateien konfiguriert. Geheimnisse stehen nie als Wert in der Konfiguration: die `*_FILE`-Variablen nennen Docker-Secret-Dateien. Fehlt eine Pflichteinstellung oder ist sie ungültig, startet der Core nicht und nennt jede betroffene Variable auf Deutsch und Englisch.

## Variables / Variablen

| Variable | Default | Meaning / Bedeutung |
|---|---|---|
| `SLG_ENV` | `dev` | `dev`, `test`, `e2e` or `production`. In `production` the core refuses test host names (`*.test`, `localhost`) and the e2e realm. / In `production` verweigert der Core Test-Hostnamen und das E2E-Realm. |
| `SLG_SHELL_BASE_URL` | none, mandatory | Public origin of the shell, for example `https://shell.example.com`, no path. Redirects, cookies and the `Origin` check derive from it, never from the `Host` header. / Öffentlicher Origin der Shell, ohne Pfad. |
| `SLG_INTERNAL_HOSTS` | `core-backend,localhost` | Host names under which the internal network reaches the core (back-channel logout, permission API). `/core/internal/**` answers only for these; the proxy must also answer 404 for the tree. In production `localhost` is refused. / Hostnamen des internen Netzes; in Produktion ist `localhost` nicht erlaubt. |
| `SLG_TRUSTED_PROXY_CIDRS` | empty | Network ranges of the reverse proxy. `X-Forwarded-For` (the right-most address that is not a proxy) and `X-Correlation-Id` count only from there. Mandatory in production: without it all users share the proxy address (rate limit, audit). / Netzbereiche des Reverse Proxy; in Produktion Pflicht. |
| `SLG_BOOTSTRAP_ADMIN_GROUP` | empty | Full path of the AD group that holds `platform-admin` until the permission mapping exists (M3a), for example `/slg/admins`. / Gruppenpfad der Start-Administratoren. |
| `SLG_OIDC_ISSUER` | none, mandatory | Public issuer URL of the Keycloak realm. The core reaches Keycloak through the same URL (network alias). / Öffentliche Issuer-URL des Realms. |
| `SLG_OIDC_CLIENT_ID` | `slg-core` | Confidential client of the core. |
| `SLG_OIDC_CLIENT_SECRET_FILE` | none, mandatory | File with the client secret. / Datei mit dem Client-Secret. |
| `SLG_OIDC_HTTP_TIMEOUT` | `3s` | Timeout of every call to Keycloak. |
| `SLG_SESSION_IDLE_TIMEOUT` | `30m` | Idle timeout, enforced on the server. / Leerlauf-Zeitlimit. |
| `SLG_SESSION_ABSOLUTE_TIMEOUT` | `10h` | Absolute session lifetime. / Absolute Sitzungsdauer. |
| `SLG_SESSION_MAX_PER_USER` | `10` | Sessions per user; the oldest end first. / Sitzungen je Nutzer. |
| `SLG_SESSION_RENEW_MARGIN` | `60s` | Access tokens are renewed this long before they expire. |
| `SLG_SESSION_STEP_UP_MAX_AGE` | `5m` | Maximum age of the authentication for sensitive admin actions. / Höchstalter der Anmeldung für Admin-Aktionen. |
| `SLG_SESSION_VAULT_KEYS_FILE` | none, mandatory | Token vault keys, one `id=base64` line per key (32 bytes each, AES-256). / Schlüsseldatei des Token-Tresors. |
| `SLG_SESSION_VAULT_ACTIVE_KEY_ID` | none, mandatory | Key id for new data. Rotation: add a new line, switch the active id, restart; remove the old key when all sessions that used it ended (absolute timeout). / Aktive Schlüssel-ID. |
| `SLG_SESSION_CSRF_KEY_FILE` | none, mandatory | Server key (at least 32 characters) for CSRF tokens and the signed pre-login cookie. / Serverschlüssel für CSRF-Token. |
| `SLG_DB_URL` | none, mandatory | `jdbc:postgresql://host:5432/platform`. |
| `SLG_DB_APP_USER`, `SLG_DB_APP_PASSWORD_FILE` | none, mandatory | Application role `slg_app`: no DDL, `INSERT` and `SELECT` only on `audit_event`. / Anwendungsrolle. |
| `SLG_DB_MIGRATOR_USER`, `SLG_DB_MIGRATOR_PASSWORD_FILE` | none, mandatory | Migration role `slg_migrator`, used by Flyway only. / Migrationsrolle. |
| `SLG_DB_POOL_SIZE` | `10` | Connection pool of the application role (at least 2). At most half of it can wait for Keycloak at the same time. Plan `max_connections` as containers x pool size plus the migrator. |
| `SLG_DB_MIGRATION_LOCATION` | `classpath:db/migration` | Flyway location; tests point it at broken scripts. Do not change in operation. |
| `SLG_MANAGEMENT_PORT` | `8081` | Internal port for health and metrics; the proxy does not route it. / Interner Port für Health und Metriken. |

## Database roles / Datenbankrollen

The roles `slg_app` and `slg_migrator` exist before the first start (the compose stack creates them from the secrets). `slg_migrator` owns the database and the schema; migrations run as this role at start under a PostgreSQL advisory lock. A failing migration keeps readiness `DOWN`, so an older container keeps serving.

Die Rollen müssen vor dem ersten Start existieren (der Compose-Stack legt sie aus den Secrets an). Eine fehlschlagende Migration hält die Bereitschaft auf `DOWN`; ein älterer Container bleibt im Dienst.

## Health and metrics / Gesundheit und Metriken

On the management port: `/actuator/health/liveness`, `/actuator/health/readiness` (status `DEGRADED` while Keycloak is unreachable, still HTTP 200) and `/actuator/prometheus` (`slg_session_active`, `slg_token_refresh_failures_total`). `/core/api/v1/health` for administrators follows with the admin API (M3a).

## Failure behaviour / Verhalten bei Störungen

| Failure / Störung | Behaviour / Verhalten |
|---|---|
| Keycloak down at start or later / Keycloak nicht erreichbar | The core starts; readiness `DEGRADED`; sign-in shows the "sign-in unavailable" page; sessions stay valid until their cached token expires, then `AUTH_UNAVAILABLE`. |
| PostgreSQL down / PostgreSQL nicht erreichbar | `503 SESSION_STORE_DOWN` for every session request, no unauthenticated fallback; recovery without restart. |
| Migration failed / Migration fehlgeschlagen | Readiness `DOWN`; fix the cause and restart. |
| Refresh response lost / Antwort beim Token-Erneuern verloren | The session ends (the rotating refresh token may be consumed); audit entry `session.terminated` with reason `refresh_lost`. A refused grant (`invalid_grant`) ends it with `refresh_rejected`; any other refusal (for example `invalid_client` after a secret rotation) keeps the sessions and counts as Keycloak unavailable. |
| Token vault key retired / Tresor-Schlüssel entfernt | Sessions whose tokens cannot be decrypted end with reason `vault_unreadable`; sign-out still works. Keep a key until all sessions that used it ended (absolute timeout). |
| Migration failed at start / Migration beim Start fehlgeschlagen | Retried every minute while readiness is `DOWN`. |
