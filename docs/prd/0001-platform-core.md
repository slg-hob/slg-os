# PRD-0001: Platform core: shell, authentication, authorization, module contract

- Status: Draft (not yet reviewed)
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0001-0009

## 1. Problem and goals
Departments need many internal tools and automations. Building each as a standalone app duplicates login, permissions, look and operations. slg-os provides one platform with an OS-like desktop in the browser, single sign-on against the local Active Directory, and a mandatory contract that lets independent teams add modules quickly and consistently.

Goals:
- G1: A desktop shell where users open permitted modules in windows.
- G2: Authentication against AD via Keycloak (ADR-0001).
- G3: Group-based authorization managed in an admin UI (ADR-0006).
- G4: A versioned module contract so a new module can be registered without changing the core (ADR-0002, ADR-0005).
- G5: Consistent UI/UX, bilingual (de/en) via i18n, across the shell and all modules.
- G6: Operable by a small team on a single server (ADR-0009).

## 2. Non-goals
- High availability, Kubernetes, multi-site deployment.
- Modules from outside the platform team's review process (ADR-0007).
- Mandatory collection of business-level audit data (ADR-0008).
- Any concrete business module (each gets its own PRD in its own repository).

## 3. Users and roles
- End user: signs in, opens permitted modules, sets language/theme.
- Module developer (internal team): builds a module from the template and the SDK.
- Platform administrator: registers modules, maps AD groups to roles/permissions, views the audit log. Bootstrapped through a configured AD group.
- Auditor (to be confirmed): read-only access to the security audit log.

## 4. Functional requirements (initial outline; to be completed and made testable in review)
- FR-1 Sign-in: unauthenticated users are redirected to Keycloak; after login the desktop appears. Sign-out ends the session in shell and modules.
- FR-2 Desktop shell: windows (open, move, resize, minimize, maximize, close, focus), taskbar, launcher listing only modules the user may use, per-user persisted window layout and preferences.
- FR-3 Language and theme: German/English and light/dark switchable per user; all shell texts come from i18n resources; modules receive the current language and theme.
- FR-4 Module registry: administrators register a module by its manifest (id, name, version, icon, i18n resources, entry points, declared permissions, supported contract version). Invalid or incompatible manifests are rejected with a clear message. Modules can be disabled without deletion.
- FR-5 Permission model: effective permissions = f(user's AD groups, stored group mapping); deny by default; admin UI to create mappings; changes take effect for subsequently issued tokens.
- FR-6 Module SDK: backend library (Spring) and frontend package providing token validation, permission checks, identity access, i18n, theming, window integration, logging with correlation ID, health endpoints.
- FR-7 Module template: a template repository that produces a compliant module that builds, tests, containerizes and registers.
- FR-8 Failure isolation: a failing or unreachable module shows an error state in its window and does not affect the shell or other modules.
- FR-9 Security audit log: login/logout/failed login, mapping changes, module registration changes, admin actions; retention and access to be specified (ADR-0008).
- FR-10 Health and diagnostics: platform and per-module health/readiness; basic operational overview for administrators.

## 5. Non-functional requirements
- NFR-1 Security: OWASP ASVS-style baseline, secure headers/CSP, CSRF/XSS protection, least privilege, dependency scanning; permission checks enforced server-side.
- NFR-2 Quality: global test coverage >= 80 %, enforced in the build.
- NFR-3 i18n: no hard-coded user-visible strings; both languages complete.
- NFR-4 Accessibility: keyboard operable desktop and windows, focus management, screen reader semantics, sufficient contrast (target level to be fixed in review, e.g. WCAG 2.2 AA).
- NFR-5 Operations: Docker Compose deployment, configuration via environment/secrets, documented backup/restore and upgrade.
- NFR-6 Performance: sign-in to usable desktop and module window open times within budgets to be set in review.

## 6. UI/UX
To be detailed in a dedicated shell PRD (window manager behaviour, narrow-viewport fallback, launcher, notification area, error/empty/loading patterns, design tokens).

## 7. Data and interfaces
- PostgreSQL for platform data (registry, group mappings, preferences, audit log); versioned migrations; module schemas separate.
- Contract artifacts: manifest schema (versioned), SDK, REST conventions (versioned, OpenAPI), federated frontend remote conventions.

## 8. Security considerations
- Token validation in every service (issuer, audience, signature, expiry); token lifetime and revocation policy to be set.
- Module-to-core and module-to-module calls authenticated.
- Manifest validation and registration restricted to administrators.
- Same-origin serving via reverse proxy; CSP for federated remotes.

## 9. Testing strategy
Unit and integration tests per service, contract tests for the manifest and SDK, end-to-end tests for sign-in, launcher, permissions and failing module. Frameworks to be chosen in review and then recorded in `CLAUDE.md`.

## 10. Rollout and operations
Compose stack: reverse proxy, Keycloak, core backend, shell, PostgreSQL. Realm configuration is versioned and applied automatically. Phase 1 delivers shell, sign-in, registry and one reference module.

## 11. Open questions
1. Is Kerberos/SPNEGO single sign-on required, or is a login form against Keycloak acceptable?
2. Java version baseline and Spring Boot major version.
3. Frontend/backend test frameworks and coverage tooling.
4. Window manager: library vs. in-house.
5. Vite Module Federation plugin maturity (spike needed, see ADR-0005).
6. Audit log retention period and who may read it.
7. Token lifetime and how quickly mapping changes must take effect.
8. Accessibility target level and narrow-viewport behaviour.
9. Secret management and backup targets on the host.
10. How module repositories publish artifacts (container registry, versioning) and how the platform learns about new versions.

## 12. Review log
None yet. Required perspectives: requirements completeness, security, UI/UX, permissions, operations, data protection.
