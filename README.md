# slg-os

SLG Operating System: a locally hosted, web-based platform with an OS-like desktop for internal tools and automations of System Logistics. It signs users in against the local Active Directory, authorizes by AD groups, and hosts independently developed modules through a mandatory, versioned module contract.

**Status:** implementation phase. The specification (PRD-0001 to PRD-0005) is approved; milestone M1 of the walking skeleton (build and CI foundation) is in place. Build and test commands are in [`CLAUDE.md`](CLAUDE.md).

## Decisions at a glance

| Area | Decision |
|---|---|
| Identity | Keycloak federating Active Directory, OIDC for everything ([ADR-0001](docs/adr/0001-keycloak-as-identity-provider.md)) |
| Modules | Separately deployed services from their own repos, registered by manifest ([ADR-0002](docs/adr/0002-modules-as-separate-services.md)) |
| UI | Window-based desktop; module frontends via Module Federation ([ADR-0003](docs/adr/0003-window-based-desktop-ui.md), [0005](docs/adr/0005-module-federation-frontend.md)) |
| Stack | Java + Spring (Maven), React + Vite + Bootstrap, PostgreSQL, Docker ([ADR-0004](docs/adr/0004-monorepo-and-maven.md)) |
| Permissions | Modules declare, admins map AD groups in the UI ([ADR-0006](docs/adr/0006-permission-model.md)) |
| Deployment | Single server, Docker Compose, < 100 users ([ADR-0009](docs/adr/0009-deployment-scale.md)) |

## Documentation map

- [`ROADMAP.md`](ROADMAP.md): phases, sequence and open decisions
- [`CLAUDE.md`](CLAUDE.md): working rules and workflows for contributors and Claude Code
- [`docs/adr/`](docs/adr/README.md): architecture decision records
- [`docs/prd/`](docs/prd/README.md): product requirements (always English), starting with [PRD-0001 Platform core](docs/prd/0001-platform-core.md)
- [`docs/reviews/`](docs/reviews/): review checklists for specifications, implementations and module registration
- [`docs/branch-protection.md`](docs/branch-protection.md): branch protection settings for `main` (pull request and CI gate)

## Workflow in short

PRD draft, independent multi-perspective review, implementation, tests (global coverage >= 80 %), independent multi-perspective review. Every change goes through a feature branch and a pull request; the CI check `gate` must be green before merge to `main`. Code and PRDs in English, UI strictly bilingual (German/English) via i18n.
