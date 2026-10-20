# 0016. Audience-bound tokens for modules

- Status: Proposed
- Date: 2026-10-20

## Context
The gateway calls module backends on behalf of the user. Each module must receive a token whose audience is that module only (PRD-0003 SK-19, Q26). The session holds the user's tokens (ADR-0015), so the gateway needs a mechanism to obtain a token per module. Two candidates were measured against production-mode Keycloak 26.8 with the confidential client `slg-core` (`TokenMechanismExperimentIT`, output `target/sk19-token-mechanism.json`, CI run of 2026-10-20).

| Mechanism | Result |
| --- | --- |
| Standard token exchange v2 with `audience=reference` only | 400 `Requested audience not available` |
| Token exchange v2 with `audience=reference` and `scope=reference` (client scope with an audience mapper) | 200, access token with `aud=reference`, `azp=slg-core`, 300 s, no refresh token |
| Refresh grant with `scope=reference`, original login without that scope | 200 but the token has no audience: the scope is silently ignored |
| Refresh grant with `scope=reference`, original login requested `openid reference` | 200, `aud=reference`, refresh token rotates |
| Reuse of the rotated refresh token | 400 `invalid_grant`, the token family is revoked |

## Decision
Use **standard token exchange v2** per module: the gateway exchanges the user's access token for a token with `audience=<module id>` and `scope=<module id>`, caches it until shortly before expiry and never stores a refresh token for it. The provisioner creates, per registered module, a client scope with an audience mapper and assigns it to `slg-core` as an optional scope.

## Consequences
- The user's refresh token is used only by the session refresher, so the single-flight rule of ADR-0015 is enough; no second consumer rotates it.
- Down-scoping would need every module scope in the original login and would put every module audience into the user token, or force a renewal chain per module that rotates the shared refresh token.
- The exchange requires token exchange to be enabled for the `slg-core` client and the module scope to exist before the first call; registering a module therefore includes an admin API call to Keycloak (module registry).
- The interface `ModuleTokenMechanism` hides the mechanism, so it can be replaced.

## Alternatives considered
- Refresh-token down-scoping: see above; rotation makes concurrent use unsafe.
- Forwarding the user's access token to modules: the audience would not be module specific.
