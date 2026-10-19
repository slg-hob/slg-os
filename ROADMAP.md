# Roadmap

Status as of 2026-10-19. The roadmap shows sequence and dependencies, not dates. It is updated whenever a PRD is approved or a phase is finished.

Legend: **Done**, **Next**, **Planned**, **Later**. Owner gates (needs a decision or sign-off from the owner) are marked with ◆.

## Phase 0: Specification and spike (done)
- Done: ADR-0001 to 0012 (identity, modules, UI, stack, permissions, trust, audit, deployment, Module Federation, window manager, backend-for-frontend).
- Done: [PRD-0001](docs/prd/0001-platform-core.md) platform core, approved in revision 2.
- Done: [PRD-0002](docs/prd/0002-shell-spike.md) spike and [report](docs/spikes/2026-shell-spike-report.md). Result: Module Federation with Vite is viable in Chromium; in-house window manager; tokens must stay on the server.
- Done: working rules with feature branches, pull requests and a CI quality gate (`gate`) as merge condition; documentation checks run today, build, test and coverage jobs join with the skeleton.
- Open from the spike, closed during phases 1a to 1c: ADR-0010 and ADR-0011 are proposed and become accepted when their gaps are closed (production-mode Keycloak, Firefox/WebKit/Safari checks, manual screen-reader pass, window-manager gaps).

## Phase 1a: Walking skeleton (next)
Goal: the thinnest end-to-end slice on the real stack, so everything after it builds on proven ground.
- Monorepo layout and build (Maven multi-module backend, Vite/React frontend, Docker Compose), with coverage gates and CI.
- Compose stack: reverse proxy with two host names, Keycloak in production mode (PostgreSQL), PostgreSQL, core backend.
- Core backend: backend-for-frontend (login, session cookie, CSRF), PostgreSQL session store, gateway to module backends, module registry through the admin API, shell document with nonce and import map.
- Shell: sign-in, desktop with in-house window manager (two windows), language and theme switch, state components.
- One reference module (backend and frontend) loaded through Module Federation.
- E2E tests against the real stack, `pg_dump` backup script.
- First step: [PRD-0003](docs/prd/0003-walking-skeleton.md) (repository layout, verified versions of JDK, Spring Boot and tools, CI, milestones M1 to M6c), approved 2026-10-19. Next: M1 foundation.

## Phase 1b: Platform capabilities (PRD in progress)
- [PRD-0004](docs/prd/0004-platform-capabilities.md) (reviewed, awaiting approval): milestones B1 to B8 (B2, B5 and B7 split into sub-milestones). Owner decisions 2026-10-19: four-eyes release is a process in the module repository (Q38); SDK and template via GitHub Packages and a template repository.
- Admin UI and permission mapping from AD groups to roles and permissions.
- Security audit log with hash chain.
- Immutable module store, registration pipeline and module provisioner (database schema and Keycloak client per module).
- Module template and SDK (backend and frontend), contract tests, module registration checklist in practice.
- Alerts in the admin overview, backup of the module store, health aggregation.

## Visual language and design system (planned, after the skeleton)
- Own PRD: visual language, tokens, typography, iconography, desktop metaphor (start menu, desktop icons, tray), window chrome, motion.
- Interactive prototypes for review, visual regression tests, usability test with users from two departments. ◆ design direction and sign-off by the owner (ideally with a person with design experience).
- Fixes found in the spike screenshots: layout with banners, overlapping window controls, dark theme polish.

## Phase 1c: Go-live gates (planned)
- Restore rehearsal on a clean host, load test, chaos tests (Keycloak, PostgreSQL, core restart, module down).
- Browser gates: Firefox, WebKit/Safari, Edge on an office PC (`Alt+Space`, shortcut conflicts, performance).
- Manual screen-reader passes (NVDA + Firefox, JAWS + Chrome).
- ◆ Works council agreement and data protection decision (Q23), acceptance of the remaining risk (Q24), SMTP relay decision for alert mails (Q30, deferred).

## Later
- First business modules, each in its own repository with its own PRD, built from the template.
- Optional: Kerberos single sign-on (Q1), e-mail alerts, notifications and job scheduling capabilities, further languages.
- Not planned: high availability, Kubernetes, modules from outside the platform team's review process.

## Open decisions and who closes them
| Item | Needed by | Owner |
|---|---|---|
| Q23 works council and DPO | before go-live | owner |
| Q24 remaining risk after the BFF | before go-live | owner |
| Q25a Firefox/WebKit/Safari checks | phase 1c | platform team |
| Q25b owner of manual gates (screen reader, Edge, measurements) | phase 1c | owner |
| Q26 token exchange or down-scoping in production Keycloak | phase 1a | platform team |
| Q27 Keycloak host name, certificates, internal back-channel route | phase 1a | owner |
| Q28 confirm PostgreSQL session store | phase 1a | platform team |
| Q30 SMTP relay for alerts | deferred | owner |
| Enable branch protection on `main` ([instructions](docs/branch-protection.md)) | now | owner |

## Main risks
- Visual quality is not yet specified or designed; it is planned after the skeleton and needs an owner decision on direction and review capacity.
- Module Federation, the window manager and the gateway are verified only on spike code and in Chromium.
- The gateway sits on every module call; performance and failure behaviour are verified only in phase 1a/1c.
- Works council and data protection approval can delay go-live independent of the technical work.
