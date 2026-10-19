# 0012. Browser token handling for federated remotes

- Status: Accepted by the owner on 2026-10-19 (option 2, backend-for-frontend). Supersedes the token-handling decisions Q11 and Q12b of PRD-0001; the PRD text is amended in the follow-up PRD-0001 revision.
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

## Decision
Option 2: the core backend acts as a backend-for-frontend. Tokens never reach JavaScript.

- The shell signs in through the core (authorization code + PKCE between the core and Keycloak). The browser holds only an HttpOnly, Secure, SameSite session cookie issued by the core.
- API calls from the shell and from modules go to the core's gateway under the same origin; the core attaches the right per-module access token server-side (refresh-token down-scoping or, if production Keycloak supports it, token exchange) and forwards to the module backend. Refresh tokens and module access tokens stay on the server.
- CSRF protection is mandatory: SameSite cookies, a required custom request header, `Origin` checks.
- The silent-renewal iframe and `frame-ancestors 'self'` are no longer needed; the shell can use `frame-ancestors 'none'`.

## Consequences
- A compromised module can no longer steal a token or refresh token, so a stolen credential does not outlive the session. It can still make authenticated requests as the signed-in user from inside the shell origin while that user is signed in, and read other windows' DOM; backend authorisation per request, import-map integrity (ADR-0010), four-eyes release of module versions and dependency scanning remain necessary.
- New core component (BFF/gateway): session store, token vault, rotation and revocation, CSRF handling, request forwarding with correlation ids. It sits on the hot path of every module call; performance and failure behaviour need requirements in PRD-0001.
- Module SDK: modules receive identity and permissions from the core; the frontend SDK has no `getAccessToken()`. Module backends validate the audience-bound token forwarded by the gateway.
- Session lifetime, logout and revocation (back-channel logout) become server-side concerns and are simpler to enforce than with stateless tokens in the browser.
- The spike's down-scoping, serialised-grant and narrowing code is reference material for the server side, not for the browser.
- Open: production-mode Keycloak must confirm which of token exchange or down-scoping is available for a confidential BFF client (token exchange is expected to work for confidential clients).

## Original recommendation and context
Prefer option 2 if modules from several teams will run in the shell origin, because ADR-0007 limits who may publish modules but not the supply-chain risk of their dependencies. Option 1 is acceptable for a first release only with the listed mitigations and shortened refresh/session lifetimes. The owner decides; if option 2 is chosen, it supersedes the token decisions in PRD-0001 8.1 and Q11.
