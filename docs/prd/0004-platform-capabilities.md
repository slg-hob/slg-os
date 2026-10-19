# PRD-0004: Platform capabilities (phase 1b)

- Status: Draft
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0001, 0002, 0004 to 0009, 0010 and 0011 (proposed, decided by the skeleton evidence), 0012; ADR-0013 to 0016 and 0017 (written by the skeleton, PRD-0003 section 7.5). New ADRs written during this phase: section 7.5 (numbered from 0018).
- Related PRDs: PRD-0001 (platform core, approved revision 2), PRD-0003 (walking skeleton, approved; this PRD builds on its code and its decisions)
- Related: `ROADMAP.md` (phase 1b), `docs/reviews/module-registration-checklist.md`

## 1. Problem and goals
After the skeleton, one reference module runs through the real stack, but everything that makes the platform usable by a team is missing: administrators can only act through an API, the audit log is a plain table, module databases and Keycloak clients are provisioned by a script, the immutable store has no retention or monitoring, module authors have no SDK package and no template, and nobody is told when something fails. Phase 1b adds these capabilities so that a second team can build, register and operate a module without help from the core developers.

Goals:
- G1: An administrator manages modules, roles, group mappings, sessions and the audit log in an accessible, bilingual admin UI, with every sensitive action step-up protected and audited (PRD-0001 FR-4, FR-4a, FR-5, FR-5a, FR-9).
- G2: The audit log is tamper-evident (hash chain, daily verification), is fed with Keycloak login events, has a retention job and a read path (FR-9, ADR-0008, 8.9).
- G3: Registering a module provisions its database schema and role, its Keycloak audience, scope and service-account client automatically, idempotently and without handing a privileged credential to the module (FR-4, 7).
- G4: The immutable module store is complete: archive input, verification at start and daily, retention, monitoring and a staged activation flow (FR-4 AC7).
- G5: The module contract is usable by other teams: published backend and frontend SDK, a contract test kit, an automatable part of the registration checklist, and a module template that passes the checklist without edits (FR-6, FR-7).
- G6: Operators see problems: alerts for AD, break-glass use, certificates, backups, modules, store and audit chain in the admin overview (NFR-8, NFR-9).
- G7: Backups are scheduled, include the store and all module schemas, report their result to the platform and can be written to the SMB target (NFR-5).
- G8: Data-subject requests (export and deletion of preferences, lookup of audit entries) can be served by an administrator (8.9).

## 2. Non-goals
- Visual language, window chrome look and desktop metaphor (own PRD, ROADMAP). The admin UI uses the design-system components of the skeleton.
- Go-live gates of phase 1c: restore rehearsal on a clean host, full load test, chaos tests beyond those listed here, Firefox/WebKit/Safari/Edge manual checks, manual screen-reader pass, works council and DPO sign-off.
- E-mail alerts (Q30, deferred by the owner); alerts stay in the admin overview, in the tray badge for administrators and in the logs.
- An auditor role (Q14); high availability; WebSocket; Kerberos (Q1); languages beyond German and English.
- The notification and job-scheduling capabilities for modules ("later" in ROADMAP) and any business module.
- Support for more than the current contract major (Q13).
- Self-service registration by departments (ADR-0007, Q16).

## 3. Users and roles
- Platform administrator (`platform-admin`, seeded in the skeleton by `SLG_BOOTSTRAP_ADMIN_GROUP`): full admin UI and API. No further admin roles in this phase: the owner decided that the four-eyes release of module versions is a **process**, not a tool function (Q38); split roles are not built. The admin API is nevertheless written with one permission per capability (`platform.modules.manage`, `platform.access.manage`, `platform.sessions.revoke`, `platform.audit.read`, `platform.overview.read`, `platform.privacy.manage`), all bundled in the built-in role `platform-admin`, so that split roles remain a mapping change.
- Module developer: consumes the SDK packages and the template, runs the contract test kit in module CI.
- Platform team reviewer: applies `docs/reviews/module-registration-checklist.md` and records the four-eyes review of the module repository.
- Operator: runs backups, reads alerts, performs lock-out recovery.
- End user: unchanged; sees only effects (launcher, error states).

## 4. Functional requirements
Numbering `PF-n`. "Implements" names the PRD-0001 requirement. Every sensitive endpoint requires step-up (re-authentication not older than 5 minutes, verified through `auth_time`, PRD-0003 SK-7 AC6) unless noted.

### 4.1 Audit log

#### PF-1 Tamper-evident audit chain
Implements FR-9 AC2, 8.8.
- AC1: Every `audit_event` row stores `seq` (gapless, assigned under a single-writer lock), `prev_hash` and `row_hash` = SHA-256 over a canonical encoding of `seq`, `prev_hash` and all event fields. The first row after the migration starts the chain (the migration re-chains existing skeleton rows in order and records the result in an `audit_checkpoint` row).
- AC2: A daily job (and an admin action "verify now") recomputes the chain from the latest checkpoint and reports: OK, first broken `seq`, or gap. A failure raises an alert (PF-12) and writes an audit event. Tests: modified field, deleted row, inserted row, reordered rows, truncated tail (compared with the head hash stored in the previous nightly backup manifest and the log line written at each verification).
- AC3: The head hash and `seq` are written to the log after each verification and into the nightly backup manifest (PF-15). The hash chain protects against casual or accidental change by anyone without the ability to rewrite the whole table; it does not protect against a database superuser who recomputes the chain. This limit is stated in the operator documentation and in section 8; whether an external anchor is wanted is Q45.
- AC4: Concurrent writers do not break the chain (test with 20 threads and with two core containers); a failed insert does not leave a gap.

#### PF-2 Keycloak event ingestion
Implements FR-9 (failed login, login, logout, break-glass), 8.7.
- AC1: The core polls the Keycloak admin events API for the platform realm and the master realm every 30 s through a dedicated read-only service account (`slg-audit-reader`, role `view-events` only), over the internal network. A cursor (last event time and id) is stored; events are de-duplicated by Keycloak event id; Keycloak down means the poll resumes from the cursor (no loss within Keycloak's own event retention, which the realm file sets to at least 7 days).
- AC2: Mapped events: `LOGIN` (written by the core callback in the skeleton; the ingested event is only used for the cross-check and not written twice), `LOGIN_ERROR` (reason code, user id if known, IP), `LOGOUT`, admin-console and master-realm logins (`break_glass_use`). Each Keycloak event produces exactly one audit entry (integration test with a replayed poll).
- AC3: Data minimisation: the free-text username typed into a failed login is **not** stored (it may contain a mistyped password); only the user id when Keycloak resolved one, else the marker `unknown`, plus IP and reason. Test: a failed login with a password-like username leaves no such text in the database, logs or backups.
- AC4: A master-realm login that is not the documented break-glass account, and any use of the break-glass account, raises an alert (PF-12) and an audit event.
- AC5: The realm file enables events, sets the listener, retention and the excluded event types; the import smoke test asserts them.

#### PF-3 Audit retention, read access and export
Implements 8.9, FR-9 (reads audited), Q6, Q14.
- AC1: Retention 6 months (configurable, Q6 working value). The table is partitioned by month; a job running under the separate database role `slg_retention` (the only role with the privilege to detach and drop partitions) removes whole partitions older than the retention period, after writing an `audit_checkpoint` row with the last `row_hash` and `seq` of the removed range. Verification (PF-1) starts at the latest checkpoint. The application role cannot drop partitions (tested).
- AC2: `GET /core/api/v1/admin/audit` (permission `platform.audit.read`) filters by time range, event type, actor, object and outcome, with keyset pagination; page size <= 200. Every call, including exports, writes an `audit_read` event with the filter used (not the result).
- AC3: CSV export of a filtered range (maximum 50,000 rows per request). Cells starting with `=`, `+`, `-`, `@`, tab or carriage return are prefixed with an apostrophe; encoding is UTF-8 with BOM, the first row is the header; a test feeds hostile values. Export requires step-up.
- AC4: The audit viewer shows event types by i18n key, never raw database strings (unknown types show a generic label).

### 4.2 Provisioning and the store

#### PF-4 Module provisioner
Implements FR-4 (provisioning sentence), 7 (module database provisioning), 8.4, 8.10.
- AC1: On registration the provisioner creates, idempotently and in this order: the module database schema `mod_<id>` and the role `mod_<id>` (limited to its own schema, no other grants, `CONNECT` only to the platform database), the Keycloak audience, scope and service-account client (confidential, client credentials, audience-restricted scopes, no other flows), and the credential files for both. Re-running changes nothing; a partial failure leaves a recorded state and a re-run completes it; deleting a module does not drop its schema (data is kept until an administrator confirms a separate "purge module data" action, which requires step-up and is audited).
- AC2: Privilege separation: the provisioner uses its own database role (`slg_provisioner`: create schema and role, no access to platform tables, no access to module data) and its own Keycloak service account with the minimum fine-grained admin permissions (manage clients and client scopes in the platform realm, nothing else). The core's runtime role and runtime Keycloak client hold neither. A compromised gateway request path cannot create roles (test: the runtime role fails `CREATE ROLE`).
- AC3: Credentials never appear in the manifest, the registry, the audit log, logs, images, environment variables or `docker inspect` output; they reach the module container only as files under `/run/secrets/` with mode 0400 owned by the container user. The delivery mechanism is decided in ADR-0018 against the criteria in Q39; whichever is chosen, a CI test registers a module on the compose stack and asserts that the module starts with its files and that no credential is visible outside them.
- AC4: Rotation: an administrator action "rotate credentials" (step-up, audited) issues new database and Keycloak credentials, delivers them, and the module picks them up on restart; the old credentials stay valid for a documented overlap and are then revoked; a scheduled yearly reminder alert (PF-12) exists (8.4 requires rotation at least yearly).
- AC5: Module Flyway migrations run with the module role at module start (module concern). The registry records the module's reported schema version when the module exposes it; a module whose migration failed keeps readiness DOWN and the previous container serving.

#### PF-5 Store completion and staged activation
Implements FR-4 AC2, AC3, AC7, 8.3.
- AC1: Build sources: an archive (tar.gz or zip, upload to the admin API or fetch under the SSRF rules) in addition to the HTTP listing of the skeleton. Unpacking enforces limits on archive size, entry count and path length, rejects `..`, absolute paths, links, device files, HTML files and unknown file types, and does not trust archive-declared sizes (decompression-ratio cap). Fuzz-style tests cover malformed archives.
- AC2: Verification: the core verifies the active store directory of every module at start and daily (tree hash and file hashes against the registry); a mismatch disables the module's remote, raises an alert, increments `slg_store_verify_failures_total`, and is audited. Restoring from backup re-verifies (PF-15).
- AC3: Retention: the three previous versions of each module and every version younger than the absolute session lifetime (10 h) stay; an audited admin job removes older ones; the store volume usage is monitored and alerts at 70 % (PF-12).
- AC4: Lifecycle: registering a version creates it in state `registered` (not served to users). **Activation** is a separate admin action (step-up) that first runs pre-flight checks: backend readiness of the version reported UP, manifest and permission diff shown (added, removed, renamed, orphaned mappings; breaking changes need an explicit confirmation listing every affected mapping), `requires` satisfied, store verified. Rollback re-activates the previous version in one action. All transitions are audited and idempotent. The activation does not depend on a second person (Q38); the audit entry names the actor and the checklist reference the administrator enters (free text, link to the module repository review, validated as an http(s) URL).
- AC5: Upgrade order guidance (backend first, then remote; rollback in reverse) is shown in the UI together with a mismatch warning when the running backend version is older than the manifest requires.
- AC6: Icons: the manifest `icon` is an identifier from the design-system icon set; uploaded SVG icons are not supported (a module-supplied SVG would execute in the shell origin). A custom icon is a PNG in the build, within size limits and checked by magic bytes.

### 4.3 Access management

#### PF-6 Roles, mappings and effective permissions
Implements FR-5, FR-5a, ADR-0006.
- AC1: Roles are platform-owned bundles. Administrators create, rename and delete roles, change their permissions, and import a module's `suggestedRoles` as a starting point (a suggestion never applies itself). Built-in role `platform-admin` cannot be deleted or reduced; the last admin mapping stays undeletable (PRD-0003 SK-6 AC2).
- AC2: Group mappings connect an AD group (identified by the full group path claim) to one or more roles. The group picker lists groups from Keycloak (Q41 decides the scope); a mapping to a group that no longer exists in the directory is flagged "orphaned" and kept. Mappings of permissions removed by a module upgrade are flagged "orphaned permission" and listed in the UI, never deleted silently.
- AC3: Effective-permission explanation: for a user id, the admin sees groups (from the latest live session, else "no session"), mapped roles, resulting permissions and which mapping grants each one ("why does this person have access"). The lookup is audited. The same view serves "what would this group get" without a user.
- AC4: Effect of changes: a mapping or role change drops the permission cache (<= 60 s remaining staleness for module-side caches, documented), invalidates cached module tokens for affected sessions and writes one audit event per change with before and after. Changes need step-up. Concurrent edits use optimistic locking (version column); the loser sees a conflict message.
- AC5: "Revoke sessions" for one user or all users (audited, step-up); the acting administrator's own session survives "revoke all" unless chosen otherwise.
- AC6: Deny by default is verified by an exhaustive test that every admin endpoint returns 403 for a user holding every permission except the one it requires.

### 4.4 Admin UI

#### PF-7 Admin UI
Implements FR-4, FR-4a, FR-5, FR-9, FR-10, NFR-4 for administrators.
- AC1: The admin UI is part of the shell (core windows, not a federated module), shown in the launcher category "Administration" only to users holding at least one platform permission; deep links `/app/platform/<page>` return the neutral not-found state for others. It remains usable while any module is down (FR-8 AC3).
- AC2: Pages: Overview (PF-12 alerts, module status, store usage, last backup, certificate expiry, audit chain state, session count), Modules (list, versions, register from manifest and build source, activate, rollback, disable, enable, delete, rotate credentials, purge data), Access (roles, mappings, effective permissions, revoke sessions), Audit (viewer and export), Privacy (PF-14), Operations (health detail, verification actions).
- AC3: Every destructive action shows a confirmation dialog naming the object and the consequence, focuses the least destructive button, and states the audit effect. Step-up expiry shows a re-authentication dialog and resumes the action without losing the entered data.
- AC4: All texts are i18n keys in de and en (namespace `platform`); manifest-supplied strings (names, permission labels) are rendered as text, never as HTML, and truncated with the full value in an accessible tooltip; sorting uses `Intl.Collator`; dates use the SDK formatters.
- AC5: Accessibility: tables with proper headers and sortable-column semantics, status never by colour alone, forms with associated errors, live-region announcements for results of actions, keyboard-only completion of every flow, axe clean in light, dark, forced-colors and narrow mode (PRD-0003 SK-18 AC2 rules apply).
- AC6: Forms validate on the server and echo field-level errors with codes mapped to i18n keys (UI-30); the manifest registration form shows every violation with its field path and the de/en message from the API.

### 4.5 SDK, contract and template

#### PF-8 Module SDK (complete)
Implements FR-6 in full; extends PRD-0003 SK-14.
- AC1: Backend library `slg-os-sdk-backend` adds: the service-account client for module-to-module calls (client credentials, token cache with early refresh, audience per target, retry and timeout policy, correlation id propagation); the permission cache with the 60 s bound and invalidation on a 401/403 from the core; DEGRADED/UP/DOWN readiness with dependency checks; Micrometer metrics with the naming rules of NFR-8; structured JSON logging with a personal-data-free MDC; a test support module (`slg-os-sdk-test`) that issues signed test tokens and fakes the permission API.
- AC2: Frontend package `@slg-os/sdk-frontend` adds: keyboard shortcut registration with collision rejection and help integration (UI-15), shell dialog and toast APIs, `lang` helper for mixed-language text, route integration with the shell's History API deep links, and the notification-area badge API. It still exposes no token and no raw `fetch`.
- AC3: Contract version `1.0` is kept if no manifest field is added or changed; additive changes raise the minor version (`1.1`); the SDK, the manifest schema, the contract test kit and the template carry the same version, and a CI check fails if they disagree. Breaking changes follow Q13 and need a new ADR.
- AC4: Publishing: SDK artefacts go to the organisation's GitHub Packages (Maven and npm), published by a release workflow from tags on `main` with provenance (npm provenance, signed artefacts where the registry supports it), an SBOM attached to the release and the version recorded in `CHANGELOG.md`. Module CI authenticates with the workflow token; a package in the repository registry is visible only to repositories of the organisation (Q43). Consumers pin exact versions.
- AC5: Backward compatibility within a contract major: a test builds the previous SDK release's reference module against the new SDK and passes (PRD-0001 §9). The OpenAPI diff gate of PRD-0003 covers the core and SDK-facing APIs.

#### PF-9 Contract test kit and checklist automation
Implements FR-7 AC1, module registration checklist.
- AC1: A package `slg-os-contract-tests` (Java and a CLI `slg-os-check`) lets a module repository verify: manifest schema and semantic rules (permission names, error-code keys in both languages, `requires` ranges, entry points), build layout (`build-manifest.json`, file types, no HTML, no source maps with secrets), i18n key parity and placeholder consistency, health and readiness endpoints, token validation behaviour against forged tokens (wrong audience, wrong `azp`, expired, bad signature), 403 without permission on every annotated endpoint, remote loading in the shell fixture (mount, theme and language switch, error boundary), render-count stability, and the lint rules of UI-21 (no raw colours, global CSS, own Bootstrap, `!important`).
- AC2: The checklist `docs/reviews/module-registration-checklist.md` marks which items are checked automatically by the kit (listed with the kit's check id) and which need human review; the kit prints a report that the administrator pastes or links in the activation step (PF-5 AC4). Items that cannot be automated (source is an internal repository, owner team named, PRD present, four-eyes review recorded) stay manual.
- AC3: The kit has its own tests with golden valid and invalid modules; each check fails on a deliberately broken module (mutation-style negative tests).

#### PF-10 Module template
Implements FR-7.
- AC1: A GitHub template repository (`slg-os-module-template`) is generated from the directory `templates/module` of this monorepo by a sync workflow on release (the monorepo stays the single source; a drift check fails CI if the template repository differs from the directory at the released version). It contains: Spring Boot backend wired to the SDK (auth, permissions, i18n resources, logging, health, Flyway), Vite/React remote wired to the frontend SDK with the settings of FR-7 AC2 (`index.html`, `dts: false`, shared entries including prefixes, relative `base`), Dockerfile (multi-stage, non-root, read-only root file system, limits from NFR-7), compose snippet with the secret file mounts of PF-4, manifest, i18n skeleton (de, en), CI workflow (build, unit tests with the 80 % gates equal to the template, contract test kit, dependency and image scanning, SBOM, image signing with cosign, digest output, manifest validation), lint configuration (`@slg-os/eslint-config`), `CODEOWNERS`, PR template with the module registration checklist, `README` (English) with a quick start.
- AC2: Core CI generates a module from the template at the current version, builds it from a clean checkout, runs its own tests with coverage, registers it on the E2E stack, signs in as a permitted user and asserts that its window opens, a permission denial works and the kit passes (FR-7 AC1: no edits needed to pass the checklist's automatable items).
- AC3: Coverage rules of the template cannot be weakened silently: the kit compares the module's gate configuration with the template's and reports differences (NFR-2, registration checklist).
- AC4: Developer documentation (English, `docs/developers/`): getting started, contract reference (generated from the schema and OpenAPI), error-code convention, permission naming, i18n rules, local development against the compose stack with a dev profile (hot reload of a remote inside the shell, which the spike did not verify, is verified here and documented or its limits stated), upgrade and rollback of a module, troubleshooting.
- AC5: Effort measurement: a developer who has not seen the platform creates a module that shows one window and one protected endpoint, following only the documentation; the time and the stumbling points are recorded in the verification report (the spike never measured FR-7 effort). Target: under one working day; a miss is reported, not hidden.

#### PF-11 Module-to-module calls
Implements 8.4.
- AC1: A manifest entry point lists the caller modules allowed to call it (`allowedCallers`). The provisioner grants a caller's service account the target audience only if the target lists the caller; removal on the next registration revokes it.
- AC2: A second reference module (`reference-consumer`, in the monorepo, built from the template) calls the reference module's notes API with its service-account token via the SDK client. Tests: allowed call succeeds; a module not listed gets no audience (401 from Keycloak); a forwarded user token cannot be replayed as service-to-service token and vice versa (`azp` rule); a user id passed in a header is ignored for authorisation.
- AC3: Module-to-module calls do not pass the gateway; their metrics and logs carry the caller and target module ids; calls to a DOWN module fail within the SDK timeout with a mapped error.

### 4.6 Operations

#### PF-12 Alerts and operational overview
Implements NFR-8, NFR-9, FR-10, Q30 (in-product alerts only).
- AC1: Alerts are persisted (`alert` table: id, kind, subject, severity, first seen, last seen, state `open|acknowledged|resolved`, count) with de-duplication per kind and subject, and shown in the Overview and as a badge in the tray for administrators. Acknowledging is audited; an alert resolves itself when the condition clears.
- AC2: Kinds, with their detection: AD/LDAP unreachable (Keycloak federation test through the admin API every 5 minutes and login-failure reason codes); break-glass or unknown master-realm login (PF-2); certificate expiry < 30 days for both host names (the core reads the certificates mounted for the proxy or probes the proxy's TLS endpoints); backup failed or older than 36 hours (PF-15 status); module DOWN longer than 5 minutes; store volume >= 70 %; store verification failure (PF-5); audit chain failure (PF-1); credential rotation due (PF-4); clock drift beyond 60 s against the Keycloak `Date` header or an NTP check; session store or Keycloak degraded.
- AC3: Every alert kind has a test that creates the condition (stubs and fake clocks), observes one alert, observes de-duplication, clears the condition and observes resolution. Alerts are also written as structured log lines at `WARN`/`ERROR` with a stable `alertKind`, so a log shipper can be added later.
- AC4: The Overview works when Keycloak, the store or one module is down; failing detectors report "unknown" instead of failing the page.
- AC5: Until Q30 is decided, nobody is notified outside the platform; the operator documentation states this at the top of the monitoring chapter and the Overview shows the last time an administrator looked at it.

#### PF-13 Operational runbooks and recovery
Implements FR-5a AC3, NFR-5.
- AC1: Runbooks (German and English, `docs/operations/`): first start with bootstrap admin, lock-out recovery (delete the admin mapping in the database, set the variable, restart), break-glass Keycloak admin use, certificate renewal for both host names, credential rotation, module upgrade and rollback, store and audit verification failures, restoring from backup, Keycloak or LDAP outage. Each runbook names the alert that triggers it.
- AC2: Lock-out recovery and break-glass are exercised in an automated test on the compose stack (E2E): recovery restores an admin; a break-glass login produces the alert and the audit entry within one poll interval.

#### PF-14 Privacy tooling
Implements 8.9.
- AC1: An administrator (permission `platform.privacy.manage`, step-up) can, for a user id: export the user's stored preferences, layout and the audit entries naming them as actor or object (JSON and CSV), and delete the user's preferences and layouts. Deleting audit entries is not offered (security accountability; retention rule applies). Each use is audited.
- AC2: Preferences and layouts of users no longer present in the directory are removed 90 days after the user disappeared; a daily job compares stored user ids with the Keycloak user list (disabled counts as present for the first 90 days) and logs counts only. Test with a fake clock.
- AC3: The Art. 30 record input (data categories, purposes, retention, recipients) is generated as a table from the code's configuration and kept in `docs/operations/privacy-record.md` for the DPO (Q23); a CI check fails if a table or column holding personal data is added without an entry.

#### PF-15 Backup completion
Implements NFR-5; extends PRD-0003 SK-17.
- AC1: A backup container runs the skeleton's backup script on a schedule (nightly, time configurable) and on demand from the Operations page. It backs up the platform database, the Keycloak database, every module schema listed by the registry (new modules need no backup configuration), the realm export, the store volume and the globals; the session table's data is excluded.
- AC2: The target is a directory inside the container; the operator mounts the SMB share (Compose volume with the `local` driver and `cifs` options, credentials from a Docker secret) as documented; the target is configuration (Q9, Q18). Output is `age`-encrypted; retention 14 daily and 8 weekly copies is enforced on the target.
- AC3: Each run writes a status record (time, result, sizes, audit head hash and seq, store tree hashes per module) to a small status file on a volume the core reads read-only; the core turns a failed or missing record into the backup alert (PF-12). The record contains no credentials.
- AC4: A restore test in CI restores a backup that contains two modules with their schemas into a clean stack and asserts: registry and mappings present, store hashes match, module data present, audit chain verifies from the checkpoint, sessions invalid. The restore rehearsal on a clean host stays a go-live gate (phase 1c).

## 5. Non-functional requirements
- NFR-P1 Quality: PRD-0001 NFR-2 and the gates of PRD-0003 SK-2 apply to all new code, including the SDK, the kit and the template repository (its generated module is held to the same gates). New packages `audit`, `provisioning` and `store` require >= 90 % line coverage, like `gateway`, `session` and `security`.
- NFR-P2 Security: PRD-0001 NFR-1 and section 8 apply; section 8 of this PRD adds the controls of the new trust boundaries.
- NFR-P3 Accessibility and i18n: the admin UI is held to WCAG 2.2 AA and to the i18n gates of PRD-0003 SK-2; the developer documentation is English, runbooks are German and English.
- NFR-P4 Performance (p95, 20 concurrent users, warm): admin list pages <= 300 ms for 100 modules and 10,000 audit rows per page query; audit insert with chain <= 20 ms at 50 events/s; registration of a 5 MB build <= 10 s end to end; provisioning <= 15 s; audit verification of 1 million rows <= 10 minutes and it does not block inserts.
- NFR-P5 Operability: all new background jobs (verification, retention, polling, backups, privacy) are idempotent, take a database advisory lock so that two core containers never run the same job concurrently, expose last run and result as metrics (tag `job` only) and write a log line per run.
- NFR-P6 Scale: sized for 100 users and about 24 modules (NFR-7); no design choice here may preclude two core instances (ADR-0009).

## 6. UI/UX
The scope is PF-7. The admin UI is a set of shell windows built from the skeleton's design system; it adds data table, form layout, confirmation dialog, status badge and tabs patterns to the design system (documented, themed, tested) so that modules can reuse them. All pages have Loading, Empty, Error and Forbidden states; long tables use keyboard-reachable pagination, not infinite scroll. The step-up dialog is a window-modal dialog; a destructive confirmation never uses colour alone. Text is in de and en from the first commit; German labels are reviewed by the owner. The visual look is deferred to the design PRD; patterns must not hard-code colours.

## 7. Data and interfaces

### 7.1 Data model additions (Flyway)
`audit_event` (partitioned by month; `seq`, `prev_hash`, `row_hash`), `audit_checkpoint`, `keycloak_event_cursor`, `alert`, `job_run`, `role` and `group_mapping` (added `version`, `orphaned`), `module_provisioning` (state per step, key ids, rotation dates; no secrets), `module_version` (added `state`: registered, active, previous, retired), `activation` (actor, review reference, pre-flight result). Database roles added: `slg_provisioner`, `slg_retention`. Personal data columns are registered in the privacy record (PF-14 AC3).

### 7.2 API additions (all under `/core/api/v1/admin`, versioned, OpenAPI-documented, `oasdiff` gated)
| Area | Endpoints |
|---|---|
| Modules | `POST /modules`, `POST /modules/{id}/versions/{v}/activate`, `.../rollback`, `.../disable`, `.../enable`, `DELETE /modules/{id}`, `POST /modules/{id}/rotate-credentials`, `POST /modules/{id}/purge-data`, `GET /modules`, `GET /modules/{id}/versions` |
| Access | `GET/POST/PUT/DELETE /roles`, `GET/PUT /mappings`, `GET /directory/groups`, `GET /effective-permissions?user=` and `?group=`, `POST /sessions/revoke` |
| Audit | `GET /audit`, `GET /audit/export`, `POST /audit/verify` |
| Operations | `GET /overview`, `GET /alerts`, `POST /alerts/{id}/acknowledge`, `POST /backup/run`, `GET /jobs` |
| Privacy | `GET /privacy/export?user=`, `DELETE /privacy/preferences?user=` |

### 7.3 Contract artefacts
Manifest JSON schema (version 1.x) gains `allowedCallers` (in 1.0 as an optional field already named by PRD-0001 FR-4; if the skeleton schema lacks it, it is added as 1.1), `errorCodes`, and the `icon` rule of PF-5 AC6. Artefacts and where they are published: SDK (GitHub Packages), `@slg-os/eslint-config`, `slg-os-contract-tests`, schema and OpenAPI (attached to each release), template repository.

### 7.4 Toolchain
Versions are those of ADR-0013 (PRD-0003 section 7.4). New tools (artefact signing, SBOM, npm provenance, `cifs` volumes, PostgreSQL partition tooling) are chosen and verified against official documentation when each milestone starts and recorded in the ADRs below; none is assumed to work from this PRD alone.

### 7.5 Decisions and ADRs produced by this phase
| # | Decision (proposal unless marked) | ADR |
|---|---|---|
| D1 | **Decided by the owner:** four-eyes release is a process in the module repository; the tool records the review reference and does not enforce a second approver (Q38) | recorded in ADR-0018 |
| D2 | **Decided by the owner:** SDK and template are distributed through GitHub Packages and a GitHub template repository synced from the monorepo | ADR-0019 |
| D3 | Credential delivery from the provisioner to module containers (options in Q39) | ADR-0018 |
| D4 | Audit hash chain, monthly partitions, checkpoints and the `slg_retention` role | ADR-0020 |
| D5 | Keycloak event ingestion by polling the admin events API with a read-only service account (not a custom Keycloak provider, which would add a build and upgrade burden to Keycloak) | ADR-0021 |
| D6 | Admin UI as shell windows, not a federated module; one permission per capability bundled in `platform-admin` | ADR-0022 |
| D7 | Icons are design-system identifiers or PNG, never module SVG | noted in ADR-0010's revision |

## 8. Security considerations
- Admin plane: all admin endpoints deny by default, check the permission server-side and require CSRF and Origin (PRD-0003 SK-4); registration, activation, rollback, mapping, revoke, rotation, purge, export and privacy actions require step-up verified by `auth_time`. Mass assignment is prevented by explicit request DTOs; list endpoints never return fields the UI does not use.
- Stolen admin session: the attacker needs a fresh step-up for destructive actions; "revoke all" and mapping changes are audited with before and after; the 10 h absolute session and the 5-minute step-up bound the window. A compromised administrator can still register a malicious module: that risk is accepted through ADR-0007 and the process-based review of Q38, and stated here so the owner knows the tool does not enforce a second person.
- Provisioner: runs with the minimum database and Keycloak rights and is separate from the runtime path (PF-4 AC2); credentials are generated with a CSPRNG, shown to nobody, delivered as files, and never logged; Keycloak client secrets and database passwords are rotated at least yearly with an alert.
- Audit: append-only grants, hash chain, daily verification, checkpoint on retention, read events audited, CSV export neutralised against formula injection, usernames of failed logins not stored. Audit rows contain pseudonymous ids and IP addresses, which are personal data; access is limited to `platform.audit.read`.
- Archive handling: zip-slip, symlink, decompression-bomb and type-confusion tests (PF-5); the shell never renders module-supplied HTML or SVG; manifest strings are text only.
- Module-to-module: audience granted only by the target's `allowedCallers`; `azp` distinguishes forwarded user tokens from service tokens; a user id between modules is informational.
- Supply chain: SDK and template are signed or carry provenance; the template's CI pins action versions; module images are signed and referenced by digest; the kit verifies the build layout; package visibility is limited to the organisation (Q43).
- Alerts and logs never contain secrets or tokens; alert subjects use module ids and host names, not user data.
- Data protection: new personal data are audit rows (pseudonymous ids, IPs), Keycloak event cursors (no personal data), alerts (none) and the privacy tooling that touches preferences. Retention: audit 6 months, preferences 90 days after the user disappears, sessions as in the skeleton. DPO input: audit viewer purpose and access, ingestion of login events, privacy tooling (Q23, Q42).

## 9. Testing strategy
- Backend: JUnit/AssertJ/Mockito with Testcontainers (PostgreSQL with partitions and roles, Keycloak with the committed realm and the events configuration, LDAP stub); concurrency tests for the chain (threads and two containers); property-style tests for the canonical encoding; archive fuzz corpus; ArchUnit rules (the provisioning package is the only user of the provisioner credentials; no admin controller bypasses the permission annotation, enforced by a test that lists every mapped endpoint).
- Admin API: a table-driven test enumerates all admin endpoints and asserts 401 without session, 403 without permission, 403 without CSRF/Origin, and step-up requirement where specified.
- Frontend: Vitest + Testing Library + MSW for admin pages; Playwright in Chromium, Firefox and WebKit for the main admin flows: register and activate a module, rollback with orphaned permissions, role and mapping edit, effective permissions, revoke sessions, audit viewer and export, alert lifecycle, privacy export; axe on every page (light, dark, forced-colors, narrow).
- E2E on the compose stack: provisioning with credential delivery, rotation, module-to-module call with `reference-consumer`, break-glass and lock-out recovery, backup and restore with two modules, store tampering detection, Keycloak event ingestion with replay, template-generated module registered and opened (PF-10 AC2).
- Contract kit and template: golden valid and invalid modules, mutation-style negative tests, drift check between template repository and directory, SDK backward-compatibility build (PF-8 AC5).
- Time-dependent behaviour (alerts, rotation reminders, retention, polls) uses an injectable clock.
- Mutation check on `audit`, `provisioning`, `store`, `security` in each milestone review.
- Coverage: gates as in NFR-P1; tests assert behaviour including failure paths; every fixed defect gets a regression test.
- Manual and external: effort measurement by a developer new to the platform (PF-10 AC5), DPO review of audit and privacy (Q23, Q42), SMB target test against the real share (Q9), real-AD group listing (Q41).

## 10. Rollout and operations
- Milestones, each its own pull request (or several) through the implementation workflow:
  - B1 Audit: hash chain, partitions, checkpoints, retention role and job, read API and export, Keycloak event ingestion (PF-1, PF-2, PF-3).
  - B2 Provisioning and store: provisioner with separate roles, credential delivery ADR-0018 and its test, rotation, archive input, verification jobs, retention, staged activation (PF-4, PF-5).
  - B3 Access management API: roles, mappings, directory groups, effective permissions, session revocation, optimistic locking (PF-6).
  - B4 Operations: alerts and overview API, job framework, backup completion with status record, runbooks, privacy tooling (PF-12, PF-13, PF-14, PF-15).
  - B5 Admin UI: all pages on top of B1 to B4, design-system patterns (PF-7). Starts with stubs once the B1 to B3 API shapes are fixed and finishes after B4.
  - B6 SDK completion and publishing: complete SDK, contract artefacts, GitHub Packages release workflow, ADR-0019 (PF-8).
  - B7 Kit, template and second module: contract test kit, template and sync workflow, `reference-consumer` and module-to-module calls, developer documentation, effort measurement (PF-9, PF-10, PF-11).
  - B8 Verification: E2E completion in three browsers, restore test with two modules, verification report, ADR proposals (`docs/verification/platform-report.md`).
  B1 to B3 can run in parallel; B6 can start with B1. B5 needs the API shapes of B1 to B4; B7 needs B6.
- The phase is done when: every AC of PF-1 to PF-15 is green on `main` in CI; a developer new to the platform has built and registered a module from the template following the documentation (PF-10 AC5 recorded); the verification report and ADR-0018 to 0022 are merged; the owner has reviewed the audit viewer and privacy tooling with the DPO input requested (Q42) or the blockers are named.
- Upgrade path of the core: Flyway migrations are forward-only with expand/contract discipline; the audit partition and re-chain migration runs once under an advisory lock and is tested on a copy of a database with 1 million rows. Rollback of the core after B1 requires the previous version to tolerate the new audit columns (expand only).
- No production deployment is implied; phase 1c gates apply before go-live.

## 11. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| Q38 | Four-eyes release of module versions | Benjamin Honauer | **Decided 2026-10-19:** process in the module repository; the tool records the review reference and enforces nothing (PF-5 AC4, D1) |
| Q39 | Credential delivery from the provisioner to module containers without a Docker socket: (a) a shared named volume with per-module `subpath` mounts, files written by the core with the module's uid; (b) an operator CLI container that calls the admin API and writes host secret files; (c) one-time retrieval by the module from the core at start with a bootstrap token. Criteria: no privileged mount in the core, works with Docker Compose on the target host (ADR-0009), restart-safe, rotation without recreating the compose project. Decided in ADR-0018 by a short spike inside B2 | platform team | start of B2 |
| Q40 | Which business module is first (pilot team)? A pilot gives the template and SDK a real user before the contract freezes and shapes PF-9 and PF-10 | Benjamin Honauer | before B6 |
| Q41 | Scope of the AD groups the Keycloak federation exposes: all groups, or only an OU or name filter? (affects the group picker, data minimisation and sync load) | Benjamin Honauer, with the AD owner | start of B3 |
| Q42 | DPO and works council view on: ingestion of login events (IP, outcome) into the audit log, the audit viewer and CSV export, and the privacy tooling. Closes with Q23 | Benjamin Honauer | before go-live; input requested before B1 merges |
| Q43 | GitHub organisation settings for GitHub Packages (package visibility "internal/private", repository access for module repositories, token scope for CI) | platform team | start of B6 |
| Q44 | Q30 reminder: alert e-mail via SMTP relay stays deferred; revisit before go-live | Benjamin Honauer | phase 1c |
| Q45 | Is an external anchor for the audit chain head wanted (for example a periodically written line in a separate, write-protected store)? Default: no; the head hash goes to logs and backup manifests | Benjamin Honauer | before go-live |
| Q35 | Permissions for module background work on behalf of a user without a live session (from PRD-0003); decided here only if the pilot module needs it | platform team | before B7 or the first module with jobs |
| Q23, Q24, Q25, Q31 to Q34, Q36, Q37 | Unchanged, tracked in PRD-0001 and PRD-0003 | see there | see there |

## 12. Consistency notes on earlier documents
Found while writing this PRD (not changed in the earlier documents before the owner approves; PRD-0001 gets revision 3 together with the skeleton's notes C1 to C14):
- D1: PRD-0001 8.3 says "versions are promoted four-eyes"; the owner decided on a process-based implementation (Q38). Revision 3 rewrites the sentence to say so, so that the PRD does not claim a tool control that does not exist.
- D2: PRD-0001 FR-4 lists "integrity hashes computed by the core" and the registration checklist still says the manifest includes the SHA-384 hash of the remote entry; the checklist item is outdated and is rewritten in B7 (the module supplies no hashes).
- D3: PRD-0001 FR-9 lists "reads of the audit log" as audited events and ADR-0008 lists security events only; the reads are covered by "administrative actions" and are kept (PF-3 AC2).
- D4: PRD-0001 FR-4 requires the provisioner to deliver the module database password "as a Docker secret", which a running container cannot create without the Docker API; PF-4 AC3 and Q39 replace the wording with "as files under `/run/secrets/`".
- D5: PRD-0001 NFR-2 says module CI "uploads the coverage report as evidence"; where it is stored is unspecified. PF-9 prints a report and the activation step records its link.
- D6: PRD-0001 8.9 retention "preferences 90 days after the account disappears from AD" has no mechanism; PF-14 AC2 defines it.

## 13. Review log
No review yet (Draft).
