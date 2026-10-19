# 0004. Monorepo layout and Maven as Java build tool

- Status: Accepted
- Date: 2026-10-19

## Context
The core consists of a Java backend, a React frontend (shell), Docker assets, and the module SDK/template.

## Decision
The core lives in a single repository (`slg-os`) containing backend, frontend, deployment assets and documentation. The backend is built with Maven (multi-module build). The coverage gate (>= 80 %) is enforced in the build.

## Consequences
- Atomic changes across backend, frontend and contract; one CI pipeline.
- SDK artifacts and the module template are published from this repo for use by module repos.
- Concrete directory layout, Java version, test frameworks and the coverage tooling (e.g. JaCoCo for Java, Vitest coverage for the frontend) are defined in PRD-0001 and then recorded in `CLAUDE.md`.

## Alternatives considered
- Separate backend/frontend repos: independent releases, but version matrix and coordination overhead.
- Gradle: more flexible, more scripting complexity.
