# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

`slg-os` ("SLG Operating System", System Logistics) is in its implementation phase. The specification is complete (PRD-0001 to PRD-0005, ADR-0001 to ADR-0013). The repository contains the build and CI foundation of milestone M1 (PRD-0003): a Maven multi-module backend, an npm workspace frontend, the CI quality gate and its scripts. Features follow milestone by milestone (`ROADMAP.md`).

Documentation map: architecture decisions in `docs/adr/` (accepted ADRs are not edited, supersede them with a new ADR), PRDs in `docs/prd/` (template and index there), review checklists in `docs/reviews/` (use them for the independent review agents), the plan in `ROADMAP.md`, spike reports in `docs/spikes/` (evidence behind proposed ADRs; the spike code lives only on branch `spike/shell`). The pinned toolchain versions are in `docs/adr/0013-toolchain.md`, generated from `docs/adr/0013-versions.json`.

## What slg-os is

A locally hosted, web-based internal platform with an OS-like look & feel. It is the **host/framework** for many internal tools and automations of different departments. slg-os itself provides:

- the application shell (desktop/launcher UI, navigation, theming, i18n)
- authentication against the local Active Directory
- authorization based on AD groups
- a **mandatory, defined contract** for developing and integrating modules, which live in **separate GitHub repositories**

Modules are added incrementally and lightly: registering a module and assigning permitted AD groups must not require changes to the core. slg-os is the stable frame for multi-department, multi-purpose modules.

## Tech stack (fixed)

- Backend: latest Java with Spring (Boot, Security, Data, ...) plus further frameworks as needed; built with Maven (ADR-0004)
- Frontend: React with Vite and Bootstrap; module frontends via Module Federation (ADR-0005)
- Database: PostgreSQL
- Identity: Keycloak federating the Active Directory, OIDC (ADR-0001); the core is a backend-for-frontend, tokens never reach the browser (ADR-0012)
- Deployment: Docker, single server with Docker Compose, fewer than 100 users (ADR-0009); on-prem hosting without cloud runtime dependencies (outbound pull of module images from ghcr.io is allowed, PRD-0001 Q10/Q18)
- Layout: monorepo for the core (ADR-0004)

## Commands

Prerequisites: JDK 25 (Temurin), Node.js 22, Maven through the wrapper `./mvnw` (Maven 3.9.16, checksum pinned), Docker for the compose stack (from M2). Run all commands from the repository root unless noted.

```bash
# Backend (Maven multi-module, Spring Boot 4.1)
./mvnw verify                                   # build, unit and integration tests, JaCoCo gate (80 % lines per module)
./mvnw -pl backend/core -am verify              # one module and what it depends on
SLG_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres SLG_TEST_PG_USER=postgres ./mvnw -pl backend/core -am verify   # integration tests against your own PostgreSQL instead of Testcontainers (no Docker needed)
./mvnw -pl backend/sdk-backend -Dtest=ContractVersionTest test   # a single test class (unit tests)
node scripts/ci/coverage.mjs critical           # gateway/session/security package trees at 90 %

# Frontend (npm workspaces in frontend/)
cd frontend && npm ci --ignore-scripts          # install from the lock file only
npm run lint                                    # ESLint, includes the ban on literal user-visible strings
npm run typecheck && npm run i18n:check         # TypeScript, de/en key parity and ICU validity
npm run test:coverage                           # Vitest with coverage gate (80 % lines per package)
npm run test -w @slg-os/shell -- src/App.test.tsx   # a single test file
npm run build

# CI scripts and checks (no dependencies)
node --test "scripts/ci/test/*.test.mjs"        # unit tests of the CI scripts
node scripts/ci/check-docs.mjs                  # indices, status lines, links, file size, secret patterns
node scripts/ci/check-workflows.mjs             # pinned actions, no privileged PR trigger, no integrity-weakening flags
node scripts/ci/check-versions.mjs [--render]   # version table against the build files (render rewrites the ADR table)
node scripts/ci/coverage.mjs report out.json    # coverage report; compare with a baseline: coverage.mjs compare current baseline
CI=true node scripts/ci/selftest.mjs [fault]    # destructive: applies deliberate faults, CI only
```

The coverage gates (80 % lines per module or package, 90 % for `gateway`, `session`, `security`, regression limit 2 points) are part of the build and are never lowered. Dependency changes go through `docs/adr/0013-versions.json` and the lock file together.

## Workflows

### Working agreement: how requests are handled
- A request for a PRD or an implementation starts the matching workflow below and runs it **to the end without asking for confirmation of intermediate steps**: drafting, review agents, integrating findings, tests, commits and pushes to the work branch. Do not ask "should I start the review?" or "shall I continue?".
- Ask the owner only (1) for a decision that cannot be derived from PRDs, ADRs and this file and that has real consequences, **collected into one interview at the start** (non-blocking questions go into the PRD as open questions instead), and (2) at the **final gate**: PRDs, ADRs and design decisions need the owner's approval (a comment on the pull request or a message in the chat); all other pull requests are merged by Claude as soon as the CI gate is green and the review findings are integrated, without further questions.
- Finish with one short report: what was done, what the author decided where reviews differed, what needs the owner. No step-by-step narration.
- Shortcuts: `/prd <topic>` and `/implement <PRD>` run the workflows (project skills in `.claude/skills/`); both end with a pull request.

### Git rules: feature branch, pull request, CI gate
- **Every change goes through a feature branch `claude/<topic>` and a pull request into `main`.** Never push to `main` directly (the only exception is the owner explicitly ordering it for a single change).
- **The CI check `gate` must be green before a pull request is merged.** Branch protection on `main` enforces this (pull request required, required check `gate`, no force pushes). Merge with a squash commit; delete the branch afterwards. A red gate is fixed on the branch, never bypassed.
- Open the pull request early (draft) and push after each workflow step; fill in `.github/pull_request_template.md`; list the independent review resolutions in the description.
- Commit messages in English; stage explicit paths, never `git add -A` where ignored build output or spike files could become untracked; never commit secrets or files over 50 MB. Spike code stays on branch `spike/shell` and is never merged.
- If the stop hook reports unpushed commits, verify with `git ls-remote origin <branch>` and push only if the remote really lacks the commit.

### CI quality gate
`.github/workflows/ci.yml` runs on every pull request and on `main`; the job `gate` aggregates the required jobs. Today: documentation checks (ADR and PRD indices and status lines, relative links, file size, secret patterns via `scripts/ci/check-docs.mjs`; markdownlint). With the walking skeleton the gate is extended, and a pull request must not weaken it: backend and frontend builds, unit and integration tests with the coverage gates of PRD-0001 NFR-2 (>= 80 %, >= 90 % for gateway, session and security), i18n key parity de/en and a no-literal-string lint, E2E on the real stack in Chromium, Firefox and WebKit with axe checks, dependency/image/secret scanning. Run the same checks locally before pushing where possible.

### Specification workflow
1. Draft the PRD from `docs/prd/TEMPLATE.md` in English (number and file name from the index in `docs/prd/README.md`).
2. Start independent review agents (fresh context, in parallel, they must not edit the PRD) for at least: requirements completeness and consistency, security, UI/UX and accessibility, operations and testability (add permissions or data protection where relevant). Each writes findings with severity and ready-to-paste text into the scratchpad; use `docs/reviews/spec-review-checklist.md`.
3. Integrate the findings into the PRD, resolve conflicts between reviews and write the decision into the PRD's review log, update the indices (`docs/prd/README.md`, `ROADMAP.md`), push.
4. Report. After the owner approves, set the status to Approved and merge the pull request.

### Implementation workflow
1. Implement the requirement from an approved PRD. **Code, identifiers, comments and commit messages are English.**
2. **UI/UX is bilingual (German / English) from the start, strictly via i18n.** No hard-coded user-visible strings, in the shell or in any module. Missing translation keys in either language are a defect.
3. Implement tests. **Global test coverage must be >= 80 %**, enforced in the build (fail the build below the threshold); details per PRD-0001 NFR-2.
4. Start independent review agents (same rules as above, perspectives: requirements completeness, security, UI/UX, test coverage, operations as relevant; `docs/reviews/implementation-review-checklist.md`); integrate and correct the implementation, rerun tests, push.
5. Report with test results; update `ROADMAP.md` and the commands section of this file when the build tooling changes. Merge the pull request when the gate is green.

"Independent" means the reviewer is a separate agent (fresh context), not the one that wrote the artifact.

### Exception: spike code
Disposable code under `spikes/` (branch `spike/shell`, see PRD-0002) is exempt from the 80 % coverage gate and from the full implementation workflow, approved by the owner on 2026-10-19. The exception is limited to the spike: `spikes/` is part of no Maven module, npm workspace, coverage gate, image build or release pipeline, is never merged into `main`, and nothing from it is copied into production code without the normal workflow. The spike's acceptance and security tests must still be automated.

## Platform requirements and architectural principles

These are the intended guardrails for the framework. Refine them in PRDs and ADRs; do not silently deviate. Decisions already taken are in `docs/adr/`.

**Module contract**
- One mandatory way to build and integrate a module (manifest/descriptor, lifecycle, extension points). Modules are developed in their own GitHub repos and consumed by slg-os; no module-specific code in the core.
- A module declares in its manifest: id, name, version, icon, i18n resources, routes/entry points, required permissions/roles, and the compatible slg-os contract version.
- The contract is versioned. Breaking changes need a new contract version and a migration path for existing modules.
- Provide a module template/SDK so new modules start compliant (auth, layout, i18n, permissions, logging wired in).

**Authentication and authorization**
- Authentication via the local Active Directory through Keycloak (ADR-0001). Modules validate OIDC tokens; they never talk to AD or Keycloak for login.
- Authorization derives from AD groups. Modules never implement their own auth; they consume the SDK's identity and permission API.
- Modules declare permissions in their manifest; admins map AD groups to roles/permissions in the slg-os admin UI (ADR-0006). Deny by default.
- Enforce permissions on the backend for every request; frontend hiding is only UX.
- Module-to-module and module-to-core calls are authenticated and authorized as well.

**Consistent UI/UX**
- All modules use the shared shell, design system (React components on Bootstrap theme), layout, navigation, error/empty/loading states, and accessibility baseline. Modules must not ship their own global styling or look.
- Light/dark theme and German/English language are controlled by the shell and honored by modules.

**Data**
- PostgreSQL is the only database. Schema changes via versioned migrations (e.g. Flyway/Liquibase). Each module owns its schema/namespace and must not read another module's tables; cross-module data goes through defined APIs/events.

**Cross-cutting capabilities provided by the core (do not reimplement in modules)**
- security audit log (logins, permission mapping changes, module registration, admin actions only; business-action logging is the module's own concern, ADR-0008), structured logging, correlation IDs
- configuration and secrets handling (no secrets in code or images)
- notifications, background jobs/scheduling for automations
- file/attachment handling policies
- health/readiness endpoints and metrics per module
- error handling conventions and a consistent API style (versioned REST, OpenAPI documentation)

**Operations**
- Everything runs via Docker; reproducible builds, config via environment, persistent volumes for data.
- Module deployment/upgrade must not require a full platform rebuild once the mechanism is defined; isolate a failing module from the rest of the platform.
- Backup/restore and upgrade paths are part of the design, not an afterthought.

**Security baseline**
- Follow OWASP ASVS-style practices: input validation, output encoding, CSRF/XSS protection, secure headers/CSP, least privilege, dependency scanning.
- Modules come only from internal, reviewed teams (ADR-0007), so isolation targets faults rather than malicious code. Still validate manifests, give each module its own schema/credentials, and authenticate every call. Use `docs/reviews/module-registration-checklist.md` when registering.

## UI model

Window-based desktop shell (ADR-0003): modules render in shell-managed, resizable windows and must not assume the full viewport. The shell owns theme, language and window state.

## Open decisions

PRD-0001 revision 2 (spike findings and ADR-0012 applied, independently reviewed) was approved by the owner on 2026-10-19; implementation can start from it with phase 1a. Section 11 records the owner's decisions (e.g. no Kerberos SSO, a backend-for-frontend so that no token reaches the browser (ADR-0012), only the current contract major supported, admin-only module registration, ghcr.io for module images, 6-month audit retention) and the open items (works council/DPO sign-off, Firefox/WebKit/Safari/Edge gates, production-mode Keycloak checks, the gateway session store). ADR-0010 (Module Federation approach) and ADR-0011 (window manager) are proposed, not accepted. Version numbers and tools named in the PRD (JDK, Spring Boot, test tools) are proposals until verified when the project skeleton is created; do not treat them as decided.
