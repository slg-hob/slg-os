# 0010. Module Federation approach

- Status: Proposed (spike evidence in `docs/spikes/2026-shell-spike-report.md`; needs independent review and owner acceptance)
- Date: 2026-10-19

## Context
ADR-0005 chose Module Federation for module frontends. PRD-0002 asked whether it works with the fixed Vite stack under the strict CSP and integrity requirements of PRD-0001 (NFR-1, 8.3). Spike results are in the report; the evidence applies to Chromium only.

## Decision
- Use `@module-federation/vite` (1.23.5 verified) with Vite 8 and `@module-federation/runtime` (2.9.2) in the shell. Remotes are registered at runtime from registry data; the shell has no static remotes.
- Shared singletons provided by the shell: `react`, `react/`, `react-dom`, `react-dom/`, and the shell SDK. Bootstrap CSS is provided by the shell only.
- **Compatibility is checked at registration** by comparing the manifest's `requires` with the versions the shell provides (semver). Runtime `strictVersion` is not a control: with a mismatched remote it either fails closed (`RUNTIME-015`) or fails open (logs errors, renders with a second React copy) depending on load order and build.
- **Integrity**: at registration the core copies the remote build into an immutable, content-addressed store and records SHA-384 hashes of every JavaScript file. The shell document is generated per request with a CSP nonce and an inline import map whose `integrity` section lists all registered files. Verified in Chromium: tampered entry or chunk is refused and its code does not run; a changed file in the module container after registration is not served.
- **CSP baseline** that worked with zero violations in enforcing mode: `default-src 'none'; script-src 'self' 'nonce-<n>'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; frame-src 'self'; form-action 'self'; worker-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'self'`. `worker-src 'none'` is required (a module could otherwise register a service worker for its own path). `frame-ancestors 'self'` instead of `'none'` is required for the silent-renewal iframe. Keycloak is served same-origin under `/auth`.
- Remote template requirements: an `index.html` entry (Vite needs one; it doubles as a standalone dev harness), `dts: false` (otherwise the DTS worker crashes the dev server without a `tsconfig.json`), shared entries including the `react/` and `react-dom/` prefixes, `base` set to `/modules/<id>/remote/`.
- Proxy rules verified: response headers `Set-Cookie`, `Content-Security-Policy`, CORS and `Service-Worker-Allowed` from module containers are stripped; `X-Forwarded-*` and identity headers from clients are overwritten or dropped; `nosniff` is set.

## Consequences
- The shell HTML must be produced by the core backend (nonce, import map from the registry), not by a static file server.
- Integrity relies on import-map `integrity`; Firefox and Safari behaviour is **not verified** and must be tested before go-live (Chromium only was available).
- CSS files are not covered by import-map integrity; stylesheets are same-origin and limited by `style-src 'self'`.
- A compromised but correctly registered build still runs with full access in the shell origin; see ADR-0012.

## Alternatives considered
- Static shell HTML with a hash-based CSP: rejected, the import map changes with every registration.
- Verifying only at registration without an import map: leaves tampering after registration undetected.
- Import maps/iframes as fallbacks (PRD-0002 F2/F3) were not needed.
