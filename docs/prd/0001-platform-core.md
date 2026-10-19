# PRD-0001: Platform core: shell, authentication, authorization, module contract

- Status: Revision 2, independently reviewed (requirements, security, UI/UX, operations) and integrated on 2026-10-19; awaiting owner approval. It applies the spike findings (PRD-0002, ADR-0010 to 0012) and the owner's decision for a backend-for-frontend. Items marked "proposed" need confirmation in the skeleton phase.
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0001-0012 (0010 and 0011 are proposed, 0012 is accepted)
- Related: PRD-0002 and `docs/spikes/2026-shell-spike-report.md`

## 1. Problem and goals
Departments need many internal tools and automations. Building each as a standalone app duplicates login, permissions, look and operations. slg-os provides one platform with an OS-like desktop in the browser, single sign-on against the local Active Directory, and a mandatory contract that lets independent teams add modules quickly and consistently.

Goals:
- G1: A window-based desktop shell where users open permitted modules in windows.
- G2: Authentication against AD via Keycloak (ADR-0001).
- G3: Group-based authorization managed in an admin UI (ADR-0006).
- G4: A versioned module contract so a new module can be registered without changing the core (ADR-0002, ADR-0005).
- G5: Consistent UI/UX, bilingual (de/en) via i18n, WCAG 2.2 AA, across the shell and all modules.
- G6: Operable by a small team on a single server (ADR-0009).

## 2. Non-goals
- High availability, Kubernetes, multi-site deployment.
- Modules from outside the platform team's review process (ADR-0007).
- Mandatory collection of business-level audit data (ADR-0008).
- Any concrete business module (each gets its own PRD in its own repository).
- Languages beyond German and English (the design must not preclude them).

## 3. Users and roles
- End user: signs in, opens permitted modules, sets language/theme.
- Module developer (internal team): builds a module from the template and the SDK.
- Platform administrator (`platform-admin`): registers modules, maps AD groups to roles/permissions, revokes sessions. Seeded through a configured AD group (FR-5a).
- Auditor: read-only access to the security audit log. Not part of phase 1 (Q14); platform administrators read the log and each read is audited.

## 4. Functional requirements
Each requirement is testable; "AC" are acceptance criteria.

### FR-1 Sign-in and sign-out
Unauthenticated users are redirected to Keycloak; after login the desktop appears. Sign-out ends the session in shell and modules. The core is a backend-for-frontend (ADR-0012): the browser holds only a session cookie, never a token.
- AC1: The core is a confidential OIDC client using the authorization code flow with PKCE (S256); implicit and password grants are disabled (see 8.1).
- AC2: Sign-out uses RP-initiated logout; Keycloak back-channel logout invalidates the server-side session at once, so no further module request is forwarded (see 8.2).

### FR-2 Desktop shell
Windows (open, move, resize, minimize, maximize, close, focus), taskbar, launcher listing only modules the user may use, desktop icons, notification area, per-user persisted layout and preferences. Behaviour is specified in section 6.
- AC1: The launcher shows exactly the enabled modules for which the user holds at least one permission; a user with no mapped group sees an empty launcher with an explanatory message.
- AC2: Persisted layout entries referring to disabled, removed or no longer permitted modules are skipped without error and with a notice; entries of merely disabled modules are retained.

### FR-3 Language and theme
German/English and light/dark switchable per user; all shell texts come from i18n resources.
- AC1: Switching language or theme updates shell and all open windows without reload and without losing module state (UI-22, UI-25).
- AC2: A missing translation key in either language fails CI (UI-26).

### FR-4 Module registry
Administrators register a module by submitting its manifest through the admin UI or admin API (same validation). Registration is an admin-only action; modules do not self-register (Q16).
- Manifest fields: `id` (unique, immutable, `[a-z][a-z0-9-]{2,31}`), `name`, `version` (SemVer), `contractVersion` (range), `icon`, i18n resource paths for de and en, `entryPoints` (federated remote URL, API base path, health URL; each with `instanceMode` `singleton|multiInstance`, `minWindowSize`, `minViewport`, `category`), `permissions[]` (names `<moduleId>.<resource>.<action>` with de/en labels), optional `suggestedRoles[]`, `requires` (React range, design-system range, per ADR-0005 and ADR-0010) and `closeGuard` support. Integrity hashes are computed by the core at registration for every JavaScript file of the build and are never supplied by the module (8.3). Each entry point may also declare `timeoutSeconds` (default 10, max 300), `maxBodyMB` (default 10, max 100), `streaming` (`none|sse`) and its error codes (UI-30); the module lists the caller modules allowed to call it (8.4). On registration the core idempotently provisions the Keycloak audience, scope and service-account client of the module (audited).
- AC1: A manifest with a missing or unknown field, duplicate id, incomplete language, invalid permission name, unsupported contract version, or oversize payload (> 256 KB) is rejected; the response lists every violation with field path and message (de/en).
- AC2: Registering a higher `version` of an existing id is an upgrade; the previous manifest is kept and one admin action rolls back to it. Order: an upgrade deploys the backend container first (backward-compatible API) and then registers the new remote; a rollback runs in reverse; the admin UI shows both versions and warns on a mismatch.
- AC3: An upgrade that removes or renames a permission, or raises the required contract major, is rejected unless flagged `breaking`; breaking upgrades list affected mappings and need explicit confirmation. Mappings of removed permissions are marked orphaned, never silently deleted.
- AC4: Every registration, upgrade, rollback, disable, enable and deletion creates an audit entry.
- AC5: Server-side fetches of manifest-referenced URLs obey the SSRF rules in 8.6.
- AC6: A manifest whose `requires` is not satisfied by the React and design-system versions the shell provides is rejected with a clear message. This check is mandatory; the Module Federation runtime version check is not a control (it fails closed or open depending on load order, ADR-0010).
- AC7: Registration copies the build into an immutable, content-addressed store. It rejects symlinks and hard links, restricts file types, hashes all files, re-verifies an existing store directory, makes the store read-only, switches the active version atomically and validates the entry path on the server. Changing the module container's build after registration does not change what is served. Details: the build source is a build archive or an HTTP file listing (`build-manifest.json` with paths and sizes) fetched under 8.6, never the live container directory; unpacking enforces limits on archive size, file count and path length, rejects `..` and absolute paths and HTML files; each file is served with a fixed type from an allowlist plus `X-Content-Type-Options: nosniff`; every path not in the registry snapshot answers 404; files are served only with a valid session (otherwise 404, no leak of module existence). The store layout is `/var/slg/store/<moduleId>/<version>-<treeSha256>/`, written to a temporary directory, verified and renamed; the active version is a database field, not a symlink. The shell document always references versioned paths; the three previous versions and every version younger than the absolute session lifetime (10 h) stay served; older ones are removed by an audited admin job; the store volume is monitored (alert at 70 %). The core refuses to activate a module whose store directory fails verification.

### FR-4a Module disable/enable
- AC1: A disabled module is absent from every launcher, its API routes return 503 with a machine-readable code, the gateway forwards no requests for it and cached module tokens are dropped.
- AC2: Windows open at disable time show the "module disabled" state within the health interval of FR-10 (15 s, at most 45 s after three failed checks).
- AC3: Mappings and user layouts are retained; enabling restores access without re-mapping.

### FR-5 Permission model
- Effective permissions are the union of the permissions of all roles mapped to the user's AD groups (identified by Keycloak group claim, full path). No explicit deny; absence means deny. Roles are platform-owned bundles of permissions; manifests only suggest them.
- AC1: A user with no mapped group gets 403 from every module API.
- AC2: A removed mapping applies from the next request after the permission cache expires (<= 60 s); module access tokens issued by the gateway live at most 5 min. A "revoke sessions" action (user or all) is available and audited.
- AC3: The backend SDK enforces permissions by annotation and filter; an integration test proves 403 without the permission regardless of UI state. The frontend SDK offers an equivalent check for hiding UI only.
- AC4: The core resolves effective permissions server-side from the user's AD groups. The SDK reads them through the core API with a short cache (<= 60 s, bounded by the 15-minute rule of Q7), using the gateway-forwarded token for user requests and the module's service account for background work; modules never see this mechanism (Q12).

### FR-5a First-admin bootstrap
- `SLG_BOOTSTRAP_ADMIN_GROUP` names an AD group. It seeds the `platform-admin` mapping when the database holds no admin mapping; afterwards admin rights come from stored mappings.
- AC1: With an empty database and the variable unset the core refuses to start.
- AC2: The last admin mapping cannot be deleted through the UI or API.
- AC3: Lock-out recovery: delete the admin mapping in the database (documented), set the variable, restart. A break-glass local Keycloak admin exists; its use is alerted and audited.
- AC4: Bootstrap seeding and break-glass use are audited.

### FR-6 Module SDK
Backend library (Spring) and frontend package. Only Spring and React modules are supported by the contract.
- Backend: token validation (issuer, per-module audience, signature, expiry, `azp`), permission checks, identity access, service-account client for module-to-module calls, logging with correlation ID, health endpoints.
- Frontend: identity (profile only; no token is ever available to JavaScript, ADR-0012), permission hook, `useTranslation`, locale formatters, theme and language hooks, state components (Loading, Empty, Error, Forbidden, NotFound, Offline, SessionExpired), close guard, live-region announce API, window integration. The frontend SDK provides the only HTTP client: it adds the CSRF header and `X-Correlation-Id` and maps gateway error codes (FR-11); raw `fetch` calls fail the CSRF check.
- AC1: SDK-provided context values and callbacks have stable identity (an unstable callback caused a render storm in the spike); a render-count test is part of the contract test.
- AC2: The SDK version is tied to the contract version. Only the current contract major is supported (Q13): registration rejects manifests for any other major, and a platform upgrade that raises the major is rolled out together with updated modules (see 10).

### FR-7 Module template
A template repository produces a compliant module that builds, tests (80 % gate), containerizes, validates its manifest, and can be registered by an administrator via FR-4.
- AC1: A module generated from the template passes `docs/reviews/module-registration-checklist.md` without edits.
- AC2: The template contains an `index.html` entry (also its standalone dev harness), `dts: false` for the federation plugin (otherwise the DTS worker crashes the dev server), the shared entries including the `react/` and `react-dom/` prefixes, and the `base` path `/modules/<id>/remote/`; a contract test builds it from a clean checkout.

### FR-8 Failure isolation
- AC1: A remote-entry load failure or runtime exception shows an error boundary with Retry and "Copy details" in that window only; shell and other windows stay operable (E2E test with a stopped module container).
- AC2: Module API calls are subject to the gateway timeout (FR-11 AC6; default 10 s); errors map to a standard problem-details body with a machine-readable `code` and the correlation ID.
- AC3: The registry and admin UI remain usable while any module is down.

### FR-9 Security audit log
Events: login, failed login (ingested from Keycloak events), logout, session revoke, mapping change, admin-role change, module register/upgrade/rollback/disable/enable/delete, bootstrap use, break-glass use, reads of the audit log, session termination by a security control (refresh-token reuse, administrative revoke, vault failure; no token content). Session expiry and refresh failures are logged as logout; ADR-0008 scope is unchanged. Service-account events and repeated 403s are not audited by the core (Q17, ADR-0008 unchanged). Fields: timestamp, event type, actor id, source IP, object, outcome, correlation ID. No request bodies or business data.
- AC1: Each event produces exactly one entry, verified by integration test.
- AC2: UPDATE/DELETE on the table by the application DB role fails; a daily hash-chain verification detects a modified row.

### FR-10 Health and diagnostics
Platform and per-module health/readiness; administrators get an operational overview.
- AC1: The core polls module readiness every 15 s (modules send no heartbeat); three consecutive failures mark a module DOWN. A DOWN module disappears from the launcher but stays in the registry; the gateway answers calls to it with 503 `MODULE_DOWN` without forwarding.

### FR-11 Gateway and session (backend-for-frontend)
All browser calls to the core and to module backends go through the core gateway on the shell origin (ADR-0012). The gateway routes `/modules/<id>/api`; the proxy serves static files only.
- AC1: Login: the pre-login state (OIDC `state`, `nonce`, PKCE verifier) lives in a separate `SameSite=Lax` cookie valid for 10 minutes. The callback response issues a fresh session id (no fixation). The session cookie is `__Host-slg-session` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, no `Domain`) with a 128-bit random id stored only as a hash. After login the return target must be a relative path matching `/app/<moduleId>/...`; anything else becomes `/`. Idle timeout 30 min (only user-initiated requests count, not polling marked `X-Slg-Background`), absolute session 10 h; Keycloak SSO and refresh-token lifetimes are at least that long.
- AC2: CSRF: every state-changing request needs the custom header and an `Origin` equal to the shell origin; a missing `Origin` on a non-GET request is rejected; GET requests never change state; logout is `POST` only. Streaming upgrades need a one-time ticket and the `Origin` check. Covered by tests.
- AC3: The gateway obtains an audience-bound access token per module (refresh-token down-scoping, or token exchange for the confidential client if production Keycloak supports it, Q26), forwards it, and caches it per session and module until expiry; the cache is dropped on module disable, session revoke, mapping change and logout. Forwarded tokens carry identity (`sub`) but no group list; groups stay in the server-side session. Module-scoped tokens are held in memory only.
- AC4: Errors: an expired or revoked session yields HTTP 401 with code `SESSION_EXPIRED` (shows UI-13); a module's own 401/403 passes through with a different code and never triggers UI-13. If Keycloak is unreachable during a refresh the gateway keeps the session and answers 401 `AUTH_UNAVAILABLE` once the cached module token has expired (at most 5 min), without showing SessionExpired. If a refresh response is lost or refused, the session is terminated. Streams are closed when the session ends or the token cannot be renewed.
- AC5: Refresh tokens are encrypted at rest (AES-256-GCM, key from a Docker secret with a key id for rotation) in the session store, which is excluded from backups; after a restore all sessions are invalid. Refresh is serialised with a row lock and attempted 60 s before expiry; last access is written at most once per minute; expired sessions are purged hourly.
- AC6: The gateway streams request and response bodies without buffering and passes SSE; WebSocket is not supported in phase 1. Per-entry-point overrides come from the manifest (`timeoutSeconds`, `maxBodyMB`); idle streams close after 60 s without data. The implementation uses a non-blocking client or virtual threads (fixed in the skeleton ADR).
- AC7: The gateway forwards a request to `/modules/<id>/api` only if the module is enabled and the user holds at least one permission of that module. Core administration APIs require the corresponding admin permission and, for mapping changes, registration and revoke-all, a re-authentication not older than 5 minutes (`max_age`).
- AC8: The session store is PostgreSQL (proposed, to be confirmed in the skeleton ADR, Q28). If it is unavailable the gateway answers 503 `SESSION_STORE_DOWN`, with no fallback to unauthenticated or in-memory sessions. A DOWN module yields 503 `MODULE_DOWN`, counted per module.
- AC9: Timeouts, correlation ids (a new id per inbound request; inbound values are accepted only from the proxy) and problem-details errors follow FR-8; the gateway adds no business logic.

## 5. Non-functional requirements
- NFR-1 Security (verifiable): (a) OWASP ASVS Level 2 baseline tracked in a checklist; (b) CSP `default-src 'none'; script-src 'self' 'nonce-<per request>'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; frame-src 'none'; form-action 'self'; worker-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'` without `unsafe-eval`/`unsafe-inline` (the nonce protects the inline import map that carries the integrity data, ADR-0010), plus HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy and Permissions-Policy on every location, verified by an automated test that also asserts zero CSP violations during sign-in, theme and language switch, module errors and session expiry; (c) all permission checks server-side, deny by default, tested per endpoint; (d) dependency, image and secret scanning in CI of core and template, builds fail on high/critical findings, SBOM per release; (e) no personal data in application logs. The shell document is sent with `Cache-Control: no-store`; the nonce has at least 128 bits; inline style attributes needed by the window manager are set through the CSSOM and a test fails if `unsafe-inline` becomes necessary.
- NFR-2 Quality: every Maven module enforces JaCoCo line coverage >= 80 % and every frontend package enforces Vitest (v8) line coverage >= 80 %. The gate is per language and per repository; there is no cross-language average, which satisfies and tightens the project rule "global >= 80 %". CI also fails if coverage drops by more than 2 points against main. Exclusions only via a reviewed list in the build. Module repositories inherit the gate from the template. Coverage includes integration tests (Failsafe merged into the JaCoCo report); the packages `gateway`, `session` and `security` require >= 90 % line coverage; module CI uploads the coverage report as evidence and the registration checklist verifies that the gate configuration equals the template's. Module CI also signs the image (cosign) and prints the digest.
- NFR-3 i18n: no hard-coded user-visible strings; CI enforces key parity de/en and placeholder consistency; pseudo-locale visual tests.
- NFR-4 Accessibility: WCAG 2.2 Level AA for shell and modules (EN 301 549 aligned), automated axe checks in E2E tests plus a manual screen-reader pass per UI-20.
- NFR-5 Operations: single-host Docker Compose; configuration via environment and Docker secrets; documented, tested upgrade, rollback, backup and restore. Backup: nightly `pg_dump` per database (platform, Keycloak, each module schema) plus realm export and compose/secret inventory, encrypted (age), 14 daily and 8 weekly copies on an SMB share outside the host. The SMB target (host, share, credentials) is configuration, not code (Q9, Q18). RPO <= 24 h, RTO <= 4 h. A restore onto a clean host is rehearsed before go-live and at least twice a year. The module store volume is backed up with the databases (encrypted `tar`); the session table is excluded. A restore rehearsal checks that all registry hashes match the restored store.
- NFR-6 Performance (p95, 20 concurrent users, warm cache, LAN): shell first load <= 2 s; sign-in to usable desktop <= 3 s; module window open <= 1.5 s warm / 3 s cold; core API read <= 200 ms, write <= 500 ms; SDK permission check <= 5 ms (local); window drag 60 fps with 5 windows; core cold start to ready <= 60 s. Gateway budget: the gateway adds <= 15 ms p95 to a proxied call (stub upstream, 50 req/s, 20 concurrent users) and sustains 100 req/s on the target host; a load test (k6) is part of the nightly E2E run.
- NFR-7 Resource budget (host: 8 vCPU / 32 GB RAM / 500 GB disk, Q18): Keycloak 1.5 GB, PostgreSQL 1 GB, core backend 1 GB (gateway, sessions, registry; JVM heap capped at 60 % of the limit, to be measured under the FR-11 load test), proxy 128 MB, each module backend <= 512 MB (`mem_limit`, 1 CPU). With 30 % headroom about 24 modules fit by memory; CPU, disk (images, store, backups, logs) and the 8 cores are tracked as modules are added. Exceeding the budget requires an ADR on host sizing. The template ships these limits.
- NFR-8 Observability: JSON logs to stdout with timestamp, level, service, `correlationId` (`X-Correlation-Id`), no personal content; log rotation 10 MB x 5; Spring Actuator liveness/readiness and Prometheus metrics on an internal port; Prometheus/Grafana optional (compose profile), not required in phase 1. Gateway metrics (Micrometer, tag `module` only, no user ids): `slg_gateway_requests_total{module,status_class}`, `slg_gateway_request_seconds{module}`, `slg_gateway_upstream_timeouts_total{module}`, `slg_session_active`, `slg_token_refresh_failures_total`, `slg_store_verify_failures_total`. Phase 1 alert channel: e-mail through a configurable SMTP relay (Q30) for AD down, break-glass use, certificate expiry, backup failure and a module DOWN for more than 5 minutes; the admin overview shows the same states.
- NFR-9 Time and TLS: NTP-synchronised hosts; tokens with skew > 60 s are rejected and readiness reports drift; TLS at the proxy with a certificate from the internal Windows Active Directory CA (enrollment process to be agreed with the CA owner), trusted by all JVMs and Keycloak (also for LDAPS); certificates expiring in < 30 days raise a health warning. Two certificates are needed (shell host name and Keycloak host name); the expiry warning covers both.

## 6. UI/UX
Details apply to the shell and bind modules through the SDK, the design system and the registration checklist.

### 6.1 Window manager
- UI-1 Windows form a stack; focusing or activating raises a window; exactly one is active; minimized windows keep their stack position.
- UI-2 Opening a window moves focus into it; closing or minimizing returns focus to the opener, else the next taskbar button; focus is never lost to `body`.
- UI-3 Dragging to the left/right edge snaps to half, to the top maximizes. Keyboard equivalents via the window menu (`Alt+Space`, and a visible menu button as primary path because the browser or OS may intercept `Alt+Space`, notably in Edge on Windows): snap left/right, maximize, restore, move, resize. Free move and resize also have a single-pointer alternative (WCAG 2.5.7), for example step buttons in the window menu. The title bar always stays reachable; windows are clamped on viewport resize.
- UI-4 Each entry point is `singleton` (reopening focuses it) or `multiInstance` (titled "Name (n)").
- UI-5 Deep links use the History API: `/app/<moduleId>/<route>`, with the proxy falling back to the shell. The shell opens the window after authorization; unauthorized or unknown targets show the same neutral state (no leak of module existence). Only the active window is reflected in the URL; back/forward navigate within the active module route. Module backends and remotes live under `/modules/<id>/api` and `/modules/<id>/remote` (distinct from shell paths).
- UI-6 Open windows, geometry, state, stack order and module route are persisted per user and per device class (debounced). Restore loads only active and visible windows lazily. Last write wins across tabs/devices.
- UI-7 Modules work at a minimum window size of 480x320 px and reflow via container queries/ResizeObserver.
- UI-8 A module may register a close guard; the shell asks for confirmation on close, sign-out and session-expiry warning.

### 6.2 Launcher, desktop, notifications
- UI-9 Launcher lists permitted, enabled modules by manifest category with type-to-filter search, opens with `Meta`/`Ctrl+Esc`, and lists recent items. Texts come from the module i18n bundle and are sorted by active locale.
- UI-10 Users may pin modules as desktop icons (grid, stored per user); unavailable modules stay visible but disabled with the reason.
- UI-11 The tray shows clock, notification centre, and language/theme/user menu. Toasts have severity; warnings and errors do not auto-dismiss; announced via `aria-live`. Delivery and storage belong to the notification capability (not in this PRD).
- UI-12 Narrow viewport (< 768 px or coarse pointer): single-window mode with maximized window, bottom switcher, full-screen launcher, no move/resize/snap, separate stored layout. Modules may declare `minViewport`. Touch and kiosk optimization is out of scope (Q8).

### 6.3 States
Shared components: Loading (skeleton after 300 ms, spinner after 1 s, timeout message after 15 s), Empty, Error (what happened, correlation ID, Retry), Forbidden, NotFound, Offline (shell banner; modules mark stale data), SessionExpired.
- UI-13 When the gateway reports an expired or revoked session (FR-11 AC4) the shell shows a non-modal banner (`role="alert"`, localised, without moving focus) with a keyboard-reachable "Sign in again" button. It opens the login in a popup, falling back to a new tab if blocked. Completion is detected through `BroadcastChannel` or session polling, not `window.opener`. On success the shell refreshes the CSRF and permission state, keeps module state and returns focus to the previously focused control; if a different user signed in, all windows are reset. Only after failed or cancelled re-authentication are windows replaced by SessionExpired (focus moves to its heading). An idle warning 2 minutes before the idle timeout (`role="alertdialog"`, extendable) is proposed (Q29). Verified in the skeleton by automated tests (focus, role, popup blocked, other user) and the manual screen-reader pass.
- UI-14 A failed remote is isolated per window by an error boundary (FR-8).
- UI-30 The shell never displays problem-details `title` or `detail` verbatim. It maps `code` (with `params`) to i18n keys of the shell or the module bundle; unknown codes show a localised generic message with the correlation ID. Each module declares its error codes in the manifest and registration fails if a code lacks a de/en key; the gateway forwards `Accept-Language`.
- UI-31 Gateway 502/503/504, timeouts and network failures show the Error state with Retry (backoff) in the affected window; a failing health poll shows the Offline banner (`role="status"`), because `navigator.onLine` is unreliable; 401 shows UI-13; 403 shows Forbidden; a module disabled while open shows an Unavailable state. Loading regions set `aria-busy`.

### 6.4 Keyboard model
- UI-15 Skip links; roving tabindex in taskbar and desktop icons; `F6`/`Shift+F6` cycle shell regions; configurable window switch shortcut; `Alt+Space` window menu; keyboard move/resize (arrows, `Enter` confirm, `Esc` cancel); `Esc` closes menus and popovers, never windows; the window menu follows the menu keyboard pattern (arrows, Home/End, roving tabindex, Tab closes it); `?` shows shortcuts. Modules register shortcuts only through the SDK. Single-character shortcuts (`?`, type-to-filter) are inactive in editable fields. Every shortcut is listed in the `?` help, exposed through `aria-keyshortcuts` and can be remapped or disabled. Defaults avoid browser and OS reserved keys: `F6`, `Ctrl+Esc` and `Ctrl+Alt+Arrow` are replaced if they fail the Edge/Windows check; SDK shortcut registration rejects collisions. Esc precedence: move mode, then menu, then dialog. Keyboard move/resize mode shows a visible indicator and announces entry and exit.
- UI-16 No keyboard traps. Modal dialogs are scoped to their window (the window's other content and other windows are inert, the launcher and taskbar remain operable) and return focus to the invoking control; shell-level dialogs are page-modal. Dialogs focus the least destructive control first; Escape cancels. If the launcher opens another window while a dialog is open, the new window opens behind the dialog and the dialog keeps focus.

### 6.5 Screen reader semantics
- UI-17 The desktop is a document, not an `application` role. Landmarks: taskbar (`navigation`), active window region (`main`), tray (`complementary`). Each window is a labelled non-modal dialog/region. DOM order = tab order = visual stack order; no positive `tabindex`. Non-active windows are listed in the taskbar (`aria-current`) and minimized content is `inert`. When all windows are minimized the desk is the `main` landmark. Raising a window must not drop focus from a focused control, whether by `Element.moveBefore()` or by `z-index` stacking with a documented tab-order exception (verified in the skeleton). Raising a window by pointer or Tab never blurs a focused control (automated test: type after clicking and after Tabbing into a background window; iframe content is not reloaded). Tab order visits background windows before the active one (WCAG 2.4.3); this is a documented trade-off mitigated by skip links and F6. Windows are `region` landmarks with `aria-labelledby`; modules must not emit `main` or `h1`; `document.title` is "<window title> - SLG OS".
- UI-18 Window open/close/minimize/switch is announced through a polite live region; repeated identical messages are made distinguishable. Free move and resize have a single-pointer alternative (step buttons in the window menu, WCAG 2.5.7) and a keyboard alternative (WCAG 2.1.1).
- UI-19 Each window's title is its `h1`; module headings start below it.
- UI-20 A manual screen-reader pass (NVDA + Firefox, JAWS + Chrome, VoiceOver if used) is part of the release checklist for shell changes.

### 6.6 Design system and theming
- UI-21 One versioned package `@slg-os/design-system` (React components on Bootstrap 5.3, shared as a Module Federation singleton) with `--slg-*` design tokens mapped onto Bootstrap variables. Button and text tokens meet WCAG AA on every shell background (Bootstrap's default `btn-outline-secondary` fails contrast on tertiary backgrounds); the launcher and shell reflow without horizontal scroll at 320 CSS px (WCAG 1.4.10), while 360 px stays the supported minimum for narrow mode; tokens meet 4.5:1 for text and 3:1 for non-text elements and focus indicators, and focus remains visible in forced-colors mode. Modules use tokens and components only; raw hex colours, global CSS, own Bootstrap build, `!important` and global selectors are rejected by a lint rule in the template and by the registration checklist. iframe-embedded tools are an exception that requires an ADR and must provide a meaningful title, focus hand-off and theme/language signals where possible (Q21).
- UI-22 The shell sets `data-bs-theme` on `<html>`; modules must render correctly in both themes, never read `prefers-color-scheme`, and switch live. Default: OS preference at first login, then the user's choice. A contract test renders the reference module in both themes.
- UI-23 Respect `prefers-reduced-motion` and `forced-colors`; 200 % zoom and 400 % reflow without loss of function. No animation may exist without a `prefers-reduced-motion` rule; a test enforces this.

### 6.7 i18n and locale
- UI-24 Resolution order: user preference, browser language, `de`. `<html lang>` follows the language; text in another language is marked with `lang`. The spike default `en` is replaced by this order. Before sign-in the language is the browser language, then `de`; the Keycloak login theme and the popup pages are bilingual and themed; the preference is stored server-side per user. The English fallback of a name carries `lang="en"`.
- UI-25 The language switch applies at runtime to shell and all windows via the SDK (i18next-compatible `useTranslation`, language-changed event).
- UI-26 Modules ship `i18n/{lang}/{moduleId}.json` (namespace = module id, ICU messages) referenced by the manifest, loaded lazily. A missing key falls back to `en`, then shows the key and logs a warning (a defect). CI checks parity, unused/missing keys and placeholders. Registration is rejected if either language is incomplete.
- UI-27 Formats via `Intl` with `de-DE` and `en-GB`; modules use SDK formatters only. Display time zone Europe/Berlin, storage UTC; sorting via `Intl.Collator`.
- UI-28 Layouts tolerate +35 % German text length; a pseudo-locale (`de-XL`) is used in dev builds and visual tests; icons never carry meaning alone.

### 6.8 Browser support
- UI-29 Desktop: last two majors of Edge/Chrome, Firefox ESR and current, Safari current and previous major. Mobile (narrow mode) best effort on current Safari iOS and Chrome Android. Minimum 1280x720 (desktop mode) and 360 px width (narrow mode). Printing is the module's concern.

## 7. Data and interfaces
- PostgreSQL for platform data (registry, group mappings, preferences/layouts, audit log); versioned migrations.
- Module database provisioning: on registration the core's provisioner (dedicated privileged role) creates schema `mod_<id>` and role `mod_<id>` limited to its own schema and delivers the password as a Docker secret; the same step provisions the module's Keycloak audience, scope and service-account client (FR-4). Modules run their own Flyway migrations at startup with that role; failure keeps readiness DOWN and the previous container running. Migrations must be backward compatible with the previous module version (expand/contract) to allow rollback.
- Shell document: the core generates the shell HTML per request with a CSP nonce and an inline import map whose `integrity` section lists the SHA-384 hash of every registered JavaScript file; the map and the module list come from the same registry snapshot, and an unlisted module file is an error (ADR-0010).
- Contract artifacts: manifest schema (versioned JSON schema), SDK, REST conventions (versioned, OpenAPI, breaking-change diff in CI), federated remote conventions.

## 8. Security and data protection
- 8.1 OIDC and session (ADR-0012): the core is a confidential client using authorization code + PKCE (S256) with exact redirect URIs (no HTTP redirect URIs in production). Tokens stay on the server: the browser holds an `HttpOnly`, `Secure`, `SameSite=Strict` session cookie; CSRF protection per FR-11. Access tokens <= 5 min; rotating single-use refresh tokens with serialised use; idle timeout 30 min, absolute session 10 h. Kerberos/SPNEGO SSO is not enabled (Q1). Module backends accept bearer tokens forwarded by the gateway and validate issuer, per-module audience, signature (cached JWKS), expiry and `azp`.
- 8.2 Logout and revocation: RP-initiated logout; back-channel logout (delivered from Keycloak to the core over the internal compose network, not through the public proxy) invalidates the server-side session at once, so no denylist is needed. A user disabled in AD or a changed group loses access within the Keycloak LDAP sync interval (set to 10 min) plus one module-token lifetime (5 min), which meets the 15-minute target of Q7. Administrators can revoke all sessions (audited).
- 8.3 Trust boundary of federated remotes: remotes run in the shell origin. With the BFF a compromised module build cannot read or exfiltrate a token, but it can still send authenticated requests as the signed-in user, read other windows' DOM and send data to same-origin endpoints; `connect-src` does not stop exfiltration by navigation, prefetch or WebRTC. Backend authorisation per request therefore stays the enforcement point, and ADR-0007 is a security control: only admin-approved remote entries on the platform origin are loaded; integrity of every JavaScript file is enforced through the import map and the immutable content-addressed store (FR-4 AC7, ADR-0010; verified in Chromium only, Firefox and Safari are release gates); CSS, JSON and other runtime-fetched files are not covered, so registration restricts file types; versions are promoted four-eyes; no external CDNs or scripts at runtime; module CI uses locked dependencies, scanning and signed images; the shell never renders module-supplied HTML. Down-scoped module tokens are not a boundary against a hostile module. Whether the owner accepts the remaining risk is Q24. The gateway cannot tell which module's script issued a same-origin request, so a compromised module can call any module API or core endpoint the user may call; mitigations are least-privilege role mapping, step-up re-authentication for administrative actions (FR-11 AC7), per-module backend authorisation and ADR-0007, and no further isolation is claimed.
- 8.4 Service-to-service: each module backend has a Keycloak service account (client credentials) with audience-restricted least-privilege scopes, secrets from Docker secrets rotated at least yearly. User identity reaches modules only through the gateway-forwarded, audience-bound token. A module backend accepts a bearer token only if issuer, signature, expiry and audience match and `azp` is the core BFF client (forwarded user tokens) or a registered service-account client (module-to-module). Each target module lists the caller modules allowed to call it; the caller's token carries the target audience only if allowed. A user id passed between modules is informational and never used for authorisation. Module containers are on an internal network, not published on host ports.
- 8.5 Reverse proxy: only the proxy is exposed (TLS 1.2+, HSTS). Keycloak is served from its own origin (a separate host name), not under the shell origin; only the login, logout, token and well-known endpoints are exposed publicly, the admin console and the master realm are not (Q27). Header stripping and traversal tests run against the production module locations with positive assertions (404/400 and no content), and identity headers are handled by an allowlist. It strips inbound `X-Forwarded-*`, `Forwarded` and identity headers and sets them itself; backends trust such headers only from the proxy network; identity comes from validated tokens, never headers. Keycloak has `frame-ancestors 'none'`, empty web origins and exact redirect and post-logout URIs; the shell CSP is tested against the full login and logout redirect chain (including `form-action`); the core reaches Keycloak by the public issuer URL through a Docker network alias.
- 8.6 Manifest validation and SSRF: JSON schema validation with size limit; server-side fetches only to an allowlist of internal module service names, http(s) only, fixed ports, no redirects, resolved IPs must not be loopback, link-local or metadata addresses, 5 s timeout. The connection is made to the validated IP (no second DNS resolution, against rebinding), and the allowlist is derived from registered module entries, not from manifest content.
- 8.7 Keycloak and AD (service account and certificate rotation operated by the owner's team, Q20): LDAPS with certificate validation; dedicated read-only AD service account limited to the needed OUs; brute-force detection with tuned parameters; explicit refresh-token and session lifetimes; no `start-dev` mode; the built-in client scopes are defined explicitly in the committed realm file (importing a realm with custom scopes otherwise drops `profile`, `email`, `roles`, `web-origins`) and an import smoke test checks them; admin console not routed via the public proxy; realm export contains no secrets; defined patch cadence.
- 8.8 Audit log integrity: see FR-9. Exports go to the read-only backup.
- 8.9 Data protection (DSGVO): personal data processed by the core: user id, display name, email, AD group names (server-side session), IP address (audit), UI preferences, technical logs, and session data (session id hash, IP, user agent, encrypted tokens). Purpose: authentication, authorization, security accountability, operation. Legal basis proposal: Art. 6(1)(f) DSGVO and § 26 BDSG; a works council agreement is required before go-live because login and audit data could enable behaviour or performance monitoring (BetrVG § 87(1) Nr. 6); the audit log is not used for performance evaluation. Retention (owner decision, DPO and works council sign-off still required, Q23): audit log 6 months, application logs 30 days, session data until logout or the absolute timeout plus 24 hours, preferences 90 days after the account disappears from AD. Audit log access for platform administrators (no auditor role in phase 1, Q14), the access itself audited. Logs contain pseudonymous ids, never names, emails or content. An Art. 30 record is created and the DPO decides on a DPIA before go-live. The core can export and delete a user's preferences and look up audit entries on request.
- 8.10 Secrets: Docker secrets from files (0400), no secrets in images, repositories or realm export, per-module DB credentials.

## 9. Testing strategy
- Java (proposal, verified against official documentation when the skeleton is created, Q2/Q3): latest LTS JDK (JDK 25 proposed, 21 fallback) and Spring Boot 4.x, Maven, JUnit 5, AssertJ, Mockito, Spring Boot Test, Testcontainers (PostgreSQL; Keycloak with the versioned realm import; LDAP stub for groups), Spring Cloud Contract or Pact for REST contracts, ArchUnit ("no cross-schema access"), JaCoCo.
- Frontend: Vitest + Testing Library (coverage v8), MSW, Playwright for E2E including axe checks, the Module Federation plugin selected in the spike.
- Module Federation: the shell has a mock remote fixture; a Playwright contract test loads the template module's built `remoteEntry.js` and asserts mount, theme/language propagation, error boundary on 500/unreachable remote, and shared singleton versions.
- Contract tests: manifest schema against golden valid/invalid manifests; SDK against the previous contract version; OpenAPI diff (oasdiff) fails on breaking changes.
- E2E runs on the compose stack with production-mode Keycloak (PostgreSQL, `kc.sh start`, no `start-dev`) and the real proxy with both host names: sign-in, launcher filtering, permission denial (403), failing module, AD-unavailable login error, module upgrade and rollback, session expiry without state loss. Chaos cases: stop Keycloak, stop PostgreSQL, restart the core backend (sessions survive), stop a module. Gateway tests: concurrent refresh, session-store restart, large body, timeout, module token cache. The cross-browser run uses Playwright Firefox and WebKit in CI; Safari proper is a manual gate on a Mac.
- Spike suite as seed: `spikes/shell/tests` may seed the Module Federation contract test after it is parametrised by base URL and module id; its hostile-remote tests are characterisation tests (they assert known residual exposure, not controls) and are marked as such. Additional tests: render-count test, CSRF tests, Keycloak realm import smoke test, CSP and integrity checks in Firefox and WebKit, restore rehearsal.
- Manual gates (owner assigned by the platform team, Q25b; evidence: a dated checklist in `docs/reviews/`): scripted NVDA + Firefox and JAWS + Chrome pass (window switch, menu, dialog, session expiry, announcements), `Alt+Space` and shortcut conflicts in Edge on Windows, 200 %/400 % zoom, forced colors, measurements on an office PC. Automated: axe (all impacts) with menu, dialog, banner and narrow mode open; pseudo-locale visual tests; a `no-literal-string` lint rule covering `title`, `aria-label`, `alt` and `index.html`.
- Module CI (from the template): build, unit tests with coverage gate, contract test against the SDK version, dependency scan, image build and push, manifest validation.

## 10. Rollout and operations
- Topology: compose services `proxy` (static files from the read-only store volume, TLS for both host names), `keycloak` (own host name), `postgres`, `core-backend` (API, gateway, shell document), plus modules; no separate shell container. Health-gated startup order: postgres, keycloak, core-backend, proxy. The core starts when Keycloak is absent and reports readiness DEGRADED (sign-in unavailable) instead of failing; modules start after the core-backend and retry.
- Deploy/discovery: modules publish semver-tagged images to GitHub Container Registry (ghcr.io; the host pulls, outbound connections are allowed, Q10/Q18) and a manifest. Operators add a compose file for the module (pinned by image digest) and an administrator registers the manifest (FR-4). Module API calls go through the core gateway (FR-11); `/modules/<id>/remote` is served from the immutable content-addressed store (long-lived `immutable` cache headers for hashed files, `no-store` for the registry) and routes are generated from registry entries; the registry, not Docker labels, is the source of truth. The core polls module health (FR-10); modules send no heartbeat.
- Upgrade/rollback: change digest, `up -d <id>`, readiness-gated; rollback = previous digest, permitted because migrations are expand/contract. Core upgrades follow the same path after a backup.
- Contract versioning: SemVer `major.minor`; minor is additive, major is breaking. The platform supports only the current major (Q13): a breaking contract change is released together with updated modules, and modules not yet updated are disabled by registration validation until they are. React major and design-system range are pinned per contract major.
- Failure modes: AD down: existing sessions work, new logins fail with a localized error and an alert. Keycloak down: the gateway keeps valid module tokens until they expire (<= 5 min), then answers 401 `AUTH_UNAVAILABLE`; sessions are kept. PostgreSQL down: 503 `SESSION_STORE_DOWN` (FR-11 AC8). Core restart: sessions persist, in-flight requests fail and the SDK retries once. Module down: window error boundary (FR-8) and 503 `MODULE_DOWN`. Module backends refetch the JWKS on an unknown key id.
- Phase 1a (walking skeleton): proxy with two host names, production-mode Keycloak with AD, core with BFF and PostgreSQL sessions, shell with two windows, registry through the admin API, one reference module, CI with coverage gates, E2E against the real stack, `pg_dump` backup script.
- Phase 1b: admin UI and permission mapping, audit log with hash chain, immutable store and provisioner, module template and SDK, alert mails, store backup.
- Phase 1c (go-live gates): restore rehearsal on a clean host, Firefox/WebKit/Safari/Edge gates, load test, manual screen-reader pass, Q23 to Q25 closed.

## 11. Decisions and open questions
Owner is Benjamin Honauer unless stated. Decisions were taken in the owner interview on 2026-10-19.

### 11.1 Decisions
| # | Decision | Effect in this PRD |
|---|---|---|
| Q1 | No Kerberos/SPNEGO SSO for now; Keycloak login form against AD. Can be enabled later without architecture change. | 8.1, ADR-0001 stays valid |
| Q2/Q3 | JDK/Spring Boot versions and test tooling named in section 9 are verified against official documentation when the project skeleton is created, then recorded in `CLAUDE.md`. | Section 9 |
| Q4 | Decided by the spike: in-house window manager (ADR-0011 is proposed; the decision becomes final with its acceptance and the open gaps closed). | UI-1 to UI-8 |
| Q6 | Audit log retention is **6 months** (working value; DPO and works council must still sign off before go-live). | 8.9 |
| Q7 | Permission changes and disabled AD users take effect within **15 minutes** (access token 5 min, LDAP sync up to 15 min). | FR-5, 8.2 |
| Q8 | Desktop only; narrow mode is a fallback, no touch or kiosk optimization. | UI-12 |
| Q9 | Docker secrets from files; nightly encrypted backup to a **configurable SMB target** outside the host. | NFR-5, 8.10 |
| Q10 | Module images are published to GitHub Container Registry (ghcr.io); the host may pull (outbound connections are allowed). | Section 10 |
| Q11 | **Superseded by ADR-0012 (2026-10-19):** the core is a backend-for-frontend; tokens never reach the browser. | 8.1, FR-11 |
| Q12 | The SDK resolves effective permissions via the core API with a short cache; tokens carry identity and AD groups only. | FR-5 |
| Q12b | **Superseded by ADR-0012:** with the BFF no token can be read by a module; the remaining risk is stated in 8.3 and goes to the owner as Q24. | 8.3 |
| Q13 | Only the **current contract major** is supported. Every breaking contract change requires all modules to be updated before or together with the platform upgrade. | FR-6, section 10 |
| Q14 | No auditor role in phase 1; platform administrators read the audit log (reads are audited). | Section 3, 8.9 |
| Q15 | Client credentials service accounts per module. | 8.4 |
| Q16 | Modules are registered by administrators only; no self-registration. | FR-4 |
| Q17 | ADR-0008 stays as is; FR-9 is limited to the events it names plus unavoidable administrative events. | FR-9 |
| Q18 | Host: 8 vCPU, 32 GB RAM, 500 GB disk. Internal CA is the Windows Active Directory CA. Outbound connections are allowed. An SMB backup target exists and its location is configuration. | NFR-5, NFR-7, NFR-9 |
| Q19 | Deep links use the History API with proxy fallback to the shell. | UI-5 |
| Q20 | The owner's team operates the read-only AD service account and LDAPS certificate rotation. | 8.7 |
| Q21 | iframe exceptions need a meaningful title, focus hand-off and theme/language signals where possible, and require an ADR. | UI-21 |
| Q22 | WCAG 2.2 AA is sufficient; no stricter legal or works-council requirement known. | NFR-4 |

Resolved by the spike: Q5 (Module Federation with Vite is viable in Chromium, ADR-0010 proposed) and Q19 (History API deep links work).

### 11.2 Still open
| # | Question | Owner | Needed by |
|---|---|---|---|
| Q23 | Works council agreement and DPO decision (Art. 30 record, DPIA) covering 8.9; the DPO is also informed of the remaining risk in 8.3 (Q24) | Benjamin Honauer | before go-live |
| Q24 | Does the owner accept the remaining risk after the BFF (8.3: authenticated requests as the user, DOM access, same-origin exfiltration), and is the DPO informed? | Benjamin Honauer | before go-live |
| Q25a | Firefox, WebKit and Safari checks of import-map integrity and CSP | platform team | before go-live |
| Q25b | Owner of the manual gates: NVDA + Firefox and JAWS + Chrome pass, Edge on an office PC with `Alt+Space` and shortcut conflicts, measurements | Benjamin Honauer | phase 1c |
| Q26 | Production-mode Keycloak: token exchange (confidential client) or refresh-token down-scoping for per-module audiences | platform team | skeleton |
| Q27 | Host names, certificates (Windows AD CA) and routing for Keycloak on its own origin, including the internal back-channel route | Benjamin Honauer | skeleton |
| Q28 | Confirm the PostgreSQL-backed gateway session store in the skeleton ADR | platform team | skeleton |
| Q29 | Idle-timeout warning 2 minutes before the idle timeout (proposed: yes) | Benjamin Honauer | PRD approval |
| Q30 | SMTP relay for the phase 1 alert mails (NFR-8) | Benjamin Honauer | skeleton |

## 12. Review log
Four independent review agents (fresh context, no authorship of this PRD) reviewed it on 2026-10-19. Each wrote findings and proposed text; the author integrated them, since concurrent edits to one file would conflict.

| Perspective | Findings | Result |
|---|---|---|
| Requirements completeness, permissions, contract | 16 (6 high) | FR acceptance criteria, FR-4/4a/5/5a rewritten, manifest lifecycle, FR-8/9 criteria, Q11-Q16 |
| Security and data protection | 14 (5 high) | NFR-1, section 8 rewritten, bootstrap and break-glass, SSRF rules, audit integrity, DSGVO and works council |
| UI/UX, accessibility, i18n | 12 (5 high) | Section 6 written (UI-1 to UI-29), WCAG 2.2 AA, i18n and theme contracts |
| Operations and testability | 12 (5 high) | NFR-2, 5-9, section 7 DB provisioning, sections 9 and 10, versioning policy |

Conflicts between reviews and how they were resolved:
- Bootstrap: one review proposed a permanent built-in admin role, another a seed-only group; seed-only with an undeletable last admin mapping and a break-glass account was chosen.
- Registration: one review proposed module self-registration, others admin-only registration; admin-only was chosen, modules send only health heartbeats (Q16).
- Paths: shell deep links use `/app/<id>`, module backends and remotes `/modules/<id>/...` to avoid a clash.
- Session lifetime: the 10 h absolute session from the security review was used instead of the 8 h from the operations review.
- Version numbers (JDK 25, Spring Boot 4.x, plugins) come from the reviewer's knowledge and are unverified; they stay proposals until the spike.
- The security review asked for a broader audit event list than ADR-0008 states; the PRD includes it and raises Q17 instead of editing the accepted ADR.

Owner interview 2026-10-19: 21 of the 22 original open questions were decided (section 11.1). Consequences applied: ADR-0008 stays unchanged, so the broader audit events proposed by the security review were removed from FR-9; supporting only the current contract major removes the N-1 compatibility proposed by the operations review; the 32 GB host raises the module capacity estimate. Q5 was resolved by the spike (section 11.1); Q23 (works council and DPO) was added.

Revision 2 (2026-10-19): applied the spike findings and ADR-0012 (BFF) to FR-1, FR-4, FR-5, FR-6, FR-7, new FR-11, NFR-1, UI-3, UI-13, UI-15 to UI-17, UI-21 and sections 7 to 11. Four independent review agents then reviewed revision 2: requirements and consistency (17 findings, 6 high), security (17, 4 high), UI/UX and accessibility (13, 5 high), operations and testability (10, 5 high). Integrated: BFF propagation (8.4, `azp` rule, per-module Keycloak provisioning, forwarded tokens without groups), FR-11 rewritten (login flow and cookie, CSRF, gateway authorisation and step-up, streaming and limits, session store and token vault, error codes), immutable store details, new UI-30 and UI-31, rewritten UI-13 and UI-18, shortcut and focus rules, failure modes, phases 1a to 1c, test strategy, metrics and alerts, resource budget (core backend 1 GB), and the open-question list (Q23 to Q30).

Resolutions where reviews differed: gateway timeout and body overrides use the operations review's names (`timeoutSeconds` default 10 max 300, `maxBodyMB` default 10 max 100) while WebSocket stays out of phase 1 (security and requirements reviews); the gateway latency budget is 15 ms p95 at 50 req/s; the 15-minute rule of Q7 is met by an LDAP sync interval of 10 minutes plus the 5-minute module token; the core polls module health instead of modules sending heartbeats.

Not verifiable before the skeleton: token exchange versus down-scoping in production-mode Keycloak (Q26), the PostgreSQL session store (Q28), gateway performance. A final consistency pass of revision 2 is part of the skeleton's first PRD review.