# PRD-0002: Technical spike: Module Federation and window manager

- Status: Draft (not yet reviewed)
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0003, 0005, 0007
- Related PRDs: PRD-0001 (Q4, Q5, Q11, Q19; UI-1 to UI-8, FR-8, 8.1, 8.3)

## 1. Problem and goals
PRD-0001 rests on two technical bets that are not yet proven: (a) independently built and deployed React modules can be loaded into a Vite-based shell at runtime via Module Federation (ADR-0005), and (b) a window-based desktop with keyboard-operable, screen-reader-friendly windows (ADR-0003, UI-1 to UI-20) can be built on a library or in-house. A wrong bet here would invalidate the module contract. This spike answers both questions with evidence before the project skeleton and the contract are written.

Goals:
- G1: Decide whether Module Federation with Vite meets the requirements of PRD-0001, or select a fallback (Q5).
- G2: Decide between a window-manager library and an in-house window manager (Q4).
- G3: Verify that tokens held in memory (Q11) work across shell, remotes and page reloads without a backend-for-frontend.
- G4: Produce the facts needed for the module contract: shared singleton rules, remote loading from the registry, failure behaviour.
- G5: Record the outcome in ADRs and a spike report; amend PRD-0001 where findings require it.

## 2. Non-goals
- Production code. All spike code is disposable and is not reused without a normal PRD/review/test cycle.
- The backend core, module registry, permission model, admin UI, audit log, database.
- Selecting JDK/Spring Boot versions (Q2/Q3; done when the skeleton is created).
- Final visual design of the desktop.

## 3. Users and roles
- Platform team (spike executors and consumers of the result).
- Future module developers (the contract derived from the findings affects them).

## 4. Work packages and questions
All findings are reproducible by a documented script or test, not by screenshots alone.

### WP1: Module Federation with Vite (Q5)
Build a host shell and two remote modules (separate repos or separate build roots, separate containers, one reverse proxy origin).
- WP1.1 Plugin candidates: `@module-federation/vite` (current version and maintenance state to be verified on npm and GitHub at spike start), and fallbacks listed in section 8. Record versions used.
- WP1.2 Load remotes at runtime from registry-style data (a JSON list of manifests), not from build-time configuration, so that adding a module needs no shell rebuild (PRD-0001 FR-4).
- WP1.3 Remotes are served same-origin under `/modules/<id>/remote` through the reverse proxy (PRD-0001 UI-5, 8.3).
- WP1.4 Shared singletons: React, React DOM, the design-system package, an i18n runtime and a small shell SDK are provided by the shell. Test version-range mismatches (remote built with a different React minor/major): what happens, how detectable is it at registration (manifest `requires`)?
- WP1.5 Integrity: verify the SHA-384 hash of `remoteEntry` and decide how the shell enforces it (SRI on a script/module load, or a fetch-and-verify step) and what the plugin allows.
- WP1.6 CSP: run under `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'` without `unsafe-eval`/`unsafe-inline` (NFR-1). Record every CSP exception the plugin or Vite output needs.
- WP1.7 Failure modes (FR-8): remote unreachable, remote returns 500, remote throws on render, remote loads a wrong manifest version. The error boundary must contain each failure to the window; the shell and other windows stay usable.
- WP1.8 Theming and language: `data-bs-theme` switching and runtime language change propagate to remotes without reload (UI-22, UI-25). Remotes that bundle their own Bootstrap CSS are detected as a violation.
- WP1.9 Developer experience: dev server and HMR for a remote against a running shell; effort to start a new module from a template (indicative, for FR-7).
- WP1.10 Production build size and load times against NFR-6 budgets (warm window open <= 1.5 s, cold <= 3 s on LAN).

### WP2: Window manager (Q4)
Evaluate candidates against the same executable acceptance suite. Candidates: (1) in-house on top of focused primitives (e.g. React Aria hooks plus a drag/resize layer), (2) `react-rnd` plus in-house window semantics, (3) a framework-independent window library such as WinBox wrapped in React, (4) a commercial component (e.g. KendoReact Window) only if its license is acceptable and it keeps within the Bootstrap-based design system. Each candidate's licence, maintenance activity and bundle size are recorded; claims about accessibility are verified by test, not taken from documentation.
Acceptance suite (derived from PRD-0001):
- WP2.1 Z-order and single active window (UI-1); focus moves into a new window and returns correctly on close/minimize (UI-2).
- WP2.2 Keyboard-only: open launcher, open window, move, resize, snap, maximize, restore, close, switch windows (UI-3, UI-15). No keyboard trap (UI-16).
- WP2.3 Screen reader semantics (UI-17, UI-18): landmarks, labelled windows, live-region announcements, `inert` for minimized windows, DOM order equals tab order. Manual test with NVDA + Firefox at minimum; results documented.
- WP2.4 Title bar remains reachable; clamping on viewport resize (UI-3); minimum window size 480x320 (UI-7).
- WP2.5 Persistence and restore of layout including stack order (UI-6), deep link reflection of the active window via History API (UI-5, Q19).
- WP2.6 Narrow-viewport single-window mode switch (UI-12).
- WP2.7 Performance: 5 windows with live content, drag at 60 fps on a mid-range office PC; memory after opening and closing 100 windows (leak check).
- WP2.8 Modal dialogs scoped to a window with other windows inert (UI-16); iframe-embedded content keeps focus hand-off (UI-21, Q21).
- WP2.9 Styling fit: windows are themable through the `--slg-*` tokens and Bootstrap 5.3 `data-bs-theme`, light and dark.

### WP3: Authentication thin slice (Q11)
A Keycloak container (test realm, test users and groups, no real AD) and the shell as an OIDC public client (authorization code + PKCE, S256) using a maintained client library.
- WP3.1 Tokens held in memory only; the SDK exposes `getAccessToken()` to remotes and nothing else (8.3).
- WP3.2 Page reload and new tab: how the session is re-established without user interaction while the Keycloak SSO session is valid; effect on open windows (UI-13, UI-6).
- WP3.3 Silent renewal with rotating refresh tokens kept in memory; behaviour when the renewal fails (banner, state preserved).
- WP3.4 A sample resource server (a stub endpoint is enough, no Spring needed) validates a token and returns 403 without a permission, to confirm the frontend flow end-to-end.
- WP3.5 Findings on the XSS exposure in this model: what a hostile remote could read in the shell origin; confirm that the mitigations in PRD-0001 8.3 are implementable (SRI on remote entry, no external scripts).

## 5. Deliverables and exit criteria
Deliverables:
- D1: Spike report `docs/spikes/0001-shell-spike.md` (English): setup, versions, results per work package, measurements, limitations, recommendation.
- D2: ADR: "Module Federation approach" (plugin and version, or the chosen fallback), including the shared-singleton rules for the contract.
- D3: ADR: "Window manager" (library or in-house with rationale and the test results).
- D4: ADR or PRD-0001 amendment for token handling if WP3 contradicts Q11.
- D5: A list of PRD-0001 changes needed (requirement text, manifest fields, NFR numbers), applied through the normal review workflow.
- D6: Spike code on a clearly named branch or in `spikes/` that is excluded from builds and coverage; not merged into production code paths.

Exit criteria (all required for a "go"):
- E1: Two independently built remotes load into the shell at runtime from registry data, same-origin, under the strict CSP (WP1.2, WP1.3, WP1.6), or the chosen fallback does.
- E2: A failing remote is contained in its window in all four failure cases (WP1.7).
- E3: The window manager acceptance suite WP2.1, WP2.2, WP2.4, WP2.5 passes with the selected approach, and WP2.3 shows no blocker for screen-reader use at WCAG 2.2 AA intent.
- E4: Token handling in memory survives reload and renewal without losing window state (WP3.2, WP3.3).
- E5: The recommendation names residual risks and open questions for the skeleton phase.

A "no-go" on any criterion triggers the fallback decision in section 8 and an updated recommendation; the spike is not extended silently.

## 6. Non-functional requirements
- NFR-S1 Time box: 8 working days. At day 4 a checkpoint decides whether to continue, narrow scope, or switch fallback. The box is extended only by owner decision.
- NFR-S2 Reproducibility: every result is reproducible from the repository with documented commands; versions are pinned (lockfiles).
- NFR-S3 Quality exception: the 80 % coverage gate and the full implementation workflow of `CLAUDE.md` do not apply to disposable spike code, but the acceptance suite of WP2 and the failure tests of WP1.7 are automated (Playwright/Vitest) so that the evidence is repeatable. This exception ends with the spike; nothing from `spikes/` moves into production without the normal workflow.
- NFR-S4 Security hygiene: no real credentials, no real AD data; test realm only; dependencies scanned.
- NFR-S5 i18n: even in the spike, all user-visible strings of the shell prototype use the i18n mechanism in German and English, because the i18n contract (UI-24 to UI-26) is part of what is being verified.
- NFR-S6 Browsers: latest Chromium-based browser and Firefox for automated tests; manual accessibility checks on Firefox with NVDA, plus Chrome with a second screen reader if available.

## 7. Testing strategy
- Playwright end-to-end for WP1 (runtime loading, failure cases, CSP violations asserted via report-only logging and via blocking), WP2 acceptance suite (keyboard flows, focus assertions, ARIA tree snapshot, axe checks) and WP3 (reload, renewal).
- Vitest for unit-level checks of window state and layout persistence.
- Measurements for WP1.10 and WP2.7 recorded with a documented method (browser tooling, number of runs, hardware).
- Manual screen-reader pass logged in the report, with recorded versions of browser and screen reader.

## 8. Risks and fallback options
- R1 The Vite Module Federation plugin is immature or incompatible with the current Vite major (a Rolldown-based Vite major has been reported; verify). Fallback F1: Rspack with the official Module Federation 2 tooling for the shell and remotes while keeping React/Bootstrap (ADR-0005 amended; the "Vite" stack constraint in `CLAUDE.md` needs an explicit owner decision). Fallback F2: native ES modules with import maps and dynamic `import()` for remotes, with the shell providing shared libraries through the import map. Fallback F3: iframes as the default embedding mode (reverses ADR-0005; requires the SSO, theming and messaging bridge).
- R2 No window library satisfies keyboard and ARIA requirements: build in-house, limited to the minimum needed by UI-1 to UI-8 first.
- R3 Strict CSP conflicts with the plugin's loader: record the needed exceptions; do not relax NFR-1 without an ADR.
- R4 Screen-reader behaviour of floating windows is poor even when implemented per recommendation: escalate to the owner; options are a simplified linear window mode for assistive technology users or a tab-based presentation (changes ADR-0003).
- R5 Findings invalidate the in-memory token model: choose a backend-for-frontend (reverses Q11) and amend PRD-0001.

## 9. Rollout and operations
The spike runs on developer machines and in a throwaway Docker Compose setup (reverse proxy, Keycloak, shell, two remotes). No production host, no AD connection, no data. Outbound access is only needed for package registries and container images.

## 10. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| S1 | Who executes the spike (named person or team) and what is the calendar start? | Benjamin Honauer | PRD approval |
| S2 | Is a commercial window component acceptable at all if it fits (budget, licence review)? | Benjamin Honauer | spike day 1 |
| S3 | Does the "Vite" constraint in the tech stack allow Rspack as a fallback if the Vite plugin fails (F1)? | Benjamin Honauer | spike day 4 checkpoint |
| S4 | Which screen reader and browser combinations are actually deployed in the company? | Benjamin Honauer | spike day 1 |
| S5 | Which reference hardware (CPU/RAM/browser) represents the office PCs for performance measurements? | Benjamin Honauer | spike day 1 |

## 11. Review log
None yet. Required perspectives: requirements completeness, security (CSP, SRI, token exposure), UI/UX and accessibility (acceptance suite), operations and testability (reproducibility, time box).
