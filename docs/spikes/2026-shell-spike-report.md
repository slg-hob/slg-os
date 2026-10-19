# Spike report: Module Federation, window manager and token handling

- PRD: [PRD-0002](../prd/0002-shell-spike.md)
- Date: 2026-10-19 (executed by Claude Code, owner reviews; decisions S1 to S7 in PRD-0002)
- Code: branch `spike/shell`, directory `spikes/shell/` (disposable, never merged). Citable code state: commit `b5b9baa` on branch `spike/shell` (later commits only touch the spike README). A local tag `spike-shell-v1` exists, but pushing it was refused by the environment's proxy policy (HTTP 403); PRD-0002 D6 wants the tag after ADR acceptance anyway.
- Status of this report: independently reviewed by four agents on 2026-10-19 (requirements and evidence, security, UI/UX and accessibility, operations); findings integrated (section 8); awaiting owner acceptance (S7). E4 and E5 are **partial**, not passed.

## 1. Summary and recommendation
**Conditional go.** The fixed stack (Vite, React, Bootstrap, Module Federation) works for the module contract in Chromium: independently built remotes load at runtime from registry data, same-origin, under an enforcing strict CSP without violations, with verified integrity checks and contained failures. A keyboard-operable, screen-reader-oriented window manager can be built in-house with low effort; a library adds little. Four findings change the PRD (section 6):

1. **Runtime version checks of Module Federation are not a control.** The compatibility check must happen at registration.
2. **Integrity needs a per-request shell document.** Import-map `integrity` (with a CSP nonce) is the only mechanism that covered entry and chunks; a static shell is not enough.
3. **Tokens in the browser are readable by any remote**, including the refresh token, even with a locked `fetch`. This is stronger evidence than assumed for Q11/Q12b; see ADR-0012 (owner decision required).
4. **Keycloak refuses token exchange for public clients**; per-module audiences work through refresh-token down-scoping, which needs serialised grants.

Not done (owner decisions S1, S4, S5): manual screen-reader pass, real Edge on an office PC, Firefox and Safari. These remain release gates. Exit criteria E4 (renewal matrix incomplete) and E5 (Q11 undecided, ADR-0012) are only partly met; see section 4.

## 2. Setup and deviations from PRD-0002
- The environment has no Docker daemon, so the Compose stack was not used. Everything ran natively: nginx 1.24 (apt) as the only published listener (`https://localhost:8443`, self-signed certificate; plain `http://localhost:8081` for service-worker tests), Node services for the shell document, the stub API and a hostile upstream, Keycloak 26.8.1 in `start-dev` mode (H2) from Maven Central, headless Chromium from Playwright.
- Firefox is not installed and may not be downloaded here; all browser results are Chromium (build `chromium-1194` of Playwright 1.56.1).
- Hardware is the container (4 vCPU, 15 GB). All performance numbers are indicative and not NFR-6 evidence (NFR-S7).
- Keycloak realm: test users and groups only (no AD). Passwords are generated per run and not committed.
- Time box: executed in one session, no day-4 checkpoint was needed because E1 passed on the first plugin.

**Limits on validity.** Keycloak ran in `start-dev` mode (H2, HTTP behind nginx with `--proxy-headers=xforwarded`, legacy `token-exchange` feature flag). The statement that token exchange is refused for public clients was observed in this configuration only and must be re-checked in production mode (`kc.sh start`, PostgreSQL) with the default standard token exchange v2 before ADR-0012 is accepted. nginx ran without HTTP/2, compression and HSTS, with a self-signed certificate, so transfer sizes (849 KB) are uncompressed. Service-worker tests ran on `http://localhost:8081`, a different origin from the 8443 shape. The nginx `alias` plus `current` symlink into `.run/store` is a spike construct; in the product the core serves the files. Third-party tools (nginx 1.24, Java 21, the Keycloak zip, Chromium build 1194) are installed outside the lockfiles; `spikes/shell/README.md` lists the prerequisites.

**Not done from PRD-0002:** shared design-system and i18n singletons (WP1.4; only React, React DOM and the SDK were shared), the "wrong manifest version" failure case (replaced by "wrong content"), detection of a remote that bundles its own Bootstrap (WP1.8), the `blob:` import test (WP1.5c), authenticated registry fetch (WP1.2), template effort measurement (WP1.9), forged-signature and wrong-issuer tests (WP3.4), the Report-Only CSP phase and Trusted Types, NFR-S4 supply-chain checks (`--ignore-scripts`, image scan, secret scan), Firefox, and per-browser tables for WP1.5a.

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
| 1.5 integrity | see 1.5a to d below; evidence is Chromium only | `wp1-integrity*` |
| 1.6 CSP | Enforcing policy (ADR-0010) with zero violations during sign-in, reload, new tab and loading of both remotes. Theme switch, language switch, module error and silent renewal were **not** CSP-tracked. A Report-Only phase and Trusted Types were not tried | `trackCsp` in `wp1-federation`, `wp1-integrity-federation`, `wp3-auth` |
| 1.7 failures | Four cases (unreachable, HTTP 500, wrong content, render exception) are contained to the window; other windows and the shell stay usable; Retry remounts | `WP1.7a-d` |
| 1.8 theme/language | `data-bs-theme` and language switch live without reload and without losing module state. Found and fixed: the first shell version remounted modules on language change | `WP1.8` |
| 1.9 DX | A remote builds in about 1 to 2 s. Its dev server serves `remoteEntry.js` under the base path. It needs `dts: false` and an `index.html` entry; without `dts: false` the DTS worker crashes the dev server (no `tsconfig.json`). HMR inside the shell was **not verified** | manual check |
| 1.10 timing (indicative) | see table below | `results/perf-*.json` on `spike/shell` |
| 1.11 proxy hardening | Header stripping was tested on a purpose-built `/modules/hostile/` proxy location, not on the production `alias` locations; `X-Forwarded-*` overwritten and four identity headers dropped (denylist, `/api/stub/` only); traversal variants return no secret content (the assertion only excludes two markers, it does not require a 404/400); wrong MIME not executed under `nosniff`; service worker registration blocked by `worker-src 'none'` | `wp1-proxy` |

**1.5a to d, integrity (Chromium only):**
- `<script type=module integrity>`: correct hash loads; wrong hash and tampered body are blocked.
- `<link rel=modulepreload integrity>` followed by `import()`: a wrong hash blocks the preload and the later `import()` also fails.
- Import-map `integrity`: enforced for dynamic `import()` and for chunks imported by a verified entry; a tampered chunk makes the import fail.
- Script integrity on the entry alone does **not** protect its chunks.
- An inline import map is blocked by `script-src 'self'`; it needs a nonce or hash, so the shell document must be generated per request (done in the spike with a Node shell server).
- Real remotes: with registry hashes in the import map, a tampered chunk and a tampered `remoteEntry.js` are refused and their code does not run. Content-addressed immutable copies made at registration mean that changing the module container's build afterwards does not change what is served.
- Not covered: CSS, JSON (i18n bundles), WASM and anything fetched at runtime; files that are not listed in the import map load without any check (integrity fails open for unlisted files); the shell's own bundle; browsers other than Chromium.

**WP1.10 (container hardware, headless Chromium, 100 Mbit/s and 5 ms latency, 20 runs after 3 warm-ups; median / p95 in ms):**
| Profile | Desktop cold | Desktop warm | Window open cold | Window open warm |
|---|---|---|---|---|
| LAN | 451 / 531 | 242 / 266 | 286 / 320 | 106 / 133 |
| LAN + 4x CPU | 1737 / 1957 | 1255 / 1409 | 514 / 577 | 295 / 359 |

Desktop = navigation until the signed-in desktop is visible (includes silent sign-in against the dev-mode Keycloak on the same host). Transfer for load plus one window open: 35 files, 849 KB encoded (uncompressed). Against NFR-6 (desktop <= 3 s, window open <= 1.5 s warm and 3 s cold) all values pass, on loopback with an uncompressed payload, headless Chromium, one worker.

Method: 3 discarded iterations and 20 measured iterations per profile, each in a fresh browser context; cold = browser cache cleared, warm = immediate reload in the same context (so not a clean warm start). Median is the upper middle value, p95 is nearest rank (second largest of 20) and not statistically robust. Throttling (CDP: 100 Mbit/s, 5 ms, optional 4x CPU) applies to the browser only, not to Keycloak or nginx. The warm desktop at 4x CPU is bimodal (about 0.8 s and 1.3 s); the cause was not investigated. The JSON files do not record the environment; it is described in section 2.

### WP2 Window manager
Rubric result is in ADR-0011. In short: in-house 14 of 14 tests; `react-rnd` 14 tests with 12 passed, 1 skipped (pointer snapping: the adapter was not written, about five lines) and 1 failed (leak check, heap growth 10.1 / 13.4 / 12.1 % in the logged run against the 10 % threshold; in-house 6.0 / 9.2 / 5.4 %; both are within measurement noise). The accessibility layer is shared by both engines, so the suite does not rank them on accessibility. WinBox is disqualified by maintenance (last release 2023-07). The rubric scores, weights and patch-effort estimates required by PRD-0002 were not recorded; the decision rests on the pass/fail results and the effort judgement in ADR-0011 and must be completed before acceptance.

Measured with the in-house engine (container hardware): drag with 5 live windows (rAF deltas in headless Chromium, logged but not asserted): p50 16.7 ms, p95 16.8 ms, which is one 60 Hz frame (the 16.7 ms limit is met only within frame quantisation), no long task over 50 ms. Leak check (asserted below 10 %): after 3 x 100 open/close cycles with forced GC, heap +6.0 / +9.2 / +5.4 %, DOM nodes and listeners flat; "not increasing per round" was not asserted.

| Test | Gate / WCAG | Result |
|---|---|---|
| WP2.1 | UI-1, UI-2 | pass; focus moves into a new window, returns to the opener on close, to the taskbar on minimize |
| WP2.2 | UI-3, UI-15, 2.1.1, 2.1.2 | pass (Chromium, Linux): move/resize/snap/maximize/switch/close via the window menu opened with Alt+Space; Escape cancels; Tab reaches the taskbar. Menu items were activated by mouse click in the test, so the menu's own keyboard pattern (arrows, Home/End, Tab) is **not verified**. WCAG 2.5.7: pointer alternatives exist for snap/maximize/restore only; free move/resize has no single-pointer alternative (open item). Keyboard and pointer snap are asserted in separate tests, not compared |
| WP2.3 | UI-17, UI-18, axe, 4.1.3 | pass, automated scope only: one `main`, window titles are `h1` and name the section, `aria-current` in the taskbar, minimized windows `display:none` and `inert`; no serious or critical axe violations (wcag2a/aa/22aa) in light and dark with 3 windows, one minimized (moderate/minor were logged only; 1 and 5 windows, open menu, open dialog and narrow mode were not scanned; no `ariaSnapshot`); one live-region DOM update per tested action (open, minimize, switch, close) in the active language (DOM mutations observed, not speech; snap and "restore of five windows = one summary" untested) |
| WP2.4 | UI-3, UI-7, 2.5.8 | pass; title bar stays reachable, minimum 480x320, controls at least 24x24 px |
| WP2.5 | UI-5, UI-6 | pass; restore of geometry, stack order and active window after reload, clamping on a smaller screen, corrupt/old/unknown-module/NaN storage ignored, second tab sees the layout, History API deep links, neutral message for unknown targets, back navigation |
| WP2.6 | UI-12, 1.4.10, 1.4.12 | pass; narrow mode shows one window, module state and focus survive the mode switch, desktop and narrow layouts stored separately, no horizontal scroll at 320x256, title bar survives the text-spacing override |
| WP2.8 | UI-8, UI-16 | pass; window-modal close guard traps Tab, other windows and taskbar are `inert`, Escape stays |
| WP2.9 | UI-22, UI-23 | pass; both themes, axe `color-contrast` clean, active outline present under forced colors |
| WP2.10 | UI-4, UI-8 | pass; singleton reopen focuses, multi-instance windows titled "Name (n)" |

Not covered by any test: WCAG 2.4.3 order (the active window is last in DOM order), focus retention when a window is raised by clicking or tabbing into it (the re-focus fix covers only the launcher, taskbar and shortcut paths), 2.4.11 for a covered focused element, forced-colors visibility of button focus rings, 200 %/400 % zoom, clipping of the window menu at the minimum window size, announcements of keyboard move/resize mode, Alt+Space on Windows/Edge (it opens the browser system menu there; the visible menu button is the primary path), `pointercancel` handling. Automation proves DOM, focus and attribute state in Chromium; it cannot prove spoken output, announcement order or usability, which remain gates under UI-20.

Not executed: manual screen-reader pass (NVDA + Firefox), choice among the three window role options by spoken output (the automated semantics use labelled sections with the active window as `main`), shell-level (page-modal) dialogs, iframe focus hand-off, reduced-motion behaviour (the prototype has no animations).

### WP3 Authentication thin slice
| WP | Result |
|---|---|
| 3.1 tokens in memory | No token in local/session storage or readable cookies; OIDC state/nonce use `sessionStorage` (library requirement). Authorization code + PKCE S256 |
| 3.2 reload, new tab, sign-out | Reload and new tab re-establish the session without interaction through a hidden iframe (`prompt=none`) and the Keycloak SSO cookie; open windows are restored; sign-out ends the session. Needs `frame-ancestors 'self'` |
| 3.3 renewal | Library renewal with the rotating refresh token starts 2 minutes before expiry; failure shows a non-modal banner and keeps window state (clock-faked tests) |
| 3.4 stub API | The stub validates signature, issuer and audience (`jose`). Tested: finance user 200 on module A, ops user 403, missing token 401, wrong audience 401. No forged-signature or wrong-issuer test |
| 3.5 audience | Standard token exchange for the public client is refused (`Public client is not allowed to exchange token`). Working alternative: the login requests the module scopes, the shell narrows its own token to `openid groups` immediately, and module tokens come from refresh grants with `scope=<module>`. Module windows get a context that can only request their own audience; wrong audience gives 401. **Down-scoping is not a security boundary against a hostile module**: the rotating refresh token must carry all module scopes, and any code in the shell origin can also run its own silent authorization-code flow in a same-origin iframe and obtain any permitted scope. It limits accidental use only; backend authorisation per request is the only enforcement |
| 3.6 hostile remote | see ADR-0012. Tested: fetch wrapping, `URLSearchParams` patching, same-origin and off-origin `fetch` exfiltration, cross-window DOM read, service worker. Not tested: own silent-auth iframe, navigation-based and WebRTC exfiltration (not governed by `connect-src`), DOM injection into the shell or other windows, XHR/`Headers`/`Response` patching, `postMessage`, access to Keycloak pages in the same origin, tokens in the Federation shared scope (`__FEDERATION__` is reachable). The React-tree exposure was found and fixed during the spike; the test asserts the fixed state |
| 3.7 renewal matrix | Executed: reload, new tab, renewal, failed renewal, two concurrent refreshes (observed: one 200, the other and every later use of the rotated token 400; the revocation of the client session and the continued validity of the SSO session were observed manually, not asserted). **Not executed**: Keycloak on a sibling subdomain, lost response after rotation, SSO idle expiry, back-channel logout |

Findings from the realm import: importing a realm file that defines `clientScopes` replaces the built-in scopes (`profile`, `email`, `roles`, `web-origins`); requests for them fail with `invalid_scope`. The realm template must define what it needs or keep the built-ins.

## 4. Exit criteria (PRD-0002 section 5, as amended by decisions S1, S4, S5)
| | Status | Remark |
|---|---|---|
| E1 Federation | Pass (Chromium) | CSP exceptions needed beyond the PRD draft: none for scripts; `worker-src 'none'` and `frame-ancestors 'self'` added; inline import map needs a nonce |
| E2 Isolation | Pass | |
| E3 Windows | Pass for the automatable part, with gaps | Frame times are logged, not asserted (p95 16.8 ms = one frame at 60 Hz); manual screen-reader pass and Edge measurements deferred; rubric scores not recorded; ADR-0011 "accepted pending" |
| E4 Auth | **Partial** | WP3.1 to 3.4 pass. The WP3.7 matrix is incomplete (sibling-subdomain Keycloak, lost response after rotation, SSO idle expiry, back-channel logout, SameSite/third-party-cookie records not executed); E4 is not met until these run |
| E5 Closure | **Partial** | Q4 (in-house), Q5 (plugin viable in Chromium) and Q19 (History API) are decided in proposed ADRs. Q11 is not decided: ADR-0012 needs an owner decision |
| E6 Integrity | Pass in Chromium | import-map integrity + nonce + immutable store; other browsers unverified |
| E7 Trust boundary | Documented | The tested hostile-remote actions have recorded results (untested actions listed under WP3.6). E7 does not show that tokens are protected; it shows that they are not. Audience obtained via down-scoping, which is not a boundary |

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
| UI-15, UI-16, UI-20, Q21 | 2.2, 2.8 | partial; menu keyboard pattern, shell-level dialogs, iframe focus hand-off and screen-reader pass not executed |
| WP1.4 design-system and i18n singletons (UI-21, UI-24) | none | not tested |
| FR-7 template effort | 1.9 | not measured |

## 6. Changes proposed for PRD-0001 (D5; to go through the normal review workflow)
1. **8.1/Q11/Q12b**: record the hostile-remote evidence; owner decides ADR-0012; per-module audience through refresh-token down-scoping (token exchange unsupported for public clients); serialised grants; narrowing of the shell token; `refreshTokenAllowedScope`.
2. **NFR-1 CSP**: replace the draft policy with the one in ADR-0010 (`worker-src 'none'`, `frame-ancestors 'self'`, nonce).
3. **8.3/FR-4**: integrity via import-map `integrity` and immutable content-addressed store; hashes computed by the core at registration, not supplied by the module; Chromium-only evidence, other browsers a release gate.
4. **FR-4/FR-7**: registration-time semver check of `requires`; remote template requirements (`index.html`, `dts: false`, shared entries incl. prefixes, base path).
5. **Section 10/7**: the core serves the shell HTML with a per-request nonce and an import map from the registry.
6. **UI-3/UI-17**: raising a window on focus; own stacking context per window; note on DOM reordering and focus.
7. **UI-21**: the design system defines AA-compliant button tokens (Bootstrap `btn-outline-secondary` fails contrast on tertiary backgrounds); launcher must wrap at 320 px.
8. **Keycloak realm**: define built-in scopes explicitly when importing; brute-force, token lifetimes and rotation settings as used in `keycloak/realm.template.json`.
9. **Security wording**: replace the existing 8.3 sentence "the manifest records a SHA-384 hash of the remote entry" (hashes of all files, computed by the core); "the SDK exposes only `getAccessToken()`" is not a control; Q12b mentions only the access token although the refresh token is exposed too. Keycloak is same-origin under `/auth`: admin and master-realm paths must not be reachable through the public listener (consider a separate origin). Production realm: no HTTP redirect URIs, explicit refresh-token and session lifetimes, tuned brute-force parameters, no `start-dev`. `frame-ancestors 'self'` is wider than the silent-renewal iframe needs; prefer a dedicated renewal page or refresh-only renewal so the shell can use `'none'`; add `X-Frame-Options`, `Referrer-Policy` and `Permissions-Policy` on all locations. Registration must reject symlinks and hard links, hash all files, re-verify an existing store directory, make the store read-only and switch `current` atomically; entry-path validation must run server-side. The shell must never render module-supplied HTML (nonced inline script).
10. **UI contract**: `Alt+Space` must be verified in Edge on Windows (the visible menu button is the primary path); free move/resize needs a single-pointer alternative (2.5.7); the dialog should make only the owning window's content inert and return focus to the invoker; default language per UI-24 (browser language, then `de`), no hard-coded strings in the shell (brand, loading placeholder, raw error text, page title).
11. **Section 9/10 template and contract test**: the spike suite is a seed only after it is parametrised by base URL and module id and its hostile-remote tests are marked as characterisation tests. Lessons for the module template: stable callback references in SDK-provided props (an unstable `registerGuard` caused a render storm), `dts: false` plus `index.html`, a Keycloak import smoke test for the requested scopes, no dependence on `current` symlinks in serving, cache headers (`immutable` for content-addressed files, `no-store` for the registry), and re-checking timings and security behaviour against production-mode Keycloak and the real proxy.
12. **Gates before go-live**: manual NVDA + Firefox pass, Firefox/Safari checks of import-map integrity and the CSP, Edge measurements on an office PC, rest of the WP3.7 matrix.

## 7. Residual risks and open items
- Any registered module still runs with full access in the shell origin (ADR-0012).
- Import-map integrity and nonce handling are verified in one browser engine only.
- Raising a window reorders DOM nodes. Focus is restored only where the shell initiates the change (launcher, taskbar, shortcuts); focusing into a background window by pointer or Tab can blur the focused element and reload iframe content. Untested; fix before ADR-0011 is final (alternatives: `Element.moveBefore()`, or stacking by `z-index` with a documented tab-order exception).
- HMR of a remote inside the shell was not verified; it is the most relevant developer-experience unknown for the module template.
- Spike switches and constructs that must not be copied into production: `?debug`, `?nolock`, `?skipRequires`, the `/modules/hostile` route, plain-http `:8081`, `X-Forwarded-Host $host:8443`, the self-signed certificate, Keycloak `start-dev`, the client-side entry validation as a control, and the `window.fetch` lock (not a mitigation).
- Exfiltration by top-level navigation, link prefetch and WebRTC is not blocked by `connect-src`; the report's "off-origin exfiltration blocked" applies to `fetch`/XHR only.
- Concurrent library renewal and module token grants can race (refresh rotation); the spike serialised only module grants.
- The environment's proxy refused tag and remote-branch deletion operations; irrelevant to the findings.

## 8. Review log
Four independent review agents (fresh context) reviewed the report and ADRs 0010 to 0012 on 2026-10-19 from the perspectives requirements and evidence accuracy (16 findings, 5 high), security (14, 5 high), UI/UX and accessibility (14, 5 high) and operations and reproducibility (8, 2 high). The author integrated them. Main corrections: E4 and E5 changed from pass to partial; heap numbers and test counts aligned with the logs (react-rnd: 14 tests, 12 passed, 1 skipped, 1 failed; heap +10.1 to +13.4 %); down-scoping and exfiltration claims narrowed; 2.5.7 mapping corrected; limits on validity and the list of PRD-0002 items not done added.

Open after review: complete the rubric scores and patch-effort estimates (ADR-0011), re-check the Keycloak token-exchange result in production mode, run the missing renewal-matrix cases, and fix or test the focus and menu gaps listed in section 3. These are not blockers for the owner's decision on ADR-0012.
