# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

`slg-os` ("SLG Operating System", System Logistics) is a greenfield project in the specification phase. The repository contains documentation only (`docs/adr`, `docs/prd`, `docs/reviews`); no code or build files exist yet. **Build, lint and test commands do not exist yet.** When the project skeleton is created, add the real commands here (build, run, lint, full test run, single test, coverage) instead of guessing.

Documentation map: architecture decisions in `docs/adr/` (accepted ADRs are not edited, supersede them with a new ADR), PRDs in `docs/prd/` (template and index there; start from PRD-0001), review checklists in `docs/reviews/` (use them for the independent review agents).

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
- Identity: Keycloak federating the Active Directory, OIDC (ADR-0001)
- Deployment: Docker, single server with Docker Compose, fewer than 100 users (ADR-0009); on-prem, no cloud dependencies
- Layout: monorepo for the core (ADR-0004)

## Workflows

### Specification workflow

1. **PRDs are always written in English.**
2. A PRD is drafted first (before any implementation).
3. An **independent review agent** reviews the PRD from multiple perspectives (at least: completeness of requirements, security, UI/UX; add others as relevant, e.g. permissions, operations, data protection) and **corrects and extends** the PRD directly.

### Implementation workflow

1. Implement the requirement. **Code, identifiers, comments, commit messages are English.**
2. **UI/UX is bilingual (German / English) from the start, strictly via i18n.** No hard-coded user-visible strings, in the shell or in any module. Missing translation keys in either language are a defect.
3. Implement tests. **Global test coverage must be >= 80%**, enforced in the build (fail the build below the threshold).
4. An **independent review agent** reviews the implementation from multiple perspectives (requirements completeness, security, UI/UX, test coverage; add others as relevant) and **corrects and extends** it.

"Independent" means the reviewer is a separate agent (fresh context), not the one that wrote the artifact.

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

## Open decisions (resolve in PRD-0001 review before implementing)

PRD-0001 is reviewed and awaits owner approval. Its section 11 lists the open questions with a "needed by" milestone (Kerberos SSO, Java/Spring versions, window manager, Module Federation plugin spike, token storage, audit retention, ADR-0008 clarification, ...). Version numbers and tools named in the PRD are proposals until verified in the spike; do not treat them as decided.
