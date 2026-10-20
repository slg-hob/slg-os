# Architecture Decision Records

One file per significant architecture decision, numbered sequentially (`NNNN-short-title.md`). ADRs are written in English. An accepted ADR is not edited; to change a decision, add a new ADR that supersedes it and set the old one's status to `Superseded by NNNN`.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-keycloak-as-identity-provider.md) | Keycloak as identity provider in front of Active Directory | Accepted |
| [0002](0002-modules-as-separate-services.md) | Modules are separately deployed services embedded in the shell | Accepted |
| [0003](0003-window-based-desktop-ui.md) | Window-based desktop UI | Accepted |
| [0004](0004-monorepo-and-maven.md) | Monorepo layout and Maven as Java build tool | Accepted |
| [0005](0005-module-federation-frontend.md) | Module Federation for module frontends | Accepted |
| [0006](0006-permission-model.md) | Modules declare permissions, admins map AD groups to them | Accepted |
| [0007](0007-module-trust-model.md) | Modules come from internal, reviewed teams only | Accepted |
| [0008](0008-audit-scope.md) | Core audit log covers security events only | Accepted |
| [0009](0009-deployment-scale.md) | Single-server Docker Compose deployment | Accepted |
| [0010](0010-module-federation-approach.md) | Module Federation approach | Proposed |
| [0011](0011-window-manager.md) | Window manager | Proposed |
| [0012](0012-browser-token-handling.md) | Browser token handling for federated remotes | Accepted |
| [0013](0013-toolchain.md) | Repository layout, toolchain and CI tooling | Proposed |

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives considered
```
