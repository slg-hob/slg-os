# PRD-0001: Platform core: shell, authentication, authorization, module contract

- Status: Revision 2 draft. Revision 1 was independently reviewed and the owner decisions of 2026-10-19 are in section 11. Revision 2 applies the spike findings (PRD-0002, ADR-0010 to 0012) and the owner's decision for a backend-for-frontend; it still needs an independent review and owner approval.
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
- Manifest fields: `id` (unique, immutable, `[a-z][a-z0-9-]{2,31}`), `name`, `version` (SemVer), `contractVersion` (range), `icon`, i18n resource paths for de and en, `entryPoints` (federated remote URL, API base path, health URL; each with `instanceMode` `singleton|multiInstance`, `minWindowSize`, `minViewport`, `category`), `permissions[]` (names `<moduleId>.<resource>.<action>` with de/en labels), optional `suggestedRoles[]`, `requires` (React range, design-system range, per ADR-0005 and ADR-0010) and `closeGuard` support. Integrity hashes are computed by the core at registration for every JavaScript file of the build and are never supplied by the module (8.3).
- AC1: A manifest with a missing or unknown field, duplicate id, incomplete language, invalid permission name, unsupported contract version, or oversize payload (> 256 KB) is rejected; the response lists every violation with field path and message (de/en).
- AC2: Registering a higher `version` of an existing id is an upgrade; the previous manifest is kept and one admin action rolls back to it.
- AC3: An upgrade that removes or renames a permission, or raises the required contract major, is rejected unless flagged `breaking`; breaking upgrades list affected mappings and need explicit confirmation. Mappings of removed permissions are marked orphaned, never silently deleted.
- AC4: Every registration, upgrade, rollback, disable, enable and deletion creates an audit entry.
- AC5: Server-side fetches of manifest-referenced URLs obey the SSRF rules in 8.6.
- AC6: A manifest whose `requires` is not satisfied by the React and design-system versions the shell provides is rejected with a clear message. This check is mandatory; the Module Federation runtime version check is not a control (it fails closed or open depending on load order, ADR-0010).
- AC7: Registration copies the build into an immutable, content-addressed store. It rejects symlinks and hard links, restricts file types, hashes all files, re-verifies an existing store directory, makes the store read-only, switches the active version atomically and validates the entry path on the server. Changing the module container's build after registration does not change what is served.

### FR-4a Module disable/enable
- AC1: A disabled module is absent from every launcher, its API routes return 503 with a machine-readable code, and the gateway forwards no requests for it.
- AC2: Windows open at disable time show the "module disabled" state within one health interval (default 30 s).
- AC3: Mappings and user layouts are retained; enabling restores access without re-mapping.

### FR-5 Permission model
- Effective permissions are the union of the permissions of all roles mapped to the user's AD groups (identified by Keycloak group claim, full path). No explicit deny; absence means deny. Roles are platform-owned bundles of permissions; manifests only suggest them.
- AC1: A user with no mapped group gets 403 from every module API.
- AC2: A removed mapping applies from the next request after the permission cache expires (<= 60 s); module access tokens issued by the gateway live at most 5 min. A "revoke sessions" action (user or all) is available and audited.
- AC3: The backend SDK enforces permissions by annotation and filter; an integration test proves 403 without the permission regardless of UI state. The frontend SDK offers an equivalent check for hiding UI only.
- AC4: The core resolves effective permissions server-side from the user's AD groups. The SDK reads them through the core API with a short cache (<= 60 s, bounded by the 15-minute rule of Q7); modules never see this mechanism (Q12).

### FR-5a First-admin bootstrap
- `SLG_BOOTSTRAP_ADMIN_GROUP` names an AD group. It seeds the `platform-admin` mapping when the database holds no admin mapping; afterwards admin rights come from stored mappings.
- AC1: With an empty database and the variable unset the core refuses to start.
- AC2: The last admin mapping cannot be deleted through the UI or API.
- AC3: Lock-out recovery: delete the admin mapping in the database (documented), set the variable, restart. A break-glass local Keycloak admin exists; its use is alerted and audited.
- AC4: Bootstrap seeding and break-glass use are audited.

### FR-6 Module SDK
Backend library (Spring) and frontend package. Only Spring and React modules are supported by the contract.
- Backend: token validation (issuer, per-module audience, signature, expiry, `azp`), permission checks, identity access, service-account client for module-to-module calls, logging with correlation ID, health endpoints.
- Frontend: identity (profile only; no token is ever available to JavaScript, ADR-0012), permission hook, `useTranslation`, locale formatters, theme and language hooks, state components (Loading, Empty, Error, Forbidden, NotFound, Offline, SessionExpired), close guard, live-region announce API, window integration.
- AC1: SDK-provided context values and callbacks have stable identity (an unstable callback caused a render storm in the spike); a render-count test is part of the contract test.
- AC2: The SDK version is tied to the contract version. Only the current contract major is supported (Q13): registration rejects manifests for any other major, and a platform upgrade that raises the major is rolled out together with updated modules (see 10).

### FR-7 Module template
A template repository produces a compliant module that builds, tests (80 % gate), containerizes, validates its manifest, and can be registered by an administrator via FR-4.
- AC1: A module generated from the template passes `docs/reviews/module-registration-checklist.md` without edits.
- AC2: The template contains an `index.html` entry (also its standalone dev harness), `dts: false` for the federation plugin (otherwise the DTS worker crashes the dev server), the shared entries including the `react/` and `react-dom/` prefixes, and the `base` path `/modules/<id>/remote/`; a contract test builds it from a clean checkout.

### FR-8 Failure isolation
- AC1: A remote-entry load failure or runtime exception shows an error boundary with Retry and "Copy details" in that window only; shell and other windows stay operable (E2E test with a stopped module container).
- AC2: Module API timeouts at the proxy default to 10 s; errors map to a standard problem-details body with correlation ID.
- AC3: The registry and admin UI remain usable while any module is down.

### FR-9 Security audit log
Events: login, failed login (ingested from Keycloak events), logout, session revoke, mapping change, admin-role change, module register/upgrade/rollback/disable/enable/delete, bootstrap use, break-glass use, reads of the audit log. Service-account events and repeated 403s are not audited by the core (Q17, ADR-0008 unchanged). Fields: timestamp, event type, actor id, source IP, object, outcome, correlation ID. No request bodies or business data.
- AC1: Each event produces exactly one entry, verified by integration test.
- AC2: UPDATE/DELETE on the table by the application DB role fails; a daily hash-chain verification detects a modified row.

### FR-10 Health and diagnostics
Platform and per-module health/readiness; administrators get an operational overview.
- AC1: The core aggregates module readiness every 15 s; three consecutive failures mark a module DOWN. A DOWN module disappears from the launcher but stays in the registry.

### FR-11 Gateway and session (backend-for-frontend)
All browser calls to the core and to module backends go through the core gateway on the shell origin (ADR-0012).
- AC1: The session cookie is `HttpOnly`, `Secure` and `SameSite=Strict`; access and refresh tokens are stored only on the server (idle timeout 30 min, absolute session 10 h).
- AC2: State-changing requests require a custom request header and a matching `Origin`; requests without them are rejected (CSRF protection), covered by tests.
- AC3: The gateway obtains an audience-bound access token per module (refresh-token down-scoping, or token exchange for the confidential client if production Keycloak supports it, Q26) and forwards it to the module backend; refresh-token use is serialised per session because rotation is single use (concurrent use revokes the client session).
- AC4: An expired or revoked session yields HTTP 401 with a machine-readable code; the shell shows the session-expired banner (UI-13) and keeps module state.
- AC5: Timeouts, correlation ids and problem-details errors follow FR-8; the gateway adds no business logic.

## 5. Non-functional requirements
- NFR-1 Security (verifiable): (a) OWASP ASVS Level 2 baseline tracked in a checklist; (b) CSP `default-src 'none'; script-src 'self' 'nonce-<per request>'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; frame-src 'none'; form-action 'self'; worker-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'` without `unsafe-eval`/`unsafe-inline` (the nonce protects the inline import map that carries the integrity data, ADR-0010), plus HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy and Permissions-Policy on every location, verified by an automated test that also asserts zero CSP violations during sign-in, theme and language switch, module errors and session expiry; (c) all permission checks server-side, deny by default, tested per endpoint; (d) dependency, image and secret scanning in CI of core and template, builds fail on high/critical findings, SBOM per release; (e) no personal data in application logs.
- NFR-2 Quality: every Maven module enforces JaCoCo line coverage >= 80 % and every frontend package enforces Vitest (v8) line coverage >= 80 %. The gate is per language and per repository; there is no cross-language average, which satisfies and tightens the project rule "global >= 80 %". CI also fails if coverage drops by more than 2 points against main. Exclusions only via a reviewed list in the build. Module repositories inherit the gate from the template.
- NFR-3 i18n: no hard-coded user-visible strings; CI enforces key parity de/en and placeholder consistency; pseudo-locale visual tests.
- NFR-4 Accessibility: WCAG 2.2 Level AA for shell and modules (EN 301 549 aligned), automated axe checks in E2E tests plus a manual screen-reader pass per UI-20.
- NFR-5 Operations: single-host Docker Compose; configuration via environment and Docker secrets; documented, tested upgrade, rollback, backup and restore. Backup: nightly `pg_dump` per database (platform, Keycloak, each module schema) plus realm export and compose/secret inventory, encrypted (age), 14 daily and 8 weekly copies on an SMB share outside the host. The SMB target (host, share, credentials) is configuration, not code (Q9, Q18). RPO <= 24 h, RTO <= 4 h. A restore onto a clean host is rehearsed before go-live and at least twice a year.
- NFR-6 Performance (p95, 20 concurrent users, warm cache, LAN): shell first load <= 2 s; sign-in to usable desktop <= 3 s; module window open <= 1.5 s warm / 3 s cold; core API read <= 200 ms, write <= 500 ms; SDK permission check <= 5 ms (local); window drag 60 fps with 5 windows; core cold start to ready <= 60 s.
- NFR-7 Resource budget (host: 8 vCPU / 32 GB RAM / 500 GB disk, Q18): Keycloak 1.5 GB, PostgreSQL 1 GB, core backend 512 MB, proxy + shell 128 MB, each module backend <= 512 MB (`mem_limit`, 1 CPU). With 30 % headroom about 25 modules fit by memory; CPU, disk (images, backups, logs) and the 8 cores are tracked as modules are added. Exceeding the budget requires an ADR on host sizing. The template ships these limits.
- NFR-8 Observability: JSON logs to stdout with timestamp, level, service, `correlationId` (`X-Correlation-Id`), no personal content; log rotation 10 MB x 5; Spring Actuator liveness/readiness and Prometheus metrics on an internal port; Prometheus/Grafana optional (compose profile), not required in phase 1.
- NFR-9 Time and TLS: NTP-synchronised hosts; tokens with skew > 60 s are rejected and readiness reports drift; TLS at the proxy with a certificate from the internal Windows Active Directory CA (enrollment process to be agreed with the CA owner), trusted by all JVMs and Keycloak (also for LDAPS); certificates expiring in < 30 days raise a health warning.

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
- UI-13 When the gateway reports an expired or revoked session (FR-11 AC4), a non-modal banner offers "Sign in again" in a popup or new tab without page reload, preserving module state; this flow is verified in the skeleton because the spike validated only the silent-iframe variant, which the BFF makes unnecessary. Only after failed re-authentication are windows replaced by SessionExpired.
- UI-14 A failed remote is isolated per window by an error boundary (FR-8).

### 6.4 Keyboard model
- UI-15 Skip links; roving tabindex in taskbar and desktop icons; `F6`/`Shift+F6` cycle shell regions; configurable window switch shortcut; `Alt+Space` window menu; keyboard move/resize (arrows, `Enter` confirm, `Esc` cancel); `Esc` closes menus and popovers, never windows; the window menu follows the menu keyboard pattern (arrows, Home/End, roving tabindex, Tab closes it); `?` shows shortcuts. Modules register shortcuts only through the SDK.
- UI-16 No keyboard traps. Modal dialogs are scoped to their window (the window's other content and other windows are inert, the launcher and taskbar remain operable) and return focus to the invoking control; shell-level dialogs are page-modal.

### 6.5 Screen reader semantics
- UI-17 The desktop is a document, not an `application` role. Landmarks: taskbar (`navigation`), active window region (`main`), tray (`complementary`). Each window is a labelled non-modal dialog/region. DOM order = tab order = visual stack order; no positive `tabindex`. Non-active windows are listed in the taskbar (`aria-current`) and minimized content is `inert`. When all windows are minimized the desk is the `main` landmark. Raising a window must not drop focus from a focused control, whether by `Element.moveBefore()` or by `z-index` stacking with a documented tab-order exception (verified in the skeleton).
- UI-18 Window open/close/minimize/switch is announced through a polite live region. Dragging has a keyboard alternative (WCAG 2.5.7).
- UI-19 Each window's title is its `h1`; module headings start below it.
- UI-20 A manual screen-reader pass (NVDA + Firefox, JAWS + Chrome, VoiceOver if used) is part of the release checklist for shell changes.

### 6.6 Design system and theming
- UI-21 One versioned package `@slg-os/design-system` (React components on Bootstrap 5.3, shared as a Module Federation singleton) with `--slg-*` design tokens mapped onto Bootstrap variables. Button and text tokens meet WCAG AA on every shell background (Bootstrap's default `btn-outline-secondary` fails contrast on tertiary backgrounds); the launcher wraps at 320 px width. Modules use tokens and components only; raw hex colours, global CSS, own Bootstrap build, `!important` and global selectors are rejected by a lint rule in the template and by the registration checklist. iframe-embedded tools are an exception that requires an ADR and must provide a meaningful title, focus hand-off and theme/language signals where possible (Q21).
- UI-22 The shell sets `data-bs-theme` on `<html>`; modules must render correctly in both themes, never read `prefers-color-scheme`, and switch live. Default: OS preference at first login, then the user's choice. A contract test renders the reference module in both themes.
- UI-23 Respect `prefers-reduced-motion` and `forced-colors`; 200 % zoom and 400 % reflow without loss of function.

### 6.7 i18n and locale
- UI-24 Resolution order: user preference, browser language, `de`. `<html lang>` follows the language; text in another language is marked with `lang`.
- UI-25 The language switch applies at runtime to shell and all windows via the SDK (i18next-compatible `useTranslation`, language-changed event).
- UI-26 Modules ship `i18n/{lang}/{moduleId}.json` (namespace = module id, ICU messages) referenced by the manifest, loaded lazily. A missing key falls back to `en`, then shows the key and logs a warning (a defect). CI checks parity, unused/missing keys and placeholders. Registration is rejected if either language is incomplete.
- UI-27 Formats via `Intl` with `de-DE` and `en-GB`; modules use SDK formatters only. Display time zone Europe/Berlin, storage UTC; sorting via `Intl.Collator`.
- UI-28 Layouts tolerate +35 % German text length; a pseudo-locale (`de-XL`) is used in dev builds and visual tests; icons never carry meaning alone.

### 6.8 Browser support
- UI-29 Desktop: last two majors of Edge/Chrome, Firefox ESR and current, Safari current and previous major. Mobile (narrow mode) best effort on current Safari iOS and Chrome Android. Minimum 1280x720 (desktop mode) and 360 px width (narrow mode). Printing is the module's concern.

## 7. Data and interfaces
- PostgreSQL for platform data (registry, group mappings, preferences/layouts, audit log); versioned migrations.
- Module database provisioning: on registration the core's provisioner (dedicated privileged role) creates schema `mod_<id>` and role `mod_<id>` limited to its own schema and delivers the password as a Docker secret. Modules run their own Flyway migrations at startup with that role; failure keeps readiness DOWN and the previous container running. Migrations must be backward compatible with the previous module version (expand/contract) to allow rollback.
- Shell document: the core generates the shell HTML per request with a CSP nonce and an inline import map whose `integrity` section lists the SHA-384 hash of every registered JavaScript file; the map and the module list come from the same registry snapshot, and an unlisted module file is an error (ADR-0010).
- Contract artifacts: manifest schema (versioned JSON schema), SDK, REST conventions (versioned, OpenAPI, breaking-change diff in CI), federated remote conventions.

## 8. Security and data protection
- 8.1 OIDC and session (ADR-0012): the core is a confidential client using authorization code + PKCE (S256) with exact redirect URIs (no HTTP redirect URIs in production). Tokens stay on the server: the browser holds an `HttpOnly`, `Secure`, `SameSite=Strict` session cookie; CSRF protection per FR-11. Access tokens <= 5 min; rotating single-use refresh tokens with serialised use; idle timeout 30 min, absolute session 10 h. Kerberos/SPNEGO SSO is not enabled (Q1). Module backends accept bearer tokens forwarded by the gateway and validate issuer, per-module audience, signature (cached JWKS), expiry and `azp`.
- 8.2 Logout and revocation: RP-initiated logout; back-channel logout invalidates the server-side session at once, so no denylist is needed. A user disabled in AD loses access within the Keycloak LDAP sync interval (<= 15 min) plus one module-token lifetime (5 min). Administrators can revoke all sessions (audited).
- 8.3 Trust boundary of federated remotes: remotes run in the shell origin. With the BFF a compromised module build cannot read or exfiltrate a token, but it can still send authenticated requests as the signed-in user, read other windows' DOM and send data to same-origin endpoints; `connect-src` does not stop exfiltration by navigation, prefetch or WebRTC. Backend authorisation per request therefore stays the enforcement point, and ADR-0007 is a security control: only admin-approved remote entries on the platform origin are loaded; integrity of every JavaScript file is enforced through the import map and the immutable content-addressed store (FR-4 AC7, ADR-0010; verified in Chromium only, Firefox and Safari are release gates); CSS, JSON and other runtime-fetched files are not covered, so registration restricts file types; versions are promoted four-eyes; no external CDNs or scripts at runtime; module CI uses locked dependencies, scanning and signed images; the shell never renders module-supplied HTML. Down-scoped module tokens are not a boundary against a hostile module. Whether the owner accepts the remaining risk is Q24.
- 8.4 Service-to-service: each module backend has a Keycloak service account (client credentials) with audience-restricted least-privilege scopes, secrets from Docker secrets rotated at least yearly. User tokens are propagated only to the core; module-to-module calls use service identity plus the user id as audit claim. Module containers are on an internal network, not published on host ports.
- 8.5 Reverse proxy: only the proxy is exposed (TLS 1.2+, HSTS). Keycloak is served from its own origin (a separate host name), not under the shell origin; only the login, logout, token and well-known endpoints are exposed publicly, the admin console and the master realm are not (Q27). Header stripping and traversal tests run against the production module locations with positive assertions (404/400 and no content), and identity headers are handled by an allowlist. It strips inbound `X-Forwarded-*`, `Forwarded` and identity headers and sets them itself; backends trust such headers only from the proxy network; identity comes from validated tokens, never headers.
- 8.6 Manifest validation and SSRF: JSON schema validation with size limit; server-side fetches only to an allowlist of internal module service names, http(s) only, fixed ports, no redirects, resolved IPs must not be loopback, link-local or metadata addresses, 5 s timeout.
- 8.7 Keycloak and AD (service account and certificate rotation operated by the owner's team, Q20): LDAPS with certificate validation; dedicated read-only AD service account limited to the needed OUs; brute-force detection with tuned parameters; explicit refresh-token and session lifetimes; no `start-dev` mode; the built-in client scopes are defined explicitly in the committed realm file (importing a realm with custom scopes otherwise drops `profile`, `email`, `roles`, `web-origins`) and an import smoke test checks them; admin console not routed via the public proxy; realm export contains no secrets; defined patch cadence.
- 8.8 Audit log integrity: see FR-9. Exports go to the read-only backup.
- 8.9 Data protection (DSGVO): personal data processed by the core: user id, display name, email, AD group names (token), IP address (audit), UI preferences, technical logs. Purpose: authentication, authorization, security accountability, operation. Legal basis proposal: Art. 6(1)(f) DSGVO and § 26 BDSG; a works council agreement is required before go-live because login and audit data could enable behaviour or performance monitoring (BetrVG § 87(1) Nr. 6); the audit log is not used for performance evaluation. Retention (owner decision, DPO and works council sign-off still required, Q23): audit log 6 months, application logs 30 days, preferences 90 days after the account disappears from AD. Audit log access for the auditor role only, access itself audited. Logs contain pseudonymous ids, never names, emails or content. An Art. 30 record is created and the DPO decides on a DPIA before go-live. The core can export and delete a user's preferences and look up audit entries on request.
- 8.10 Secrets: Docker secrets from files (0400), no secrets in images, repositories or realm export, per-module DB credentials.

## 9. Testing strategy
- Java (proposal, verified against official documentation when the skeleton is created, Q2/Q3): latest LTS JDK (JDK 25 proposed, 21 fallback) and Spring Boot 4.x, Maven, JUnit 5, AssertJ, Mockito, Spring Boot Test, Testcontainers (PostgreSQL; Keycloak with the versioned realm import; LDAP stub for groups), Spring Cloud Contract or Pact for REST contracts, ArchUnit ("no cross-schema access"), JaCoCo.
- Frontend: Vitest + Testing Library (coverage v8), MSW, Playwright for E2E including axe checks, the Module Federation plugin selected in the spike.
- Module Federation: the shell has a mock remote fixture; a Playwright contract test loads the template module's built `remoteEntry.js` and asserts mount, theme/language propagation, error boundary on 500/unreachable remote, and shared singleton versions.
- Contract tests: manifest schema against golden valid/invalid manifests; SDK against the previous contract version; OpenAPI diff (oasdiff) fails on breaking changes.
- E2E (compose-based, nightly and pre-release): sign-in, launcher filtering, permission denial (403), failing module, AD-unavailable login error, module upgrade and rollback, session expiry without state loss.
- Spike suite as seed: `spikes/shell/tests` may seed the Module Federation contract test after it is parametrised by base URL and module id; its hostile-remote tests are characterisation tests (they assert known residual exposure, not controls) and are marked as such. Additional tests: render-count test, CSRF tests, Keycloak realm import smoke test, E2E against production-mode Keycloak and the real proxy, CSP and integrity checks in Firefox and Safari, a manual NVDA + Firefox pass, `Alt+Space` in Edge on Windows, and measurements on an office PC (all release gates).
- Module CI (from the template): build, unit tests with coverage gate, contract test against the SDK version, dependency scan, image build and push, manifest validation.

## 10. Rollout and operations
- Topology: compose services `proxy`, `keycloak`, `postgres`, `core-backend`, `shell`, plus modules. Health-gated startup order: postgres, keycloak, core-backend, shell/proxy; modules start after the core-backend and tolerate its absence by retrying.
- Deploy/discovery: modules publish semver-tagged images to GitHub Container Registry (ghcr.io; the host pulls, outbound connections are allowed, Q10/Q18) and a manifest. Operators add a compose file for the module (pinned by image digest) and an administrator registers the manifest (FR-4). Module API calls go through the core gateway (FR-11); `/modules/<id>/remote` is served from the immutable content-addressed store (long-lived `immutable` cache headers for hashed files, `no-store` for the registry) and routes are generated from registry entries; the registry, not Docker labels, is the source of truth. Modules send an authenticated heartbeat for health only.
- Upgrade/rollback: change digest, `up -d <id>`, readiness-gated; rollback = previous digest, permitted because migrations are expand/contract. Core upgrades follow the same path after a backup.
- Contract versioning: SemVer `major.minor`; minor is additive, major is breaking. The platform supports only the current major (Q13): a breaking contract change is released together with updated modules, and modules not yet updated are disabled by registration validation until they are. React major and design-system range are pinned per contract major.
- Failure modes: AD down: existing sessions keep working, new logins show a localized error and raise an alert. Keycloak down: sessions continue until the cached module tokens expire (<= 5 min), then module calls fail with a localized error; modules validate locally with a cached JWKS.
- Phase 1 delivers shell, sign-in, registry, permission mapping, audit log, one reference module, module template, backup scripts and a restore rehearsal.

## 11. Decisions and open questions
Owner is Benjamin Honauer unless stated. Decisions were taken in the owner interview on 2026-10-19.

### 11.1 Decisions
| # | Decision | Effect in this PRD |
|---|---|---|
| Q1 | No Kerberos/SPNEGO SSO for now; Keycloak login form against AD. Can be enabled later without architecture change. | 8.1, ADR-0001 stays valid |
| Q2/Q3 | JDK/Spring Boot versions and test tooling named in section 9 are verified against official documentation when the project skeleton is created, then recorded in `CLAUDE.md`. | Section 9 |
| Q4 | Decided by the spike: in-house window manager (ADR-0011, proposed, with open gaps). | UI-1 to UI-8 |
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
| Q24 | Does the owner accept the remaining risk after the BFF (8.3: authenticated requests as the user, DOM access, same-origin exfiltration), and is the DPO informed? | Benjamin Honauer | before go-live |
| Q25 | Firefox and Safari checks of import-map integrity and CSP; manual NVDA + Firefox pass; Edge on an office PC with `Alt+Space` and measurements | platform team | before go-live |
| Q26 | Production-mode Keycloak: token exchange (confidential client) or refresh-token down-scoping for per-module audiences | platform team | skeleton |
| Q27 | Host names, certificates (Windows AD CA) and routing for Keycloak on its own origin | Benjamin Honauer | skeleton |
| Q28 | Gateway session store (PostgreSQL-backed sessions proposed, so sessions survive a restart) | platform team | skeleton |
| Q23 | Works council agreement and DPO decision (Art. 30 record, DPIA) covering 8.9; the DPO is also informed of the accepted residual risk in 8.3 | Benjamin Honauer | before go-live |

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

Owner interview 2026-10-19: 21 of the 22 original open questions were decided (section 11.1). Consequences applied: ADR-0008 stays unchanged, so the broader audit events proposed by the security review were removed from FR-9; supporting only the current contract major removes the N-1 compatibility proposed by the operations review; the 32 GB host raises the module capacity estimate. Q5 (plugin spike) remains open; Q23 (works council and DPO) was added.

Revision 2 (2026-10-19): applied the spike findings and ADR-0012 (BFF): FR-1, FR-4 (AC6, AC7), FR-5, FR-6, FR-7, new FR-11, NFR-1 (CSP), UI-3, UI-13, UI-15 to UI-17, UI-21, sections 7, 8, 9, 10 and 11. Not yet independently reviewed; required perspectives: requirements, security (BFF, CSRF, session store), UI/UX and accessibility, operations (gateway on the hot path, Keycloak origin).

Open for a follow-up review pass: none of the perspectives re-reviewed the integrated text or the decisions applied afterwards.
