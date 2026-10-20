# Roadmap

Status as of 2026-10-20. The roadmap shows sequence and dependencies, not dates. It is updated whenever a PRD is approved or a phase is finished.

Legend: **Done**, **Next**, **Planned**, **Later**. Owner gates (needs a decision or sign-off from the owner) are marked with ◆.

## Work tracks and dependencies
Three tracks run in parallel, each on its own feature branch and pull request; shared files (`ci.yml`, POMs, lock file, migration versions) are merged in small steps to keep conflicts low.

| Track | Sequence | Starts when |
|---|---|---|
| A Core (critical path) | M2 identity and session, M3a registry, M3b store and gateway, M4 shell, M5 reference module and SDK, M6a to M6c | M1 done (2026-10-20, ADR-0013 accepted); M2 done (2026-10-20, sessions and E2E harness in three browsers; ADR-0015 and ADR-0016 proposed) |
| B Visual language | V1 tokens and pipeline, V2 assets and catalogue; then V3a (needs M4), V3b, V3c (needs B5a), V4 (needs B5d), V5, V6, V7 (needs V3 to V6, B5b to B5d, B7a) | V1 and V2 now (no dependency on the core) |
| C Platform capabilities | B1 audit and job framework (needs the audit table from M2), B2a/B2b (need M3), B3, B4, B5a to B5d, B6, B7a to B7c, B8 | B1 after M2 is merged |

Open items on the way: Q59 (Lead-Radar backend facade) blocks B6 only; branch protection on `main` is an owner setting.

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
- First step: [PRD-0003](docs/prd/0003-walking-skeleton.md) (repository layout, verified versions of JDK, Spring Boot and tools, CI, milestones M1 to M6c), approved 2026-10-19. M1 foundation (build, CI gate, self-test) delivered 2026-10-20; M2 identity and session delivered 2026-10-20 (PR #18 backend, PR #19 compose, Keycloak and E2E); next: M3a (track A) and V1 (track B).

## Phase 1b: Platform capabilities (PRD in progress)
- [PRD-0004](docs/prd/0004-platform-capabilities.md) (approved 2026-10-19): milestones B1 to B8 (B2, B5 and B7 split into sub-milestones). Owner decisions 2026-10-19: four-eyes release is a process in the module repository (Q38); SDK and template via GitHub Packages and a template repository.
- Admin UI and permission mapping from AD groups to roles and permissions.
- Security audit log with hash chain.
- Immutable module store, registration pipeline and module provisioner (database schema and Keycloak client per module).
- Module template and SDK (backend and frontend), contract tests, module registration checklist in practice.
- Alerts in the admin overview, backup of the module store, health aggregation.

## Visual language and design system (planned, after the skeleton)
- [PRD-0005](docs/prd/0005-visual-language.md) (approved 2026-10-19): owner chose direction C "Soft glass" on 2026-10-19 (milestones V1, V2, V3a-c, V4 to V7).
- Own PRD: visual language, tokens, typography, iconography, desktop metaphor (start menu, desktop icons, tray), window chrome, motion.
- Interactive prototypes for review, visual regression tests, usability test with users from two departments. ◆ design direction and sign-off by the owner (ideally with a person with design experience).
- Fixes found in the spike screenshots: layout with banners, overlapping window controls, dark theme polish.

## Phase 1c: Go-live gates (planned)
- Restore rehearsal on a clean host, load test, chaos tests (Keycloak, PostgreSQL, core restart, module down).
- Browser gates: Firefox, WebKit/Safari, Edge on an office PC (`Alt+Space`, shortcut conflicts, performance).
- Manual screen-reader passes (NVDA + Firefox, JAWS + Chrome).
- ◆ Decided by the owner on 2026-10-19: no formal works-council or DPO gate (Q23), remaining risk accepted (Q24), no alert e-mails (Q30).

## Later
- First business modules, each in its own repository with its own PRD, built from the template.
- Optional: Kerberos single sign-on (Q1), e-mail alerts, notifications and job scheduling capabilities, further languages.
- Not planned: high availability, Kubernetes, modules from outside the platform team's review process.

## Open decisions and who closes them
| Item | Needed by | Owner |
|---|---|---|
| Q23 works council and DPO: **no formal gate, owner decision 2026-10-19** | closed | owner |
| Q24 remaining risk after the BFF: **accepted 2026-10-19** | closed | owner |
| Q25a Firefox checks (Safari not required, decided 2026-10-19) | phase 1c | platform team |
| Q25b manual gates: **owner and team, decided 2026-10-19** | phase 1c | owner |
| Q26 token exchange or down-scoping in production Keycloak | phase 1a | platform team |
| Q27 production Keycloak host name and AD-CA certificates (CI and local use test names, decided 2026-10-19) | before go-live | owner |
| Q28 confirm PostgreSQL session store | phase 1a | platform team |
| Q30 SMTP relay for alerts: **no e-mail alerts, decided 2026-10-19** | closed | owner |
| Q47 pilot module (Lead-Radar-Plattform): **frontend only, decided 2026-10-19**; backend facade or ADR exception is Q59 | start of phase 1b milestone B6 | platform team |
| Enable branch protection on `main` ([instructions](docs/branch-protection.md)) | now | owner |

## Main risks
- Visual quality is not yet specified or designed; it is planned after the skeleton and needs an owner decision on direction and review capacity.
- Module Federation, the window manager and the gateway are verified only on spike code and in Chromium.
- The gateway sits on every module call; performance and failure behaviour are verified only in phase 1a/1c.
- Works council and data protection approval can delay go-live independent of the technical work.
