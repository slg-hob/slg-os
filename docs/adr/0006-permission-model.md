# 0006. Modules declare permissions, admins map AD groups to them

- Status: Accepted
- Date: 2026-10-19

## Context
Different departments need different access to different modules. AD admins should not be a bottleneck, and modules must not hard-code AD group names.

## Decision
- A module declares its permissions (and optionally suggested roles) in its manifest.
- slg-os stores the mapping `AD group -> role / permissions` in PostgreSQL and exposes an admin UI to maintain it.
- Effective permissions are computed from the user's AD groups (delivered in the Keycloak token) and the stored mapping. Default is deny.
- Every module backend enforces permissions on every request through the SDK; the frontend only uses permissions to hide or disable UI.

## Consequences
- Needs a bootstrap mechanism for the first platform administrator (e.g. a configured AD group), specified in PRD-0001.
- Permission names are part of the module contract and are versioned; removing or renaming one is a breaking change.
- Mapping changes take effect for new tokens; token lifetime and revocation behaviour must be specified.
- Changes to the mapping are security events (ADR-0008) and are audited.

## Alternatives considered
- Fixed AD group naming convention (`SLG_<Module>_<Role>`): simple, but makes AD admins the bottleneck.
- Combination of convention and UI: more moving parts for little gain at this scale.
