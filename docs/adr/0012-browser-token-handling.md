# 0012. Browser token handling for federated remotes

- Status: Proposed, **owner decision required** (the spike evidence is stronger than assumed when Q11/Q12b were decided; independently reviewed 2026-10-19)
- Date: 2026-10-19

## Context
PRD-0001 Q11 chose in-memory tokens without a backend-for-frontend; Q12b accepted the residual risk that a compromised module build can read the access token. The hostile-remote fixture (WP3.6) tested what that risk is in practice.

## Evidence (Chromium, spike code)
- Blocked: off-origin `fetch`/XHR exfiltration (CSP `connect-src`), service worker registration (`worker-src 'none'`), access to cookies (none readable) and storage (no tokens stored; OIDC state, nonce and PKCE verifier are in `sessionStorage` during the redirect). Not blocked by `connect-src`: exfiltration by top-level navigation, link prefetch and WebRTC; the test used `fetch` only.
- Possible: same-origin exfiltration; reading other windows' DOM; wrapping `window.fetch` and capturing other modules' access tokens and the **refresh token** in refresh-grant request bodies; replaying a captured module token against that module's API.
- Walking the React tree found tokens because the shell kept the user object in state. Fixed by keeping only the profile in state.
- Locking `window.fetch` before remotes load stops the wrapper, but patching `URLSearchParams.prototype` still sees the refresh token. In-origin code cannot be fully isolated from JavaScript-visible tokens. Not tested: a module running its own `prompt=none` authorization-code flow in a hidden same-origin iframe (expected to obtain any permitted scope without touching the shell's tokens), XHR/`Headers`/`Response` patching, DOM injection into other windows, access to Keycloak pages in the same origin, tokens in the Federation shared scope.
- Down-scoping is not a trust boundary: the rotating refresh token must carry all module scopes, so code holding it or running its own silent flow can obtain any audience. The module-bound SDK context limits accidental use only; backend authorisation per request is the only enforcement.
- Keycloak (26.8.1 in `start-dev` mode with the legacy `token-exchange` feature flag) refused standard token exchange for the public client; this must be re-checked in production mode with the default token exchange v2. Per-module audiences work through refresh-token down-scoping. Refresh tokens rotate and are single use: two concurrent grants revoke the client session (the SSO session survives). Module token grants must be serialised.

## Options
1. Keep in-memory tokens (Q11 as decided). Locking `window.fetch` is not a mitigation. Exposure: a compromised module can steal a refresh token worth up to the absolute session lifetime (10 h in PRD-0001 8.1). Mitigations available now: shorter refresh/session lifetimes, no secrets in React state, locked `fetch`, import-map integrity, four-eyes release of module versions.
2. Backend-for-frontend with HttpOnly cookies: tokens never reach JavaScript; the core backend exchanges and forwards module tokens. Removes the exposure described above, costs a BFF component and CSRF handling.

## Recommendation
Prefer option 2 if modules from several teams will run in the shell origin, because ADR-0007 limits who may publish modules but not the supply-chain risk of their dependencies. Option 1 is acceptable for a first release only with the listed mitigations and shortened refresh/session lifetimes. The owner decides; if option 2 is chosen, it supersedes the token decisions in PRD-0001 8.1 and Q11.
