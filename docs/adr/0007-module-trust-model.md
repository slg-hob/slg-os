# 0007. Modules come from internal, reviewed teams only

- Status: Accepted
- Date: 2026-10-19

## Context
Modules run next to sensitive internal data and are consumed from separate GitHub repositories.

## Decision
Only modules from internal teams, reviewed by the platform team, are registered. Registration is an admin action; there is no self-service registration by departments.

## Consequences
- Isolation targets accidental faults (failing modules, resource hogging, schema collisions), not malicious code. Hard sandboxing is out of scope for now.
- Still required: manifest validation, per-module database credentials/schema, authenticated module-to-core and module-to-module calls, no direct DB access across modules, dependency scanning in module CI.
- The review checklist for module registration is part of `docs/reviews/`.
- Revisit this ADR before allowing modules from outside the platform team's review process.
