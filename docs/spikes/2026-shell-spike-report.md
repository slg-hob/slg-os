# Spike report: Module Federation, window manager and token handling

- PRD: [PRD-0002](../prd/0002-shell-spike.md)
- Date: 2026-10-19 (executed by Claude Code, owner reviews; decisions S1 to S7 in PRD-0002)
- Code: branch `spike/shell`, directory `spikes/shell/` (disposable, never merged). The tag `spike-shell-v1` exists locally; pushing it was refused by the environment's proxy policy (HTTP 403), so the branch head `b5b9baa` is the citable state.
- Status of this report: draft for independent review and owner acceptance (S7).

## 1. Summary and recommendation
**Go**, with conditions. The fixed stack (Vite, React, Bootstrap, Module Federation) works for the module contract in Chromium: independently built remotes load at runtime from registry data, same-origin, under an enforcing strict CSP without violations, with verified integrity checks and contained failures. A keyboard-operable, screen-reader-oriented window manager can be built in-house with low effort; a library adds little. Four findings change the PRD (section 6):

1. **Runtime version checks of Module Federation are not a control.** The compatibility check must happen at registration.
2. **Integrity needs a per-request shell document.** Import-map `integrity` (with a CSP nonce) is the only mechanism that covered entry and chunks; a static shell is not enough.
3. **Tokens in the browser are readable by any remote**, including the refresh token, even with a locked `fetch`. This is stronger evidence than assumed for Q11/Q12b; see ADR-0012 (owner decision required).
4. **Keycloak refuses token exchange for public clients**; per-module audiences work through refresh-token down-scoping, which needs serialised grants.

Not done (owner decisions S1, S4, S5): manual screen-reader pass, real Edge on an office PC, Firefox and Safari. These remain release gates.

## 2. Setup and deviations from PRD-0002
- The environment has no Docker daemon, so the Compose stack was not used. Everything ran natively: nginx 1.24 (apt) as the only published listener (`https://localhost:8443`, self-signed certificate; plain `http://localhost:8081` for service-worker tests), Node services for the shell document, the stub API and a hostile upstream, Keycloak 26.8.1 in `start-dev` mode (H2) from Maven Central, headless Chromium from Playwright.
- Firefox is not installed and may not be downloaded here; all browser results are Chromium (build `chromium-1194` of Playwright 1.56.1).
- Hardware is the container (4 vCPU, 15 GB). All performance numbers are indicative and not NFR-6 evidence (NFR-S7).
- Keycloak realm: test users and groups only (no AD). Passwords are generated per run and not committed.
- Time box: executed in one session, no day-4 checkpoint was needed because E1 passed on the first plugin.

### Verified versions (2026-10-19; source: `npm view` and installed lockfiles)
| Component | Version |
|---|---|
| Node / npm | 22.22.0 / 10.9.4 |
| Vite | 8.3.4 (Rolldown based) |
| `@module-federation/vite` | 1.23.5 (peer range includes Vite 8) |
| `@module-federation/runtime` | 2.9.2 |
| React / React DOM | 19.3.1 (mismatch remote: 18.3.1) |
| `@vitejs/plugin-react` | 6.1.2 |
| Bootstrap | 5.3.8 |
| `oidc-client-ts` | 3.5.0 |
| `react-rnd` | 10.5.3 (MIT) |
| `semver` | 7.8.5 |
| Playwright / `@axe-core/playwright` | 1.56.1 / 4.14.0 |
| Keycloak | 26.8.1 (Java 21) |
| nginx | 1.24.0 |
| `jose` (stub) | 6.2.12 |

## 3. Results by work package
Test names refer to `spikes/shell/tests/specs/`. Final regression: 48 tests passed for the in-house engine (`results/suite-inhouse.log`).

### WP1 Module Federation
| WP | Result | Evidence |
|---|---|---|
| 1.1 plugin | `@module-federation/vite` 1.23.5 builds host and remotes with Vite 8.3.4 | all builds |
| 1.2 runtime registration | Remotes registered at runtime from `registry/modules.json`; entry URLs must be relative paths under `/modules/<id>/remote/`; absolute, `..`, encoded and `//` variants rejected | `wp1-federation`, `wp1-failures` |
| 1.3 same-origin | Served via the proxy under `/modules/<id>/remote/` | all specs |
| 1.4 singletons | React, React DOM and the SDK are shared singletons. Registration-time semver check of `requires.react` rejects the mismatched remote with a clear message. **Runtime `strictVersion` is unreliable**: first load of the mismatched remote fails closed (`RUNTIME-015`) in one build and renders with a second React copy in another; after another remote initialised the scope it fails open | `WP1.4a/b/c` |
| 1.5 integrity | see 1.5a to d below | `wp1-integrity*` |
| 1.6 CSP | Enforcing policy (ADR-0010) with zero violations during load, theme switch, language switch, module error, silent renewal | `trackCsp` assertions |
| 1.7 failures | Four cases (unreachable, HTTP 500, wrong content, render exception) are contained to the window; other windows and the shell stay usable; Retry remounts | `WP1.7a-d` |
| 1.8 theme/language | `data-bs-theme` and language switch live without reload and without losing module state. Found and fixed: the first shell version remounted modules on language change | `WP1.8` |
| 1.9 DX | A remote builds in about 1 to 2 s. Its dev server serves `remoteEntry.js` under the base path. It needs `dts: false` and an `index.html` entry; without `dts: false` the DTS worker crashes the dev server (no `tsconfig.json`). HMR inside the shell was **not verified** | manual check |
| 1.10 timing (indicative) | see table below | `results/perf-*.json` on `spike/shell` |
| 1.11 proxy hardening | Module container response headers (`Set-Cookie`, CSP, CORS, `Service-Worker-Allowed`) stripped; `X-Forwarded-*` and identity headers overwritten or dropped; traversal variants return no content; wrong MIME not executed under `nosniff`; service worker registration blocked | `wp1-proxy` |

**1.5a to d, integrity (Chromium only):**
- `<script type=module integrity>`: correct hash loads; wrong hash and tampered body are blocked.
- `<link rel=modulepreload integrity>` followed by `import()`: a wrong hash blocks the preload and the later `import()` also fails.
- Import-map `integrity`: enforced for dynamic `import()` and for chunks imported by a verified entry; a tampered chunk makes the import fail.
- Script integrity on the entry alone does **not** protect its chunks.
- An inline import map is blocked by `script-src 'self'`; it needs a nonce or hash, so the shell document must be generated per request (done in the spike with a Node shell server).
- Real remotes: with registry hashes in the import map, a tampered chunk and a tampered `remoteEntry.js` are refused and their code does not run. Content-addressed immutable copies made at registration mean that changing the module container's build afterwards does not change what is served.
- Not covered: CSS files, browsers other than Chromium.

**WP1.10 (container hardware, headless Chromium, 100 Mbit/s and 5 ms latency, 20 runs after 3 warm-ups; median / p95 in ms):**
| Profile | Desktop cold | Desktop warm | Window open cold | Window open warm |
|---|---|---|---|---|
| LAN | 451 / 531 | 242 / 266 | 286 / 320 | 106 / 133 |
| LAN + 4x CPU | 1737 / 1957 | 1255 / 1409 | 514 / 577 | 295 / 359 |

Desktop = navigation until the signed-in desktop is visible (includes silent sign-in). Transfer for load plus one window open: 35 files, 849 KB encoded. Against NFR-6 (desktop <= 3 s, window open <= 1.5 s warm and 3 s cold) all values pass even with CPU throttling, but this is indicative only.

### WP2 Window manager
Rubric result is in ADR-0011. In short: in-house 14 of 14 tests; `react-rnd` 12 passed, 1 skipped (pointer snapping not provided), 1 leak-check miss at 11.9 % heap growth (noise level; in-house 5 to 9 %). WinBox disqualified by maintenance (last release 2023-07).

Measured with the in-house engine (container hardware): drag with 5 live windows p50 16.7 ms, p95 16.8 ms, no long task over 50 ms; after 3 x 100 open/close cycles with forced GC: heap +5 % to +9 %, DOM nodes and listeners flat.

| Test | Gate / WCAG | Result |
|---|---|---|
| WP2.1 | UI-1, UI-2 | pass; focus moves into a new window, returns to the opener on close, to the taskbar on minimize |
| WP2.2 | UI-3, UI-15, 2.1.1, 2.1.2, 2.5.7 | pass; keyboard move/resize/snap/maximize/switch/close, Escape cancels, no keyboard trap, keyboard snap equals pointer snap |
| WP2.3 | UI-17, UI-18, axe, 4.1.3 | pass; one `main`, window titles are `h1` and name the section, `aria-current` in the taskbar, minimized windows `display:none` and `inert`, zero axe violations (wcag2a/aa/22aa) in light and dark with several windows, exactly one live-region message per action in the active language |
| WP2.4 | UI-3, UI-7, 2.5.8 | pass; title bar stays reachable, minimum 480x320, controls at least 24x24 px |
| WP2.5 | UI-5, UI-6 | pass; restore of geometry, stack order and active window after reload, clamping on a smaller screen, corrupt/old/unknown-module/NaN storage ignored, second tab sees the layout, History API deep links, neutral message for unknown targets, back navigation |
| WP2.6 | UI-12, 1.4.10, 1.4.12 | pass; narrow mode shows one window, module state and focus survive the mode switch, desktop and narrow layouts stored separately, no horizontal scroll at 320x256, title bar survives the text-spacing override |
| WP2.8 | UI-8, UI-16 | pass; window-modal close guard traps Tab, other windows and taskbar are `inert`, Escape stays |
| WP2.9 | UI-22, UI-23 | pass; both themes, axe `color-contrast` clean, active outline present under forced colors |
| WP2.10 | UI-4, UI-8 | pass; singleton reopen focuses, multi-instance windows titled "Name (n)" |

Not executed: manual screen-reader pass (NVDA + Firefox), choice among the three window role options by spoken output (the automated semantics use labelled sections with the active window as `main`), shell-level (page-modal) dialogs, iframe focus hand-off, reduced-motion behaviour (the prototype has no animations).

### WP3 Authentication thin slice
| WP | Result |
|---|---|
| 3.1 tokens in memory | No token in local/session storage or readable cookies; OIDC state/nonce use `sessionStorage` (library requirement). Authorization code + PKCE S256 |
| 3.2 reload, new tab, sign-out | Reload and new tab re-establish the session without interaction through a hidden iframe (`prompt=none`) and the Keycloak SSO cookie; open windows are restored; sign-out ends the session. Needs `frame-ancestors 'self'` |
| 3.3 renewal | Library renewal with the rotating refresh token starts 2 minutes before expiry; failure shows a non-modal banner and keeps window state (clock-faked tests) |
| 3.4 stub API | Signature, issuer and audience validated; finance user gets 200 on module A, ops user 403; missing token 401 |
| 3.5 audience | Standard token exchange for the public client is refused (`Public client is not allowed to exchange token`). Working alternative: the login requests the module scopes, the shell narrows its own token to `openid groups` immediately, and module tokens come from refresh grants with `scope=<module>`. Module windows get a context that can only request their own audience. Wrong audience gives 401 |
| 3.6 hostile remote | see ADR-0012 |
| 3.7 renewal matrix | Executed: reload, new tab, renewal, failed renewal, two concurrent refreshes (one succeeds, the other and every later use of the rotated token fail; the client session is revoked, the SSO session stays, so the next silent sign-in works). **Not executed**: Keycloak on a sibling subdomain, lost response after rotation, SSO idle expiry, back-channel logout |

Findings from the realm import: importing a realm file that defines `clientScopes` replaces the built-in scopes (`profile`, `email`, `roles`, `web-origins`); requests for them fail with `invalid_scope`. The realm template must define what it needs or keep the built-ins.

## 4. Exit criteria (PRD-0002 section 5, as amended by decisions S1, S4, S5)
| | Status | Remark |
|---|---|---|
| E1 Federation | Pass (Chromium) | CSP exceptions needed beyond the PRD draft: none for scripts; `worker-src 'none'` and `frame-ancestors 'self'` added; inline import map needs a nonce |
| E2 Isolation | Pass | |
| E3 Windows | Pass for the automatable part | Manual screen-reader pass and Edge measurements deferred; ADR-0011 "accepted pending" |
| E4 Auth | Pass for WP3.1 to 3.4; WP3.7 partly | not executed items listed above |
| E5 Closure | Pass | Q4: in-house, Q5: plugin viable in Chromium, Q11: see ADR-0012, Q19: History API works |
| E6 Integrity | Pass in Chromium | import-map integrity + nonce + immutable store; other browsers unverified |
| E7 Trust boundary | Pass | all hostile-remote actions have results; audience obtained via down-scoping |

## 5. Traceability
| PRD-0001 | WP | Result |
|---|---|---|
| FR-4, 8.3, NFR-1 (integrity, CSP) | 1.2, 1.5, 1.6, 1.11 | verified in Chromium |
| FR-8 | 1.7 | pass |
| UI-1 to UI-8 | 2.1, 2.2, 2.4, 2.8, 2.10 | pass |
| UI-5, Q19 | 2.5 | pass |
| UI-6 | 2.5 | pass (lazy load of hidden windows implemented) |
| UI-12 | 2.6 | pass |
| UI-13, 8.1, 8.2 | 3.2, 3.3, 3.7 | pass; matrix incomplete |
| UI-17 to UI-19 | 2.3 | automated pass; manual pending |
| UI-21 to UI-23 | 1.8, 2.9 | pass; AA button tokens needed |
| UI-24 to UI-26 | 1.8 | partly (runtime switch verified; CI key parity not built) |
| NFR-4 | 2.3, 2.9 | automated pass |
| NFR-6 | 1.10 | indicative pass |
| Q11, Q12b, 8.1 audience | 3.5, 3.6 | see ADR-0012 |

## 6. Changes proposed for PRD-0001 (D5; to go through the normal review workflow)
1. **8.1/Q11/Q12b**: record the hostile-remote evidence; owner decides ADR-0012; per-module audience through refresh-token down-scoping (token exchange unsupported for public clients); serialised grants; narrowing of the shell token; `refreshTokenAllowedScope`.
2. **NFR-1 CSP**: replace the draft policy with the one in ADR-0010 (`worker-src 'none'`, `frame-ancestors 'self'`, nonce).
3. **8.3/FR-4**: integrity via import-map `integrity` and immutable content-addressed store; hashes computed by the core at registration, not supplied by the module; Chromium-only evidence, other browsers a release gate.
4. **FR-4/FR-7**: registration-time semver check of `requires`; remote template requirements (`index.html`, `dts: false`, shared entries incl. prefixes, base path).
5. **Section 10/7**: the core serves the shell HTML with a per-request nonce and an import map from the registry.
6. **UI-3/UI-17**: raising a window on focus; own stacking context per window; note on DOM reordering and focus.
7. **UI-21**: the design system defines AA-compliant button tokens (Bootstrap `btn-outline-secondary` fails contrast on tertiary backgrounds); launcher must wrap at 320 px.
8. **Keycloak realm**: define built-in scopes explicitly when importing; brute-force, token lifetimes and rotation settings as used in `keycloak/realm.template.json`.
9. **Gates before go-live**: manual NVDA + Firefox pass, Firefox/Safari checks of import-map integrity and the CSP, Edge measurements on an office PC, rest of the WP3.7 matrix.

## 7. Residual risks and open items
- Any registered module still runs with full access in the shell origin (ADR-0012).
- Import-map integrity and nonce handling are verified in one browser engine only.
- The window manager's DOM reordering can drop focus when a focused element moves; handled by re-focusing, not by `moveBefore()`.
- HMR of a remote inside the shell and developer ergonomics beyond a successful dev server were not verified.
- Concurrent library renewal and module token grants can race (refresh rotation); the spike serialised only module grants.
- The environment's proxy refused tag and remote-branch deletion operations; irrelevant to the findings.

## 8. Review log
None yet. Required: independent agent review of this report and ADRs 0010 to 0012 (S7), then owner acceptance.
