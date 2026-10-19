# PRD-0003: Walking skeleton (phase 1a)

- Status: Draft
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0001, 0002, 0004, 0005, 0009, 0010 (proposed), 0011 (proposed), 0012. New ADRs written during the skeleton: see section 7.5.
- Related PRDs: PRD-0001 (platform core, approved revision 2; this PRD is its phase 1a and implements a defined slice of it), PRD-0002 (spike) and `docs/spikes/2026-shell-spike-report.md`
- Related: `ROADMAP.md` (phase 1a), `CLAUDE.md` (build commands are added there by the first milestone)

## 1. Problem and goals
PRD-0001 specifies the platform core on paper. Its riskiest parts (backend-for-frontend, gateway, production-mode Keycloak, Module Federation under strict CSP, per-request shell document) were proven only on disposable spike code, in Chromium, with Keycloak in `start-dev` mode and a native nginx. Phase 1a builds the **thinnest end-to-end slice on the real stack, as production code with tests and a CI gate**, so that everything after it (phase 1b capabilities, the design system, first business modules) builds on proven ground.

Goals:
- G1: A user signs in against Keycloak (federating an LDAP directory), sees a desktop, opens two windows of the reference module and signs out, all through the real compose stack with TLS and two host names.
- G2: The core is a backend-for-frontend (ADR-0012): session cookie only, tokens on the server, CSRF protection, gateway to the module backend with an audience-bound token.
- G3: A module is registered through the admin API from its manifest and build; the shell loads it by Module Federation with import-map integrity and a nonce-based CSP (ADR-0010).
- G4: Close the open technical questions that block phase 1b: Q26 (token exchange or down-scoping), Q28 (session store), the gateway concurrency model, the repository layout and the verified toolchain versions (Q2/Q3), and re-verify the spike's Chromium-only, dev-mode evidence on the production stack and in Firefox and WebKit.
- G5: A CI quality gate that enforces everything PRD-0001 NFR-2 demands, so no later change can weaken it.
- G6: A documented, tested `pg_dump` backup and restore of the platform database.

## 2. Non-goals
Deferred to phase 1b unless named in section 4 (PRD-0001 §10):
- Admin UI and the permission-mapping UI (the skeleton has the admin API only), the full module SDK and the module template repository, the provisioner (database schema and Keycloak client per module are created by a script in the skeleton, not by the core), alerts in the admin overview, the idle-timeout warning dialog (UI-13, last sentence), the audit-log hash chain and its verification (the table and the append-only grants are in the skeleton), the retention and store-cleanup jobs (versions are kept, nothing is purged), the store backup.
- Visual language and design system beyond tokens and the few components named in SK-12 (own PRD, ROADMAP).
- Go-live gates of phase 1c: restore rehearsal on a clean host, manual screen-reader pass, Edge and Safari checks, works council and DPO sign-off, load test on the target host (the skeleton runs the load test on CI runners as an indicator only).
- Any business module; WebSocket; e-mail alerts (Q30); Kerberos (Q1); high availability.

## 3. Users and roles
- Platform team (author and consumer of the result); module developers (consume the reference module as the first example, not yet a template).
- Test users in the E2E realm: `admin` (member of the bootstrap admin group), `user-a` (mapped to the reference module's `read` permission), `user-b` (no mapped group), `disabled` (disabled in the directory). Generated per run, never committed with real passwords (section 8).
- No real employee data is processed by CI. A smoke test against the real Active Directory uses only dedicated test accounts (Q31).

## 4. Functional requirements
Numbering `SK-n` avoids clashes with PRD-0001. "Implements" names the PRD-0001 requirement that the item delivers or partly delivers; the part not delivered stays with phase 1b.

### 4.1 Repository, build and CI

#### SK-1 Repository layout and toolchain
The monorepo (ADR-0004) is created with the layout in section 7.1 and pinned toolchain versions (section 7.4). Implements PRD-0001 NFR-2 (foundation) and Q2/Q3.
- AC1: A clean checkout builds with the documented commands (`./mvnw verify`, `npm ci && npm run build --workspaces`, `docker compose build`) on a machine with only Docker, a JDK and Node installed; the commands are listed in `CLAUDE.md` and `README.md` (replacing the "do not exist yet" notice).
- AC2: Versions are pinned (Maven wrapper, `package-lock.json`, image digests for base images in `deploy/`), and the verified version table (section 7.4) in the ADR created for the toolchain matches the lockfiles; a CI check fails if the ADR table and the lockfiles differ.
- AC3: `CLAUDE.md` gains the real build, run, lint, test, single-test and coverage commands; the instruction to add them is removed in the same pull request.

#### SK-2 CI quality gate
The existing `gate` job is extended; a pull request must not weaken it. Implements NFR-1(d), NFR-2, NFR-3, NFR-4.
- AC1: Jobs, all required by `gate`: `docs` (existing); `backend` (build, unit and integration tests, JaCoCo line coverage >= 80 % per Maven module and >= 90 % for the packages `gateway`, `session` and `security`, Failsafe merged into the report); `frontend` (lint, type check, Vitest v8 coverage >= 80 % per package, i18n key parity de/en with placeholder check, no-literal-string lint covering `title`, `aria-label`, `alt` and `index.html`); `e2e` (section 4.7); `security` (dependency scan, image scan, secret scan, SBOM, builds fail on high or critical findings); `compose-config` (`docker compose config` validity and hardening assertions of SK-18).
- AC2: Coverage regression: the build fails if the line coverage of a module or package drops by more than 2 points against the last successful run on `main` (baseline stored as a workflow artefact; the first run on `main` seeds it). Exclusions only through a reviewed list in the build; entry-point classes without logic may be listed, each with a comment.
- AC3: A deliberately broken fixture proves each gate: a test branch (not merged) or a CI self-test shows that lowering coverage, a missing German key, a literal string, a high-severity dependency and a committed secret each turn `gate` red. The evidence is recorded in the verification report (SK-21).
- AC4: Workflow runs use pinned action versions (commit SHA), `permissions: contents: read` by default and no secrets on pull requests from forks; the E2E job pulls only images named by digest or exact version.

### 4.2 Authentication, session and gateway

#### SK-3 Sign-in, session and sign-out (backend-for-frontend)
Implements FR-1, FR-11 AC1, AC2, AC4, AC5 (the parts named), 8.1, 8.2.
- AC1: Unauthenticated requests to `/app/**` are redirected to `GET /core/auth/login`, which starts authorization code + PKCE (S256) against Keycloak with a pre-login cookie (`SameSite=Lax`, 10 minutes) carrying `state`, `nonce` and the verifier.
- AC2: `GET /core/auth/callback` validates `state`, `nonce`, `iss` and the ID token, issues a **fresh** session id (128-bit random, stored only as SHA-256 hash) and sets `__Host-slg-session` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, no `Domain`). The return target must match `/app/<moduleId>/...` or becomes `/`.
- AC3: The login completes without a second login bounce in Chromium, Firefox and WebKit. Because a `SameSite=Strict` cookie is not sent on a redirect chain that started at another site, the callback answers with HTTP 200 and a document that navigates on (`<meta http-equiv="refresh">`, allowed by the CSP) instead of a 302; a test per browser asserts that the first request to `/app/**` after login carries the cookie (finding to confirm in M2; if the browsers differ the callback and the Keycloak host name decision, Q27, are adjusted).
- AC4: Idle timeout 30 minutes (requests marked `X-Slg-Background` do not count) and absolute timeout 10 hours are enforced server-side; tests use an injectable clock.
- AC5: `POST /core/auth/logout` (CSRF-protected) ends the session and redirects the browser to Keycloak RP-initiated logout; `POST /core/internal/backchannel-logout` (reachable only on the internal network, validates the logout token including `sid`) invalidates the matching session immediately. After either, a request with the old cookie gets 401 `SESSION_EXPIRED`.
- AC6: Refresh tokens are stored AES-256-GCM encrypted (key from a Docker secret file, key id stored with the ciphertext, rotation test with two keys); the access token of the user is renewed 60 s before expiry under a row lock; a lost refresh response terminates the session (tested with a Keycloak stub that drops the response). The session table is not part of the backup.
- AC7: Groups come from the ID token group claim (full path) and are stored in the server-side session; they are refreshed from every renewed token, so a changed group takes effect within the Keycloak LDAP sync interval plus one token lifetime (PRD-0001 8.2, Q7). The permission API (SK-6) resolves a user id to the groups of the user's most recent live session; without a session the answer is "no permissions" (see Q35 for background jobs).
- AC8: `GET /core/api/v1/session` returns profile (display name, locale, theme), effective permission names, and a per-session CSRF token; it never returns a token of any kind. A scan of all HTTP responses of the E2E sign-in flow and of the application logs contains no JWT (regular expression test).

#### SK-4 CSRF and request hygiene
Implements FR-11 AC2, AC9, NFR-1.
- AC1: Every non-GET request needs the header `X-Slg-Csrf` equal to the session's CSRF token and an `Origin` equal to the shell origin; a missing `Origin` on a non-GET request is rejected with 403 `CSRF`. GET never changes state.
- AC2: Table-driven tests cover each endpoint class (core API, admin API, gateway, logout): missing header, wrong token, wrong or missing `Origin`, token of another session.
- AC3: A new correlation id is created per inbound request; an inbound `X-Correlation-Id` is accepted only from the proxy network. Errors use problem-details with a machine-readable `code` and the correlation id.

#### SK-5 Gateway to module backends
Implements FR-11 AC3, AC6, AC7, AC8, AC9, FR-8 AC2.
- AC1: `/modules/<id>/api/**` is forwarded only if the module is enabled and UP and the user holds at least one permission of the module; otherwise 404 (unknown or not permitted, no leak), 503 `MODULE_DISABLED` or 503 `MODULE_DOWN`.
- AC2: The gateway obtains an audience-bound access token per user session and module using the mechanism selected by SK-19 (Q26), caches it per session and module in memory until expiry, and drops the cache on module disable, session revoke and logout. The forwarded token carries `sub` but no group list. The reference module backend rejects tokens with wrong audience, wrong `azp`, expired, or with a bad signature (tests with forged tokens).
- AC3: Request and response bodies are streamed without buffering; SSE passes through; limits come from the manifest (`timeoutSeconds` default 10, `maxBodyMB` default 10); an upstream timeout answers 504 `MODULE_TIMEOUT`; an oversize body answers 413 before it is forwarded fully; idle streams close after 60 s.
- AC4: Hop-by-hop headers, `Cookie`, `Authorization` and `X-Slg-*` headers of the browser are not forwarded; identity reaches the module only through the token; `X-Forwarded-*` come from the proxy only.
- AC5: Session store (PostgreSQL, SK-3) unavailable: 503 `SESSION_STORE_DOWN` and no unauthenticated fallback (chaos test, SK-20). Keycloak unavailable during renewal: the session is kept and the answer is 401 `AUTH_UNAVAILABLE` once the cached module token expired.
- AC6: Metrics per PRD-0001 NFR-8 (`slg_gateway_requests_total`, `slg_gateway_request_seconds`, `slg_gateway_upstream_timeouts_total`, `slg_session_active`, `slg_token_refresh_failures_total`) exist on the internal management port, tagged by `module` only.

#### SK-6 Permissions (minimum)
Implements FR-5 (AC1, AC2, AC4 in part), FR-5a.
- AC1: Roles, role permissions and group mappings are tables; the admin API (SK-8) can create and change them. Effective permissions are the union over the user's groups; absence means deny; the cache lives at most 60 s.
- AC2: `SLG_BOOTSTRAP_ADMIN_GROUP` seeds the `platform-admin` mapping when no admin mapping exists. Empty database and unset variable: the core refuses to start. The last admin mapping cannot be deleted through the API. Bootstrap use is written to the audit table.
- AC3: The reference module's backend enforces its permission by annotation; an integration test proves 403 for `user-b` and for `user-a` without the permission, regardless of UI state.

### 4.3 Registry, store and shell document

#### SK-7 Module registration (admin API)
Implements FR-4 (AC1, AC2 in part, AC4, AC5, AC6, AC7 basics), 8.6.
- AC1: `POST /core/api/v1/admin/modules` accepts a manifest (JSON schema, version 1, size <= 256 KB). Violations are returned all at once with field path and a de/en message; golden valid and invalid manifests are part of the tests (missing and unknown fields, duplicate id, incomplete language, bad permission name, unsupported contract version, oversize).
- AC2: The registration check compares `requires.react` and `requires.designSystem` with the versions the shell provides (semver) and rejects mismatches with a clear message (ADR-0010, PRD-0001 FR-4 AC6). Runtime `strictVersion` is not relied on.
- AC3: Registering a higher `version` of an existing id is an upgrade; the previous version is kept; `POST .../rollback` makes it active again. Permission removal or renaming without a `breaking` flag is rejected (PRD-0001 FR-4 AC3). Disable and enable follow PRD-0001 FR-4a (retention of mappings and layouts).
- AC4: Server-side fetches of the manifest's URLs obey PRD-0001 8.6: allowlist of the module's registered internal service name, http(s), fixed port, no redirects, resolved IP not loopback, link-local or metadata address, connection to the validated IP, 5 s timeout. Tests include rebinding with a stub DNS, a redirect, and an IPv6 literal.
- AC5: Every registration, upgrade, rollback, disable and enable writes one audit row (SK-9).
- AC6: Admin endpoints require the `platform-admin` permission; registration, rollback and mapping changes additionally require a re-authentication not older than 5 minutes (`max_age`; step-up flow tested with Keycloak).

#### SK-8 Content-addressed store and import map (minimal)
Implements FR-4 AC7 (the parts below), ADR-0010. Hardening beyond this list (retention job, archive input, monitoring) is phase 1b.
- AC1: Registration copies the build listed in the module's `build-manifest.json` (paths and sizes, fetched under SK-7 AC4) into `/var/slg/store/<moduleId>/<version>-<treeSha256>/` through a temporary directory, rejects `..`, absolute paths, symlinks, hard links, HTML files and files whose type is not on the allowlist, enforces limits (file count, total size, path length), computes SHA-384 of every file, re-verifies the directory after the rename, sets it read-only and switches the active version through a database field (no symlink).
- AC2: `GET /app/**` returns the shell document generated per request: a CSP nonce of at least 128 bits, an inline import map whose `integrity` section lists every JavaScript file of every enabled module from the same registry snapshot, `Cache-Control: no-store`. An unlisted module file is an error, not a fallback.
- AC3: `/modules/<id>/remote/**` is served from the store by the proxy with an `auth_request` subrequest to the core (valid session, otherwise 404 without revealing module existence), fixed content types, `X-Content-Type-Options: nosniff`, `immutable` cache headers for hashed names, 404 for everything not in the registry snapshot. Decision D3 (section 7.5): if the added latency breaks the budget of NFR-6, the core serves the files itself.
- AC4: Tests: changing the reference module container's build after registration does not change what is served; a tampered file in the store directory makes the core refuse to activate it; traversal variants (`..`, encoded, double-encoded, backslash, `//`) against the **production** proxy locations return 404 or 400 with no content (positive assertions, replacing the spike's weaker check).

#### SK-9 Audit table (minimum)
Implements FR-9 AC1 in part; hash chain and verification job in phase 1b.
- AC1: Table `audit_event` with the fields of PRD-0001 FR-9 (timestamp, event type, actor id, source IP, object, outcome, correlation id). The application's database role has `INSERT` and `SELECT` only; a test proves `UPDATE` and `DELETE` fail.
- AC2: Events written in the skeleton: login, logout, session revoke, mapping change, module register/upgrade/rollback/disable/enable, bootstrap use. Each action writes exactly one entry (integration test). Failed logins and break-glass use are phase 1b (they need the Keycloak event ingestion).
- AC3: No request bodies, tokens or business data in audit rows; no names or emails in logs (pseudonymous ids only).

#### SK-10 Preferences and layout
Implements FR-2 (persistence), FR-3, UI-6, UI-24 (server-side preference).
- AC1: `GET/PUT /core/api/v1/preferences` (language `de|en`, theme `light|dark|system`) and `GET/PUT /core/api/v1/layout` (windows, geometry, state, stack order, module route; per user and device class `desktop|narrow`; last write wins; JSON schema validated, size <= 64 KB).
- AC2: A layout entry for a disabled, removed or not permitted module is skipped with a notice and retained when merely disabled (FR-2 AC2).

### 4.4 Shell and window manager

#### SK-11 Desktop shell
Implements FR-2, FR-3, FR-8 AC1, UI-1 to UI-8, UI-9 (basic), UI-13, UI-14, UI-17 to UI-19, UI-22 to UI-25, UI-30, UI-31 in the scope below. The behaviour of PRD-0001 section 6 binds this work; the list states what must exist at the end of the skeleton.
- AC1: Windows: open, move, resize, minimize, maximize, restore, close, focus, snap left/right/top, taskbar, keyboard path through the window menu (`Alt+Space` and a visible menu button), a single-pointer alternative for move and resize (step buttons), `singleton` and `multiInstance` entry points, close guard, narrow single-window mode, per-user persisted layout (SK-10), History API deep links `/app/<moduleId>/<route>` with a neutral state for unknown or unauthorised targets.
- AC2: Gaps recorded in ADR-0011 are closed or documented as accepted in the ADR with a test: `pointercancel` and `lostpointercapture`, window-menu keyboard pattern (arrows, Home/End, roving tabindex, Tab closes), dialog scoped to the owning window with focus return, and **focus retention when a window is raised by pointer or Tab** (decision between `Element.moveBefore()` and `z-index` stacking; automated test: typing continues after clicking and after Tabbing into a background window, iframe content is not reloaded).
- AC3: Launcher lists exactly the enabled modules for which the user holds at least one permission, grouped by category with type-to-filter; a user without a mapped group sees an empty launcher with an explanatory message. Desktop icons, notification centre and recent items are phase 1b.
- AC4: Language (browser language, then `de`; user preference once set) and theme switch at runtime, without reload and without losing module state, applied to every open window; `<html lang>` and `data-bs-theme` follow. A missing key in either language fails CI (SK-2).
- AC5: Session expiry (UI-13): the gateway's 401 `SESSION_EXPIRED` shows a non-modal `role="alert"` banner with a keyboard-reachable "Sign in again" button; the login opens in a popup, with a new tab as fallback; completion is detected through `BroadcastChannel` (not `window.opener`); module state and focus are kept; a different user signing in resets all windows; failed or cancelled re-authentication replaces the windows with SessionExpired. A module's own 401 or 403 never triggers the banner.
- AC6: Error mapping (UI-30, UI-31): the shell maps problem-details `code` to i18n keys and never shows `title` or `detail`; unknown codes show a localised generic message with the correlation id; 403 shows Forbidden, 502/503/504 and timeouts show the Error state with Retry and backoff in the affected window, a failing health poll shows the Offline banner.
- AC7: Failure isolation (FR-8 AC1): a stopped module container, an unreachable or HTTP 500 remote entry, a wrong-content entry and a render exception each show an error boundary with Retry and "Copy details" in that window only.
- AC8: The shell makes no outbound request to a third party (fonts, CDNs, telemetry): an E2E test fails on any request whose origin is not one of the two configured hosts.

#### SK-12 Design system (minimum)
Implements UI-21 (foundation), UI-22, UI-23, UI-28.
- AC1: Package `@slg-os/design-system` (React on Bootstrap 5.3.8, shared as a Module Federation singleton) with `--slg-*` tokens mapped onto Bootstrap variables, AA-compliant button and text tokens for every shell background (including the tertiary background where `btn-outline-secondary` fails, spike finding), and the components Button, Dialog, and the state components Loading, Empty, Error, Forbidden, NotFound, Offline, SessionExpired.
- AC2: Lint rules reject raw hex colours, `!important`, global selectors and a second Bootstrap build in module code; every animation sits behind a `prefers-reduced-motion` rule and a test enforces it; focus indicators stay visible in forced-colors mode.
- AC3: The visual language (icons, window chrome look, desktop metaphor) is not decided here; defaults are functional and replaceable by the later design PRD without API changes.

### 4.5 Reference module and SDK (minimum)

#### SK-13 Reference module
Implements FR-6 (minimum), FR-7 (foundation), FR-8, UI-26.
- AC1: A backend (Spring Boot, own database schema `mod_reference` and role created by the skeleton's provisioning script, own Flyway migration) and a frontend remote (Vite, Module Federation, `index.html` entry, `dts: false`, base `/modules/reference/remote/`) in the monorepo, built and containerised like a real module. It declares two entry points (a `singleton` "Notes" window and a `multiInstance` "Viewer"), one permission `reference.notes.read` and `reference.notes.write`, and error codes with de and en texts.
- AC2: Notes are stored per user in the module schema and exposed through `/modules/reference/api/v1/notes` (list, create, delete) with permission checks; an SSE endpoint streams a counter for the gateway streaming test; a slow endpoint and a large-body endpoint exist for timeout and limit tests; a "fail" switch makes the module return 500 for the chaos tests.
- AC3: The i18n bundles `i18n/{de,en}/reference.json` use ICU messages; parity is checked by SK-2.
- AC4: Contract tests (run in the module's CI job): render-count test (SDK context values and callbacks have stable identity, spike finding), theme and language propagation, error boundary on 500 and on an unreachable remote.

#### SK-14 SDK (minimum)
Implements FR-6 in the minimal form needed by SK-13; the full SDK and the template are phase 1b.
- AC1: `slg-os-sdk-backend` (library): bearer token validation (issuer, audience, signature with cached JWKS and refetch on unknown key id, expiry, `azp` allowlist), `@RequiresPermission` annotation and filter, identity access, correlation id propagation, readiness endpoint with DEGRADED/UP/DOWN, JSON logging. No code of the reference module depends on core internals.
- AC2: `@slg-os/sdk-frontend` (package): identity (profile only), permission hook, `useTranslation` wrapper, theme and language hooks, the HTTP client that adds `X-Slg-Csrf` and `X-Correlation-Id` and maps gateway error codes, live-region announce API, close guard registration. No `getAccessToken` exists. Raw `fetch` calls in module code are rejected by lint.
- AC3: The SDK versions are tied to the contract version `1.0` declared in the manifest schema.

### 4.6 Keycloak and directory

#### SK-15 Keycloak in production mode
Implements 8.5, 8.7 (the parts below), Q26, Q27.
- AC1: Keycloak runs with `kc start` (never `start-dev`), PostgreSQL, behind the proxy on its **own host name**; only login, logout, token and well-known paths are routed publicly; the admin console and the master realm are not (positive tests: 404 from the public proxy). The core reaches Keycloak by the public issuer URL through a Docker network alias.
- AC2: The realm is a committed file (`deploy/keycloak/realm.json`) without secrets: clients `slg-core` (confidential, code + PKCE S256, exact redirect and post-logout URIs, back-channel logout URL on the internal network, no implicit or password grant, empty web origins), a module service-account client for the reference module, explicitly defined built-in client scopes (`profile`, `email`, `roles`, `web-origins` in addition to the custom ones), a group-membership mapper with full path, explicit token, SSO and refresh-token lifetimes (access 5 min; SSO idle >= 30 min, SSO max >= 10 h, refresh >= session), brute-force detection with tuned parameters, LDAP user federation with a 10-minute sync interval.
- AC3: An import smoke test starts a fresh Keycloak with the file and asserts the scopes, clients, mapper and lifetimes (spike finding: importing custom scopes drops the built-ins).
- AC4: Secrets (client secret, LDAP bind password, database password, token-vault key) come from Docker secrets (files, mode 0400), are not in images, the repository, the realm file, `docker inspect` output or logs; a scan of the compose configuration and of image layers asserts this.
- AC5: The test directory is an LDAP container with fixed users and groups for CI (Q31 covers the real Active Directory). Keycloak and every JVM trust the internal CA through a mounted truststore; the CI CA is generated per run.

#### SK-19 Decision experiment: per-module token mechanism (Q26)
- AC1: On production-mode Keycloak, with the confidential `slg-core` client, test (a) standard token exchange v2 to an audience-bound token and (b) refresh-token down-scoping with serialised grants. Record for each: whether it works, which Keycloak features and flags it needs, the resulting `azp`, `aud`, `scope` and lifetime, behaviour with rotating refresh tokens and concurrent requests (exactly one grant at a time per session), and failure behaviour when Keycloak is down.
- AC2: Rule: use (a) if it works for the confidential client with the required claims and without legacy feature flags; else (b). The outcome is an ADR (section 7.5) and SK-5 AC2 is implemented against it. Both candidates sit behind one interface so the choice can be reversed without touching the gateway.

### 4.7 End-to-end verification, backup and operations

#### SK-16 Compose stack and operations
Implements PRD-0001 §10 (topology), NFR-5, NFR-7, NFR-8.
- AC1: `docker compose up -d --build` brings up `proxy`, `keycloak`, `ldap` (test profile), `postgres`, `core-backend` and `reference-module` (backend). No separate shell container: the proxy serves the shell's static assets and the store, the core serves the shell document and the APIs. Startup is health-gated for `postgres` before `core-backend` and `core-backend` before `proxy`; the core starts when Keycloak is absent and reports readiness DEGRADED (sign-in unavailable) instead of failing, so Keycloak is **not** a `depends_on` condition of the core.
- AC2: Resource limits per NFR-7 (`mem_limit`, `cpus`) are set in the compose file and asserted by the `compose-config` job; the JVM heap is capped at 60 % of the limit.
- AC3: Hardening of every application container (core, reference module): non-root user, read-only root file system with explicit tmpfs, `cap_drop: [ALL]`, `no-new-privileges`, internal network only (no host ports except the proxy), health check defined, logs JSON to stdout with rotation (10 MB x 5). Asserted by the `compose-config` job.
- AC4: Management endpoints (Actuator health, readiness, Prometheus metrics) listen on an internal port that the proxy does not route; `/core/api/v1/health` (liveness, readiness with DEGRADED state, certificate expiry warning for both host names, clock drift) is available to authorised admins.
- AC5: Configuration only through environment variables and secret files; a documented table of every variable lives in `README.md`; the core refuses to start with missing or invalid mandatory settings and says which one (de and en operator messages).

#### SK-17 Backup and restore of the platform database
Implements NFR-5 (script), phase 1a scope.
- AC1: `deploy/backup/backup.sh` creates `pg_dump` custom-format dumps of the platform, Keycloak and reference-module databases, a Keycloak realm export without secrets, a tarball of the store volume and an inventory of the compose and secret file names (not contents); output is encrypted with `age` for a configured recipient and written to a configurable directory (the SMB mount is the operator's concern, Q9); retention 14 daily and 8 weekly copies; the session table is excluded.
- AC2: `deploy/backup/restore.sh` restores into a clean stack. A CI test registers a module, signs in, runs the backup, destroys the volumes, restores, and asserts: registry and mappings present, every store hash matches, all sessions invalid (users must sign in again), audit rows present.
- AC3: A failing backup exits non-zero and writes an error line; the alert hook is a no-op until Q30 is decided, documented as such.

#### SK-18 End-to-end tests on the real stack
Implements PRD-0001 §9 (E2E and chaos) for the skeleton scope.
- AC1: Playwright runs against the compose stack with production-mode Keycloak, the real proxy and both host names (test CA trusted by the browsers) in **Chromium, Firefox and WebKit**. Scenarios: sign-in and desktop; launcher filtering for `admin`, `user-a`, `user-b`; permission denial (403 independent of UI); opening both entry points; language and theme switch with state kept; session expiry and re-authentication without state loss; sign-out and back-channel logout; failing module; module upgrade and rollback; AD (LDAP) unavailable login error; third-party-request guard (SK-11 AC8).
- AC2: Every scenario runs axe (all impacts) with menu, dialog, banner and narrow mode open; a CSP test asserts zero violations during sign-in, theme and language switch, module error and session expiry in all three browsers (closes the gaps of spike WP1.6).
- AC3: Integrity and CSP matrix (Q25a evidence): in each browser, a tampered entry and a tampered chunk are refused, a correct build loads, an unlisted module file fails, and the nonce blocks an injected inline script. Results are recorded per browser; a browser that fails is documented and the affected control is marked "not enforced" in ADR-0010 instead of silently accepted.
- AC4: Chaos cases: stop Keycloak (sessions stay valid until the cached module token expires, then `AUTH_UNAVAILABLE`), stop PostgreSQL (`SESSION_STORE_DOWN`, recovery without restart of the core), restart the core (sessions survive, the SDK retries once), stop the module (`MODULE_DOWN`, shell and other windows usable, admin API usable).
- AC5: Header and proxy tests on the production locations: stripping of inbound `X-Forwarded-*`, `Forwarded` and identity headers (allowlist), no `Set-Cookie`/CSP/CORS/`Service-Worker-Allowed` from module responses, the security headers of NFR-1(b) on every location, traversal cases (SK-8 AC4).
- AC6: Gateway load test (k6) on the CI runner: 50 req/s with a stub upstream and 20 concurrent users: added latency p95 <= 15 ms; 100 req/s sustained without errors; core cold start to ready <= 60 s; shell first load, sign-in to usable desktop and window open measured against NFR-6. CI-runner numbers are an indicator, not evidence for the target host (phase 1c); a regression of more than 20 % against the last run on `main` is reported (not blocking in the skeleton, blocking once phase 1c fixes the target hardware).

#### SK-20 Failure and recovery behaviour of the core
- AC1: Sessions survive a core restart (stored in PostgreSQL); in-flight requests fail cleanly; concurrent refresh of the same session by two core threads yields one grant (test with two threads and with two core containers against one database, to keep scale-out open).
- AC2: Flyway migrations run at start under an advisory lock; a failing migration keeps readiness DOWN and the old container serving.

### 4.8 Verification report

#### SK-21 Verification report and decisions
- AC1: `docs/verification/skeleton-report.md` records: verified versions, results of SK-2 AC3, SK-15 AC3, SK-19, SK-18 AC3 (per browser) and AC6, the PRD-0001 consistency pass (section 12 of this PRD) and every deviation. It is the evidence for the owner's decisions on ADR-0010 and ADR-0011.
- AC2: The ADRs of section 7.5 exist; ADR-0010 and ADR-0011 are proposed for acceptance with the evidence, or stay proposed with the named blocker. Accepted ADRs are never edited; amendments are new ADRs.

## 5. Non-functional requirements
- NFR-S1 Quality: PRD-0001 NFR-2 applies from the first commit; the coverage gates (80 % line per module and package, 90 % for `gateway`, `session`, `security`, 2-point regression limit) are active before the first feature merges (milestone M1).
- NFR-S2 Security: PRD-0001 NFR-1 and section 8 apply; section 8 of this PRD lists what the skeleton verifies.
- NFR-S3 i18n and accessibility: PRD-0001 NFR-3 and NFR-4 apply to everything the skeleton ships, including operator messages (de and en) and the Keycloak login (built-in German and English; the custom theme belongs to the design PRD).
- NFR-S4 Performance: PRD-0001 NFR-6 and NFR-7 are measured as in SK-18 AC6; no target is relaxed here.
- NFR-S5 Reproducibility: the E2E result must not depend on the developer machine: CI starts the stack from a clean checkout; local `npm run e2e` uses the same compose files.
- NFR-S6 Data protection: see section 8.

## 6. UI/UX
The scope is SK-11 and SK-12. Texts exist as i18n keys in `de` and `en` from the first commit (namespace `shell`, `designSystem`, `reference`). Both languages are reviewed by a German speaker (the owner), not machine-translated only. Loading, Empty, Error, Forbidden, NotFound, Offline and SessionExpired states exist as components and are used by the shell and the reference module. Layouts tolerate +35 % German text length (pseudo-locale `de-XL` in dev builds and visual tests). The unauthenticated language is the browser language, then `de`. The Keycloak login page uses the built-in bilingual theme; branding is deferred.

## 7. Data and interfaces

### 7.1 Repository layout (proposal, confirmed in ADR-0013)

```text
pom.xml                    parent (BOM, plugin versions, JaCoCo rules)
backend/
  core/                    Spring Boot application: bff, gateway, session, security, registry, store, audit, admin, shell document
  sdk-backend/             library (SK-14)
  reference-module/        Spring Boot module backend (SK-13)
frontend/
  package.json             npm workspaces
  shell/                   Vite + React shell
  design-system/           @slg-os/design-system
  sdk-frontend/            @slg-os/sdk-frontend
  reference-module/        federated remote
  contracts/               manifest JSON schema, golden manifests
deploy/
  compose.yaml, compose.e2e.yaml
  proxy/ keycloak/ ldap/ backup/ scripts/
e2e/                       Playwright tests, k6 scripts
docs/                      as today, plus docs/verification/
```

Java base package `com.systemlogistics.slgos` is a proposal; changing it later is cheap only before M2 (Q33).

### 7.2 Core API (all under `/core`, versioned)
| Path | Purpose |
|---|---|
| `GET /core/auth/login`, `GET /core/auth/callback`, `POST /core/auth/logout`, `POST /core/internal/backchannel-logout` | sign-in, sign-out (internal path not routed by the proxy) |
| `GET /core/api/v1/session` | profile, permissions, CSRF token |
| `GET /core/api/v1/modules` | launcher data (permitted, enabled, UP) |
| `GET/PUT /core/api/v1/preferences`, `GET/PUT /core/api/v1/layout` | per-user state |
| `POST /core/api/v1/admin/modules`, `.../{id}/rollback`, `.../{id}/disable`, `.../{id}/enable` | registry (SK-7) |
| `GET/PUT /core/api/v1/admin/mappings`, `POST /core/api/v1/admin/sessions/revoke` | permission mapping, session revocation |
| `GET /core/api/v1/health` | operator overview (admin) |
| `GET /app/**` | shell document with nonce and import map |
| `/modules/<id>/api/**`, `/modules/<id>/remote/**` | gateway, store |

The API is described in OpenAPI generated from the code; `oasdiff` runs in CI against the last release on `main` and fails on breaking changes (PRD-0001 §9).

### 7.3 Data model sketch (Flyway, versioned in the repo)
`module`, `module_version` (manifest JSON, tree hash, file hashes, state), `module_entry`, `permission`, `role`, `role_permission`, `group_mapping`, `user_preference`, `user_layout`, `session` (id hash, user id, groups, encrypted refresh token with key id, timestamps, CSRF token hash; excluded from backup), `audit_event`. Database roles: `slg_app` (no DDL, no UPDATE/DELETE on `audit_event`), `slg_migrator` (DDL, used by Flyway only), `mod_reference` (own schema). Module tables never reference each other.

### 7.4 Toolchain versions (verified against package registries on 2026-10-19; compatibility with each other is **not** verified until M1 builds)
| Component | Proposed | Source of verification |
|---|---|---|
| JDK | 25 (LTS), Eclipse Temurin 25 image; 21 as fallback | Adoptium release list (LTS: 8, 11, 17, 21, 25); image tag `25-jre` exists |
| Spring Boot | 4.1.1 (4.2.0-M2 is a milestone, not used) | Maven Central; manages Spring Framework 7.0.9, Spring Security 7.1.1, Flyway 12.4.0, Hibernate 7.4.5, Micrometer 1.17.1, PostgreSQL JDBC 42.7.13, Testcontainers 2.0.5, JUnit 6.0.3, Mockito 5.23.0, AssertJ 3.27.7 |
| Maven | 3.9.16 through the wrapper (Maven 4 is only a release candidate) | Maven Central |
| JaCoCo | 0.8.15 (JDK 25 support to be confirmed in M1) | Maven Central |
| Keycloak | 26.8.1 (`quay.io/keycloak/keycloak:26.8.1`, same version as the spike) | Maven Central, quay.io tag |
| PostgreSQL | 17 (Debian-based image, for stable collation; 18 and 16 images exist) | Docker Hub tags; Keycloak's supported database versions to be checked in M2 |
| Node.js | 22 (as in the spike); 24 considered in ADR-0013 | local, spike |
| Vite / `@vitejs/plugin-react` | 8.3.4 / 6.1.2 | `npm view` |
| `@module-federation/vite` | 1.23.5 | `npm view` |
| React / React DOM | 19.3.1 | `npm view` |
| Bootstrap | 5.3.8 | `npm view` |
| Vitest | 5.0.5 | `npm view` |
| Playwright / `@axe-core/playwright` | 1.64.1 / 4.14.0 (the spike used 1.56.1) | `npm view` |
| TypeScript | 7.0.2 is the registry's latest; compatibility with ESLint and Vitest to be checked in M1, otherwise the newest compatible 6.x | `npm view` |
| i18next / react-i18next | 26.5.3 / 17.1.0 | `npm view` |
| ESLint | 10.13.0 | `npm view` |
| Proxy | nginx (official image, version pinned by digest in M1) | to verify |
| Scanners, SBOM, k6, age | chosen in M1 (Q34) | to verify |

### 7.5 Decisions made in this PRD and ADRs produced by the skeleton
| # | Decision (proposal) | ADR |
|---|---|---|
| D1 | Toolchain and layout of 7.1 and 7.4 | ADR-0013 |
| D2 | Gateway on Spring MVC with virtual threads and the JDK `HttpClient` with streamed bodies; switch to WebFlux only if the SK-18 AC6 budget fails and the cause is the threading model | ADR-0014 |
| D3 | Remote files served by the proxy with `auth_request` to the core (fallback: core serves them); the shell's own static assets are public | amendment to ADR-0010 as a new ADR, if needed |
| D4 | Sessions in PostgreSQL with a custom store (hashed ids, encrypted refresh tokens, row-lock refresh); Spring Security's OAuth2 client is used for protocol validation only, if it fits | ADR-0015 (closes Q28) |
| D5 | Per-module tokens by the rule of SK-19 | ADR-0016 (closes Q26) |
| D6 | Spring Data JDBC / `JdbcClient` and explicit SQL instead of JPA, because sessions, locks and the audit privileges need explicit SQL | ADR-0013 |
| D7 | Groups kept in the server-side session and refreshed with every token renewal; the permission API resolves by user id from the latest live session (Q35) | ADR-0015 |

## 8. Security considerations
The skeleton verifies, by tests, the following controls of PRD-0001; each AC above names its test.
- Session and cookies: SK-3 AC2/AC3/AC5/AC6; CSRF: SK-4; gateway authorisation, header hygiene and limits: SK-5; SSRF and registration input: SK-7; integrity, nonce, traversal and store: SK-8; audit privileges: SK-9; Keycloak production settings, realm file, secrets: SK-15; container hardening: SK-16; CSP, headers and third-party-request guard: SK-11 AC8, SK-18.
- Threats considered for the new trust boundaries: browser to proxy (header spoofing, traversal), proxy to core (identity headers, `auth_request` result caching and bypass: the subrequest result is not cached across sessions), core to module (token confusion between modules, wrong `azp`), core to Keycloak (refresh race, lost response), core to database (privilege separation), CI (secrets in pull requests from forks, unpinned actions, poisoned images).
- Residual risk unchanged from PRD-0001 8.3 and Q24: a registered module's JavaScript runs in the shell origin with the user's authority. The skeleton adds no mitigation beyond integrity and ADR-0007 and must not claim any.
- Secrets: SK-15 AC4; the token-vault key has a documented rotation procedure; test credentials are generated per CI run and are not reused anywhere else.
- Logs: JSON, correlation id, pseudonymous user id, no tokens, no names or emails (regular-expression test over E2E logs).
- Dependencies: scanning gates (SK-2 AC1); SBOM stored as a CI artefact for each build of `main`.

Data protection: the skeleton processes personal data only in the form of the E2E test users (synthetic) and, for the real-AD smoke test, of dedicated test accounts named by the owner (Q31); no production directory data is imported. The retention rules of PRD-0001 8.9 for sessions are implemented (purge hourly, idle and absolute timeouts); the audit retention job is phase 1b. The Art. 30 record and DPIA decision stay with Q23 before go-live.

## 9. Testing strategy
- Backend: JUnit, AssertJ, Mockito; Spring Boot tests; Testcontainers for PostgreSQL and (for the Keycloak import smoke test and the integration tests of SK-3/SK-5/SK-19) Keycloak with the committed realm and the LDAP container; ArchUnit rules: no cross-schema access, `gateway` and `session` depend on `security` and not the other way round, no class outside `session` touches the token vault; JaCoCo gates per SK-2.
- Frontend: Vitest + Testing Library + MSW, Playwright component and E2E tests (SK-18); axe in every scenario; contract test builds the reference module from a clean checkout and loads its built `remoteEntry.js` in the shell fixture (mount, theme and language propagation, error boundary on 500 and unreachable remote, shared singleton versions).
- Time-dependent behaviour (timeouts, refresh margin, expiry) uses an injectable clock, not sleeps.
- The spike suite under `spikes/shell/tests` on branch `spike/shell` seeds the contract test only after being parametrised by base URL and module id; its hostile-remote tests are characterisation tests and are marked as such. Nothing is copied from the spike without the normal workflow.
- Mutation check: each milestone's review (CLAUDE.md implementation workflow) includes a mutation pass on `session`, `gateway` and `security` to show that the suite notices deliberate faults.
- Coverage: the 80 % line gate (90 % for `gateway`, `session`, `security`) is met by tests that assert behaviour (inputs to outputs, error and boundary cases), not by line execution alone; each fixed defect gets a regression test.
- Manual and external gates (not blocking the skeleton merge, tracked in the verification report): real-AD smoke test (Q31), Safari on a Mac, Edge on an office PC, manual screen-reader pass (phase 1c).

## 10. Rollout and operations
- Milestones, each its own pull request through the implementation workflow (build, tests with coverage, four independent reviews, fixes, merge when `gate` is green):
  - M1 Foundation: repository layout, toolchain ADR-0013, Maven and npm projects, CI jobs and the gates with their self-tests (SK-1, SK-2), `CLAUDE.md` commands.
  - M2 Identity and session: compose base (postgres, keycloak, ldap, proxy with two host names and test CA), realm file, import smoke test, BFF login/logout/session/CSRF, token vault, SK-19 experiment and ADR-0015/0016 (SK-3, SK-4, SK-15, SK-19, parts of SK-16).
  - M3 Registry and gateway: permissions minimum, registry, store, import map, shell document, gateway with limits, streaming and metrics, audit table (SK-5 to SK-10).
  - M4 Shell: window manager, launcher, preferences, states, session-expiry banner, design-system minimum (SK-11, SK-12).
  - M5 Reference module and SDK minimum (SK-13, SK-14).
  - M6 Verification: E2E in three browsers, chaos, load, backup and restore, hardening checks, verification report, ADR proposals for 0010/0011 (SK-16 to SK-18, SK-20, SK-21).
  Milestones may overlap in time; a milestone is done when its tests are green and its review findings are resolved.
- The skeleton is not deployed to production. It runs on the CI runners and on any developer or staging host with Docker, using the compose files in `deploy/`.
- Upgrade and rollback of the skeleton itself: images by digest, Flyway forward-only migrations with expand/contract discipline from the first migration.
- Operator documentation in German and English (`README.md`, `docs/operations/`): start, stop, configuration table, secrets, backup, restore, troubleshooting of the failure modes of PRD-0001 §10.
- Cost of the CI: the E2E job (three browsers, compose stack) is the longest job; it runs on every pull request, and the load test on `main` nightly; if runtime exceeds 25 minutes the browser matrix is parallelised, not reduced.

## 11. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| Q31 | Test directory for a real-AD smoke test: dedicated test accounts, a read-only bind account, the LDAPS certificate chain, and confirmation that processing these test accounts is acceptable before Q23 is closed (Q20 covers the operator of the account) | Benjamin Honauer | M2 |
| Q32 | Host names for development/staging (shell and Keycloak, same registrable domain or not), and whether a staging host separate from production exists. This closes Q27 for the skeleton; production names and Windows AD CA certificates stay with Q27 | Benjamin Honauer | M2 |
| Q33 | Java group id and base package (`com.systemlogistics.slgos` proposed) | platform team | M2 |
| Q34 | Choice of dependency scanner, image scanner, secret scanner, SBOM tool, load-test tool and backup encryption tool; proposals are made in ADR-0013 and verified against official documentation | platform team | M1 |
| Q35 | Permissions for background work of a module for a user without a live session: the skeleton returns "no permissions" for such calls; a module that needs more (scheduled jobs on behalf of a user) is a phase 1b design topic | platform team | before the first module with jobs |
| Q36 | Is a Mac available for the WebKit/Safari manual check (Q25a), and who runs it? Playwright WebKit in CI is not Safari | Benjamin Honauer | phase 1c |
| Q26, Q28 | Closed by the skeleton (SK-19, ADR-0015/0016) | platform team | M2 |
| Q23, Q24, Q25b, Q30 | Unchanged, tracked in PRD-0001 | Benjamin Honauer | see PRD-0001 |

## 12. PRD-0001 consistency pass
PRD-0001 §12 requires a final consistency pass of revision 2 in the skeleton's first PRD review. The author's findings (the review agents extend this list); none is changed in PRD-0001 before the owner approves this PRD, and PRD-0001 then gets revision 3 listing them:

| # | Finding in PRD-0001 | Handling in this PRD |
|---|---|---|
| C1 | FR-4 AC7 requires a valid session for module files, while §10 and FR-11 say the proxy serves static files only | `auth_request` (SK-8 AC3, D3) |
| C2 | NFR-1(b) CSP has `frame-src 'none'` and `frame-ancestors 'none'`, while ADR-0010 (proposed, written before ADR-0012) lists `frame-src 'self'` and `frame-ancestors 'self'` | PRD-0001 prevails; ADR-0010 is superseded in the part concerning frames by the new ADR if it is accepted |
| C3 | FR-5 AC4 says the SDK reads permissions with the gateway-forwarded token, while FR-11 AC3 says that token has no group list, and background work uses a service account | groups kept in the session, resolved by user id (SK-3 AC7, D7); background work Q35 |
| C4 | `SameSite=Strict` session cookie vs a redirect chain from Keycloak (a cross-site start) | callback answers 200 with a client-side navigation (SK-3 AC3), verified per browser |
| C5 | §10 lists Keycloak as a health-gated start dependency of the core, but also demands that the core starts without Keycloak (DEGRADED) | Keycloak is not a `depends_on` condition (SK-16 AC1) |
| C6 | FR-9 lists failed logins ingested from Keycloak events, but gives no mechanism (admin console is not routed publicly) | out of the skeleton; mechanism (event listener or internal admin-API polling) is a phase 1b design item |
| C7 | NFR-2 "coverage must not drop by more than 2 points against main" has no mechanism | baseline artefact (SK-2 AC2) |
| C8 | §10 says proxy routes are "generated from registry entries" without saying how the proxy learns about a new module | the proxy locations are generic (`/modules/<id>/remote/` with the id as a path variable); no reload on registration (SK-8 AC3); to be confirmed by the operations review |
| C9 | The Q-numbering in PRD-0001 skips from Q22 to Q29 inside table 11.1 (Q23 to Q28 appear in 11.2) | cosmetic; fixed in revision 3 |

## 13. Review log
No review yet (Draft).
