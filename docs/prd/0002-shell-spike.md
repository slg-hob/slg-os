# PRD-0002: Technical spike: Module Federation and window manager

- Status: Reviewed (four independent reviews integrated, awaiting owner approval)
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0003, 0005, 0007
- Related PRDs: PRD-0001 (Q4, Q5, Q11, Q19; UI-1 to UI-29, FR-8, NFR-1, NFR-6, 8.1, 8.3)

## 1. Problem and goals
PRD-0001 rests on two technical bets that are not yet proven: (a) independently built and deployed React modules can be loaded into a Vite-based shell at runtime via Module Federation (ADR-0005), and (b) a window-based desktop with keyboard-operable, screen-reader-friendly windows (ADR-0003, UI-1 to UI-20) can be built on a library or in-house. A wrong bet would invalidate the module contract. This spike answers both questions with evidence before the project skeleton and the contract are written.

Goals:
- G1: Decide whether Module Federation with Vite meets the requirements of PRD-0001, or select a fallback (Q5).
- G2: Decide between a window-manager library and an in-house window manager (Q4).
- G3: Verify that tokens held in memory (Q11) work across shell, remotes, reloads and tabs without a backend-for-frontend, including the per-module audience requirement of PRD-0001 8.1.
- G4: Establish what the integrity and CSP controls of PRD-0001 8.3 and NFR-1 can technically enforce, and what remains residual risk.
- G5: Produce the facts needed for the module contract: shared singleton rules, remote loading from registry data, failure behaviour.
- G6: Record the outcome in ADRs and a spike report; amend PRD-0001 where findings require it.

## 2. Non-goals
- Production code. All spike code is disposable.
- The backend core, module registry service, permission model, admin UI, audit log, database.
- Selecting JDK/Spring Boot versions (Q2/Q3, done when the skeleton is created).
- Final visual design of the desktop.
- Defending against a malicious reviewed author (ADR-0007). SRI and CSP are evaluated for what they do mitigate: a tampered container or file, a compromised dependency, XSS inside a remote.

## 3. Users and roles
- Platform team (spike executors and consumers of the result).
- Future module developers (the contract derived from the findings affects them).

## 4. Work packages and questions
Every result is reproducible by a documented script or automated test, not by screenshots alone. Facts about libraries (version, maintenance, licence, accessibility claims) are unverified until checked; the report records the verification date and source (npm, GitHub release page) for each.

### WP1: Module Federation with Vite (Q5)
Build a host shell and three remotes with separate build roots, separate lockfiles and separate Docker builds, one reverse proxy origin: two compliant remotes and one deliberately mismatched remote (older React minor, to test singleton mismatch).
- WP1.1 Plugin candidates: `@module-federation/vite` (version, maintenance state and support for the current Vite major, which may be Rolldown-based, verified at spike start) and the fallbacks in section 8. Record versions used.
- WP1.2 Load remotes at runtime from registry-style data (a JSON list of manifests), not from build-time configuration (FR-4). The shell rejects entry URLs that are not relative paths under `/modules/<id>/remote` (no absolute URLs, no `..`, no encoded traversal); the registry fetch is authenticated.
- WP1.3 Remotes are served same-origin under `/modules/<id>/remote` through the reverse proxy (UI-5, 8.3).
- WP1.4 Shared singletons: React, React DOM, the design-system package, an i18n runtime and a small shell SDK are provided by the shell. Test the mismatched remote: is the mismatch detected at registration (manifest `requires`) or at load, and with what error?
- WP1.5 Integrity of remote code (PRD-0001 8.3). `import()` has no integrity option, so this WP determines what is technically enforceable:
  - WP1.5a Record per browser (Chromium, Firefox) whether integrity is enforced for: (i) `<script type=module integrity>`, (ii) `<link rel=modulepreload integrity>` followed by `import()`, (iii) import-map `integrity`, (iv) chunks imported by a verified entry. Tamper test: serve one modified byte and assert a load failure.
  - WP1.5b Evaluate content-addressed delivery: the registry stores the entry hash and a chunk-hash list; the proxy serves `/modules/<id>/<version>/<hash>/...` from a copy verified at registration (closes the gap between registration and load). Negative test: modify a chunk in the module container after registration; the shell must refuse it.
  - WP1.5c Fetch-and-verify followed by `import()` of a `blob:` URL is expected to conflict with `script-src 'self'` and relative chunk imports; verify, do not assume.
  - WP1.5d State plainly in the report what these controls do and do not cover.
- WP1.6 CSP: run the full policy in the production build, first Report-Only, then enforcing. Start from: `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self' <idp>; img-src 'self'; font-src 'self'; frame-src <idp>; form-action 'self' <idp>; base-uri 'none'; object-src 'none'; frame-ancestors 'none'` (the identity provider origin is a placeholder; PRD-0001 NFR-1 is updated if the findings require it). Test production builds only (dev builds use eval/inline). Optionally try `require-trusted-types-for 'script'`. List every violation and every added directive in the report (target: no `unsafe-eval`, no `unsafe-inline`). Assert via Playwright `securitypolicyviolation` events that none occur during load, theme switch, language change and remote error.
- WP1.7 Failure modes (FR-8): remote unreachable, remote returns 500, remote throws on render, remote loads with a wrong manifest version. The error boundary contains each failure to its window; shell and other windows stay usable.
- WP1.8 Theming and language: `data-bs-theme` switching and runtime language change propagate to remotes without reload (UI-22, UI-25). A remote that bundles its own Bootstrap CSS is detected as a violation.
- WP1.9 Developer experience: dev server and HMR for a remote against a running shell; effort to start a new module from a template (indicative, for FR-7).
- WP1.10 Production size and load times against NFR-6 (warm window open <= 1.5 s, cold <= 3 s), measured per NFR-S7.
- WP1.11 Proxy hardening: a remote response carrying CSP, `Set-Cookie`, CORS or `Service-Worker-Allowed` headers is stripped; `/modules/a/../b/`, `%2e%2e` and `//host` entry URLs are rejected; wrong MIME fails under `nosniff`; service-worker registration from a remote is blocked or detected; spoofed `X-Forwarded-Host/Proto` are ignored.

### WP2: Window manager (Q4)
Candidates are pre-screened on day 1 by licence, maintenance activity, bundle size and a quick reading of their keyboard and ARIA support; at most two are implemented fully, one of them in-house. Candidate pool: (1) in-house, using React Aria hooks only for focus, dialog and keyboard behaviour (React Aria is not a window manager); (2) `react-rnd` or `react-draggable` + `re-resizable` plus in-house window semantics; (3) WinBox (imperative DOM, expected to conflict with React portals, `inert` and federated rendering; verify); (4) a docking/floating library such as `dockview` or `rc-dock` if it supports free-floating windows; commercial components (e.g. KendoReact Window) are excluded, the pool is open source only (S2). Tiling layouts (e.g. golden-layout, flexlayout-react) are a different model and out of scope.

Rubric. Each criterion is scored 0 = fails, 1 = passes only with in-house patches (effort estimated in days), 2 = passes natively. Gates (any 0 disqualifies the candidate unless fixable with at most 3 documented person-days of patches): WP2.1, WP2.2 (including a keyboard alternative to dragging and no trap), WP2.3 automated part, WP2.4, WP2.5 (window state held in a shell-owned, serialisable, versioned store), WP2.6 (narrow mode with the drag layer unmounted), WP2.8, WP2.9, acceptable licence, maintenance (release or commit within 12 months, verified). Tie-break weights: accessibility and keyboard 40 %, state and persistence control 20 %, maintenance and licence 15 %, performance 10 %, bundle size and DX 10 %, visual fit 5 %. In-house wins if its estimated build effort for UI-1 to UI-8 is at most 2x the patch effort of the best library. Scores and evidence are recorded in the window-manager ADR.

Acceptance suite (derived from PRD-0001, automated with Playwright and axe unless stated):
- WP2.1 Z-order and single active window (UI-1); focus moves into a new window and returns correctly on close and minimize (UI-2), asserted via `document.activeElement`.
- WP2.2 Keyboard-only: open launcher, open window, move, resize, snap, maximize, restore, close, switch windows (UI-3, UI-15). No keyboard trap (UI-16, WCAG 2.1.2). Every pointer operation has a keyboard or single-click path producing the same layout, asserted by comparing bounding boxes (WCAG 2.5.7). Pointer tests use pointer events plus keyboard parity tests rather than flaky mouse drags.
- WP2.3 Screen reader semantics (UI-17, UI-18):
  - Automated: axe-core (tags wcag2a, wcag2aa, wcag22aa) in light and dark theme with 1, 3 and 5 windows, one minimized, one dialog open, and narrow mode, zero serious or critical violations; DOM order equals stack order and tab order (2.4.3); focused elements are not obscured (2.4.11); minimized windows and windows behind a window-modal dialog are `inert` and absent from the accessibility tree (`ariaSnapshot`); exactly one `main`; each window is named by its title, the title is its `h1`, and the active window is marked via `aria-current` in the taskbar.
  - Decide the window role by test and record what NVDA announces for each: (a) every window `role="dialog"` non-modal with `aria-labelledby`; (b) labelled `region`, the active one as `main`; (c) `role="group"`. Choose the option that keeps one `main`, announces the title once and does not force focus mode.
  - Live region (4.1.3): one `aria-live="polite"` region exists at load; open, close, minimize, switch and snap each produce exactly one message in the active language; restoring five windows produces at most one summary message.
  - Manual only (deferred, see S1/S4 and E3): spoken output and verbosity (NVDA + Firefox required; JAWS + Chrome and VoiceOver if deployed, S4), browse versus focus mode on entering a window, announcement duplication, forced-colors visual check. A fixed script (open launcher, open two windows, switch, minimize, close, resolve a dialog, change language) with a pass/fail table per step and recorded versions. A blocker is any case where a screen-reader user cannot open, reach, operate or leave a window, or cannot tell which window is active. The spike runs only in a container, so this pass is not part of the spike; it is carried into the skeleton phase as a release gate for the shell (UI-20) and the script is delivered with the report.
- WP2.4 Title bar remains reachable; clamping on viewport resize (UI-3); minimum window size 480x320 (UI-7); target size of title-bar buttons, resize handles (or keyboard equivalent) and taskbar buttons at least 24x24 CSS px (2.5.8).
- WP2.5 Persistence and restore of layout including stack order (UI-6), tested independently of the window manager: restore onto a smaller screen, corrupted or old schema, two tabs; deep-link reflection of the active window via History API (UI-5, Q19).
- WP2.6 Narrow-viewport single-window mode (UI-12): resizing 1280 -> 600 -> 1280 px with three windows keeps open modules, their internal state (form text) and focus; narrow and desktop layouts are stored separately; at 320x256 CSS px and 200 % zoom no horizontal page scroll and all functions reachable (1.4.10); text-spacing override loses no content in the title bar (1.4.12). This renderer is also the fallback of R4, so build it first.
- WP2.7 Performance per NFR-S7: 5 windows with live content, drag p95 frame time <= 16.7 ms and no long task > 50 ms; heap and DOM node/listener growth below 10 % after 100 open/close cycles in three rounds, not increasing per round.
- WP2.8 Modal dialogs are scoped to a window with other windows inert; Tab cannot escape a window-modal dialog; inert windows are not clickable; shell-level dialogs trap and return focus; iframe content keeps focus hand-off (UI-16, UI-21, Q21).
- WP2.9 Styling fit: windows are themable through the `--slg-*` tokens and Bootstrap 5.3 `data-bs-theme`; contrast (1.4.3, 1.4.11) in both themes via axe, `forced-colors` and `prefers-reduced-motion` honoured (UI-23).
- WP2.10 Instance modes and close guard (UI-4, UI-8): singleton reopen focuses the existing window, multi-instance windows are titled "Name (n)"; a registered close guard prompts on close and sign-out.

### WP3: Authentication thin slice (Q11)
A Keycloak container (test realm, no AD) and the shell as an OIDC public client (authorization code + PKCE S256) with a maintained client library (candidates such as `oidc-client-ts` or `keycloak-js`; check support for rotating refresh tokens held in memory).
- WP3.1 Tokens held in memory only; the SDK exposes a token accessor to remotes. OIDC state/nonce storage (for example sessionStorage) is recorded.
- WP3.2 Reload and new tab: how the session is re-established without user interaction while the Keycloak SSO session is valid; effect on open windows (UI-13, UI-6).
- WP3.3 Silent renewal with rotating refresh tokens in memory; failed renewal keeps window state (banner).
- WP3.4 A stub resource server validates a token (signature, issuer, audience) and returns 403 without a permission.
- WP3.5 Audience: PRD-0001 8.1 requires per-module audience, but a token accessor without an audience hands every remote one broad token. Evaluate Keycloak audience mappers and token exchange for a public client; if unsupported or too costly, raise a PRD-0001 amendment (D4).
- WP3.6 Hostile-remote demonstration. A labelled fixture remote `evil` (localhost only, no real exfiltration target) attempts: (a) obtain a token and POST it to an off-origin host (expect CSP block); (b) the same to a same-origin path (expect success; document as residual risk); (c) wrap `fetch` and capture shell requests; (d) search `globalThis.__FEDERATION__`, the shared scope and React fibers for tokens; (e) read `document.cookie`, local/session storage and other windows' DOM; (f) call another module's API with the token; (g) register a service worker. Each result is recorded as blocked or possible, with the control that stops it. Residual same-origin exposures are copied into PRD-0001 8.3.
- WP3.7 Renewal matrix: Keycloak same-origin (`/auth`) and on a sibling subdomain; reload, new tab, silent renew via iframe versus refresh rotation, two concurrent refreshes, response lost after rotation (reuse detection), SSO idle expiry, back-channel logout. Record cookie SameSite attributes and browser versions; third-party-cookie behaviour is verified, not assumed.

## 5. Deliverables and exit criteria
Deliverables:
- D1: Spike report `docs/spikes/2026-shell-spike-report.md` (English): setup, verified versions with date and source, results per work package with links to scripts and raw data, a traceability table (PRD-0001 requirement -> WP -> result), residual risks, recommendation. `docs/spikes/` is added to the documentation map in `CLAUDE.md` when the report is merged.
- D2: ADR "Module Federation approach" (plugin and version, or the chosen fallback), including the shared-singleton rules and the integrity mechanism for the contract.
- D3: ADR "Window manager" with the rubric scores and evidence.
- D4: ADR or PRD-0001 amendment if WP3 contradicts Q11 or the audience requirement.
- D5: A list of PRD-0001 changes (requirement text, manifest fields, NFRs), applied through the normal review workflow.
- D6: Spike code lives in `spikes/shell/` on branch `spike/shell`, which is never merged into `main` in this form. `spikes/` is part of no Maven module, npm workspace, coverage gate, image build or release pipeline; CI path filters ignore it and CI fails if production code imports from it. Only the report, ADRs and PRD changes are merged. After acceptance of the ADRs the final state is tagged `spike-shell-v1` and the branch is retired. Automated tests may seed the production acceptance suite after normal review.

Exit criteria (all required for "go"; each is pass/fail and links its evidence):
- E1 Federation: the two compliant remotes and the mismatched remote load at runtime from registry data, same-origin under `/modules/<id>/remote`, under the CSP of WP1.6 with a documented exception list (target: empty). The mismatch is detected at registration or load with a clear error (WP1.2 to 1.6, 1.8, 1.11).
- E2 Isolation: all four failure cases of WP1.7 are contained in their window (automated test green).
- E3 Windows: the selected approach passes all rubric gates that are automatable; WP2.7 meets its thresholds and WP1.10 is reported as "indicative" on container hardware (NFR-S7). The manual screen-reader pass (WP2.3) and the Edge measurements on an office PC are not part of this spike (S1, S4, S5); they remain open release gates listed in the report, and the window-manager ADR is marked "accepted pending manual screen-reader verification".
- E4 Auth: WP3.1 to 3.4 pass (tokens in memory only; reload and new tab re-establish the session without interaction while the SSO session is valid; failed renewal keeps window state; stub returns 403) and the WP3.7 matrix shows no unhandled case.
- E5 Closure: the report decides Q4, Q5, Q11 and Q19, includes the traceability table, residual risks and open questions.
- E6 Integrity: the SRI result per WP1.5a is documented and one chosen mechanism passes the tamper test of WP1.5a/b, or a no-go for the integrity part of PRD-0001 NFR-1/8.3 is declared with a proposed replacement.
- E7 Trust boundary: WP3.6 shows every hostile-remote action with a result; WP3.5 shows how a per-module audience is obtained or documents why not.

No-go handling: a failed criterion triggers the matching fallback in section 8, one switch per question. The fallback re-runs the failed criterion and its dependencies. A partial go (for example E1 passes only with a fallback) is reported as such. If the fallback also fails, the spike ends with a "no-go" report and an owner decision (a new ADR superseding ADR-0005 or ADR-0003, or a scope change). The owner signs off the recommendation. Extension of the time box requires an owner decision recorded in the report.

## 6. Non-functional requirements
- NFR-S1 Time box and staffing: the spike is executed by Claude Code with the owner reviewing (S1); the owner's effort is decisions and review, not implementation. The time box stays 10 working days in two phases as an upper bound (the reviews estimated 15 to 20 person-days of human work). Phase A (days 1 to 4): compose baseline, WP1, WP3 thin slice (WP3.1 to 3.3), window candidate pre-screen. Checkpoint at day 4: E1 passes with one plugin and the candidate list is reduced to two; otherwise decide continue, narrow, or fallback. Phase B (days 5 to 10): WP2 suite, WP3.4 to 3.7, WP1.10/1.11, ADRs, report. The manual screen-reader pass and office-PC measurements are excluded (see E3). If the owner's review capacity or the time box is exceeded, the scope is cut to WP1 (E1, E2), WP2 with two candidates and WP3.1 to 3.3, and the report lists the postponed work packages.
- NFR-S2 Reproducibility: every result is reproducible from the repository with documented commands. Node, package manager, Vite, React, plugin, Playwright, Keycloak and proxy versions and image digests are recorded in the report with verification date and source. Lockfiles per build root, `npm ci` (or frozen-lockfile equivalent) only.
- NFR-S3 Quality exception: the 80 % coverage gate and the full implementation workflow of `CLAUDE.md` do not apply to disposable code under `spikes/`; the exception is approved by the owner (S6, recorded in `CLAUDE.md`) and ends with the spike. The acceptance suite (WP2) and the failure and security tests (WP1.5 to 1.7, WP1.11, WP3.6) are automated so the evidence is repeatable.
- NFR-S4 Security hygiene: pinned lockfiles with integrity hashes, `npm ci --ignore-scripts`, dependency audit and container image scan with zero unaccepted high findings, base images by digest. The test realm uses randomly generated passwords, exact redirect URIs (no wildcards), ports bound to 127.0.0.1, and the export is tagged `SPIKE-ONLY`; a secret scan runs in CI. No real credentials, no real AD data.
- NFR-S5 i18n: all user-visible strings of the shell prototype use the i18n mechanism in German and English because UI-24 to UI-26 are part of what is verified.
- NFR-S6 Browsers: latest Chromium and Firefox for automated tests; no manual screen-reader checks in the spike (no combination deployed or required so far, S4; NVDA + Firefox remains the minimum for the later release gate). Safari is not covered and is recorded as residual risk.
- NFR-S7 Measurement protocol: the reference environment is an office PC with Edge (Chromium), exact data not available (S5). The spike runs in a container with Playwright Chromium and Firefox, so all results are labelled "indicative, not NFR-6 evidence" and are repeated on a real office PC with Edge in the skeleton phase. Chromium and Firefox pinned by Playwright version, fresh profile per run. Window open measured with Playwright tracing and `performance.mark/measure`, cold (empty cache) and warm (primed cache), network throttled to a documented LAN profile (for example 100 Mbit/s, 5 ms) and a second run with 4x CPU slowdown. At least 20 runs per scenario after 3 warm-up runs; report median and p95 (nearest rank); raw data committed as CSV/JSON. Frame rate from `requestAnimationFrame` deltas or Chrome tracing. Leak check with forced GC (`--js-flags=--expose-gc`, CDP `HeapProfiler.collectGarbage`), three rounds. Lighthouse is used only for transfer size. Every number links to the script and raw data.

## 7. Testing strategy
- Playwright end-to-end for WP1 (runtime loading, tamper and failure cases, CSP violations, proxy hardening), WP2 (keyboard flows, focus assertions, ARIA snapshots, axe checks) and WP3 (reload, renewal matrix, hostile remote). Vitest for window-state and layout-persistence units.
- The Playwright suite runs headless in a Linux container for functional checks; performance numbers come from the reference machine or are labelled indicative (headless Linux and office Windows PCs differ).
- The suite runs nightly on the spike branch only (no merge gate). A Testcontainers setup is not needed because Docker Compose is the system under test.
- Re-validation: the suite is kept as the seed of the Module Federation contract test of PRD-0001 section 9 and is rerun in the skeleton CI on every Vite, React, plugin or design-system upgrade, before the contract's pinned React major or plugin version changes.
- Manual screen-reader pass logged in the report with a fixed script and recorded versions.

## 8. Risks and fallback options
- R1 The Vite Module Federation plugin is immature or incompatible with the current Vite major. Rspack is not a fallback: the owner keeps Vite fixed (S3), so there is no F1. F2: native ES modules with import maps and dynamic `import()` for remotes, with shared libraries provided through the import map (import-map `integrity` support per WP1.5a). F3: iframes as the default embedding mode (reverses ADR-0005; needs an SSO, theming and messaging bridge).
- R2 No window library satisfies keyboard and ARIA requirements: build in-house, limited to UI-1 to UI-8 first.
- R3 The strict CSP conflicts with the plugin loader: record the needed exceptions; do not relax NFR-1 without an ADR.
- R4 Floating-window screen-reader behaviour is poor: offer a "Simplified layout" (user setting) that renders one maximized active window using the UI-12 narrow-mode renderer, with the taskbar as a switcher (`tablist`/`navigation`), no drag/resize/snap, same deep links, persistence and live announcements. Trigger: two or more blockers in WP2.3, or one blocker with no workaround after 2 days. Further escalation (tab-based presentation) changes ADR-0003.
- R5 Findings invalidate the in-memory token model: choose a backend-for-frontend (reverses Q11) and amend PRD-0001.
- R6 Integrity cannot be enforced at load (SRI unsupported for dynamic imports): the controls reduce to verification at registration plus content-addressed delivery (WP1.5b); PRD-0001 8.3 is amended accordingly.

## 9. Rollout and operations
The spike runs on developer machines and in a throwaway Docker Compose project `spikes/shell/compose.yaml` with services `proxy`, `keycloak`, `shell`, `remote-a`, `remote-b`, `remote-mismatch` and `registry` (static JSON manifests served by the proxy).
- Proxy: one reverse proxy (nginx, pinned by digest) is the only published port, HTTPS on `https://localhost:8443` with a locally generated self-signed certificate created by a script (never committed). Routes: `/` shell, `/modules/<id>/remote` remotes, `/auth` Keycloak, `/api/stub` the WP3.4 stub. Security headers and the CSP of WP1.6 are set here.
- Keycloak: image pinned by tag and digest, started with `--import-realm` from a committed realm file (test users and groups only, no AD).
- Images: all base images pinned by digest; Node version via `.nvmrc`/image tag.
- Start and stop: `make up`, `make down`, `make test` (runs the Playwright suite against the running stack).
- Outbound access is only needed for package registries and image pulls. No secrets, no production host, no data.

## 10. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| S1 | Decided 2026-10-19: Claude Code executes, the owner reviews. No manual tests or office-PC measurements in the spike. | Benjamin Honauer | closed |
| S2 | Decided 2026-10-19: open source only; commercial window components are excluded. | Benjamin Honauer | closed |
| S3 | Decided 2026-10-19: Rspack may not replace Vite; the Vite stack stays fixed. If the Vite plugin fails, only F2 (import maps) and F3 (iframes) remain, both requiring a superseding ADR. | Benjamin Honauer | closed |
| S4 | Decided 2026-10-19: no deployed screen-reader combination is known; NVDA + Firefox remains the minimum for the later manual release gate. | Benjamin Honauer | closed |
| S5 | Decided 2026-10-19: reference is a standard office PC with Edge (Chromium); exact specs unknown, so spike numbers are indicative and are repeated on a real PC later. | Benjamin Honauer | closed |
| S6 | Decided 2026-10-19: the owner approves the NFR-S3 exception to `CLAUDE.md` for code under `spikes/`; it is recorded in `CLAUDE.md`. | Benjamin Honauer | closed |
| S7 | Decided 2026-10-19: independent review agents review the ADRs and report, then the owner accepts. | Benjamin Honauer | closed |

## 11. Review log
Four independent review agents (fresh context, no authorship of this PRD) reviewed it on 2026-10-19: requirements and spike design (14 findings, 4 high), security (10, 4 high), UI/UX and accessibility (14, 4 high), operations and reproducibility (11, 3 high). The author integrated their proposals because concurrent edits to one file would conflict.

Conflicts and how they were resolved:
- Time box: reviews estimated 15 to 20 person-days; the PRD sets 10 working days with two executors and a defined cut for one executor, and leaves staffing to the owner (S1).
- Window-manager decision rule: one review proposed "a library wins only if it scores >= 20 % above in-house", another "in-house wins if its effort is more than 2x the best library's patch effort"; the effort-based rule was chosen because it compares cost directly.
- Spike code location: branch `spike/shell` and directory `spikes/shell/`, never merged, instead of leaving it open.
- Report location: `docs/spikes/2026-shell-spike-report.md` (avoids the PRD/ADR numbering collision).
- Library facts (plugin version and Vite compatibility, window libraries, import-map `integrity` support, Keycloak token exchange for public clients, third-party-cookie behaviour) were flagged as unverified by all reviewers and stay unverified until the spike checks them.

Owner decisions after the reviews (2026-10-19): S1, S2, S4, S5 and S7 decided (Claude Code executes, open-source components only, manual screen-reader tests and office-PC measurements deferred, agent review before owner acceptance); S6 approved (spike exception recorded in `CLAUDE.md`); S3 decided against Rspack, so fallback F1 was removed and only F2 and F3 remain.

Open for a follow-up review pass: none of the perspectives re-reviewed the integrated text or the decisions applied afterwards.
