# 0002. Modules are separately deployed services embedded in the shell

- Status: Accepted
- Date: 2026-10-19

## Context
Modules are developed in their own GitHub repositories by different teams and must be added without changing the core.

## Decision
Each module is its own deployable unit (container(s) with a Spring backend and a React frontend bundle). A module registers with slg-os through a versioned **module manifest**. The shell embeds the module frontend (ADR-0005); a reverse proxy routes module API traffic. Modules own their database schema (ADR-0006 describes permissions, the data rules are in `CLAUDE.md`).

## Consequences
- A module can be released, rolled back and fail independently of the core.
- The module contract (manifest schema, auth, API conventions, health endpoints, UI integration) is the central, versioned artifact of the platform and needs its own PRD and SDK/template.
- More containers to run; mitigated by Docker Compose (ADR-0009).
- Cross-module access only through documented APIs/events, never by reading another module's tables.

## Alternatives considered
- Runtime-loaded JVM plugins: lean, but tight coupling, classloader complexity, and one faulty module can take down the platform.
- Build-time dependencies: simplest, but every new module forces a rebuild and redeploy of slg-os.
