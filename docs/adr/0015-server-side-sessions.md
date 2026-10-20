# 0015. Server-side sessions in PostgreSQL

- Status: Proposed
- Date: 2026-10-20

## Context
ADR-0012 makes the core a backend-for-frontend: the browser holds only a session cookie, tokens stay on the server. PRD-0003 (decision D4, D7) asks for a session store that survives a core restart, works with two core containers against one database, protects refresh tokens at rest, and closes Q28 (session store) and part of Q37 (cookie attribute). The implementation of milestone M2a ([PR #18](https://github.com/slg-hob/slg-os/pull/18)) and the end-to-end harness of M2b ([PR #19](https://github.com/slg-hob/slg-os/pull/19)) give the evidence.

## Decision
1. **Store: PostgreSQL**, table `session`, explicit SQL through `JdbcClient` (no JPA). No Redis and no in-memory store: sessions survive restarts, a second container needs no coordination, and the existing backup and role concept applies. The table is excluded from the backup; after a restore everyone signs in again.
2. **Identifiers.** The cookie carries 128 random bits; only the SHA-256 hash is stored. A database leak does not yield usable session cookies.
3. **Tokens at rest.** Refresh, access and ID token are encrypted with AES-256-GCM. The key id is stored with the row; the session id hash, the key id and the purpose are authenticated data, so ciphertexts cannot be moved between sessions or token types. Keys come from a Docker secret file (`id=base64` lines), rotation adds a key and switches the active id. Only the `session` package can reach the vault (ArchUnit rule). Data that cannot be decrypted ends the session with the audit reason `vault_unreadable`.
4. **Cookie.** `__Host-slg-session`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, no `Domain`. The callback answers 200 with a document that navigates on (`<meta http-equiv="refresh">`) instead of a 302, because a `SameSite=Strict` cookie is not sent on a redirect chain that started at another site. The end-to-end test `samesite-login.spec.ts` asserts in Chromium, Firefox and WebKit that the first navigation after the callback carries the cookie and that there is no second login bounce. The `SameSite=Lax` fallback of Q37 is therefore **not** needed (verified green in Chromium, Firefox and WebKit on CI, 2026-10-20).
5. **CSRF.** Per-session token derived as HMAC(server key, session id hash), constant-time comparison, plus the `Origin` check and `Sec-Fetch-Site`. The pre-login cookie (`SameSite=Lax`, 10 minutes) carries state, nonce, verifier and the return target, signed with a different HMAC domain.
6. **Groups (D7).** The group claim of the ID token (full paths) is kept in the session and refreshed with every token renewal. Permission lookups by user id use the latest live session; without one the answer is "no permissions" (Q35 for background jobs).
7. **Refresh.** A transaction-scoped advisory lock (`pg_try_advisory_xact_lock`) per session, not a row lock: exactly one grant per session across containers, requests that only touch or delete the session are never blocked while Keycloak is slow, waiters hold no database connection, and the number of renewals that wait for Keycloak is capped at half of the pool. A refused grant (`invalid_grant`) or a lost response ends the session (the rotating refresh token may be consumed); other refusals (`invalid_client`, server errors) keep it and count as Keycloak unavailable.
8. **Timeouts.** Idle 30 minutes and absolute 10 hours are enforced on the server with an injectable clock. Requests marked `X-Slg-Background` do not count; the UI sends `POST /core/api/v1/session/touch`.
9. **Canonical paths.** All access decisions use one canonical request path (decoded, parameters removed); the session filter is deny-by-default with an allowlist.

## Consequences
- Every request does one primary-key lookup (and a write at most every 30 seconds). At fewer than 100 users this is negligible; the lookup is the first thing to measure in the load test (SK-18 AC6).
- The logout response carries the ID token hint to browser JavaScript (JSON body for `fetch`), a token of a session that has just ended. This is an accepted, narrow exception to "tokens never reach JavaScript" of ADR-0012; the alternative is Keycloak's confirmation page.
- The pre-login cookie is signed, not encrypted; it contains the PKCE verifier, which is useful only together with the authorization code.
- Per-container state is limited to rate-limit counters.
- The 200 interstitial adds one document to every login; the page is static and cached by nothing.

## Alternatives considered
- Redis or an in-memory store: another stateful service to run, back up and secure; no gain at this scale.
- Stateless signed cookies with tokens inside: contradicts ADR-0012 (tokens in the browser).
- A row lock for the refresh: blocked `touch` and `logout` while Keycloak was slow and held connections (found in review).
- `SameSite=Lax` for the session cookie: weaker CSRF position; kept only as the documented fallback.
