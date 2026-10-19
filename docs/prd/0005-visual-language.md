# PRD-0005: Visual language and design system

- Status: Draft
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0003 (window-based desktop UI), 0005 (Module Federation), 0010 and 0011 (proposed), 0012; new ADRs written during this phase: section 7.5 (numbered from 0023)
- Related PRDs: PRD-0001 (UI-21 to UI-28, NFR-1, NFR-4, NFR-6), PRD-0003 (SK-11, SK-12: functional defaults that this PRD replaces without API changes), PRD-0004 (PF-7 patterns)
- Related: `ROADMAP.md` ("Visual language and design system"), prototype canvas <https://claude.ai/artifact/81E7iLnfiCQE8mQW5f1rEy> (boards `RichtungC.dc.html` light and `RichtungC-dunkel.dc.html` dark; disposable, the repository is the source of truth), brand source <https://claude.ai/artifact/1BFqkAjen74quEsYTfTyuy> (design system of the maintenance planner, derived from systemlogistics.de)

## 1. Problem and goals
The skeleton ships functional defaults for the shell and the design system (PRD-0003 SK-11, SK-12): they work and meet the accessibility rules, but they have no identity. The platform will be used daily by many departments; it needs a recognisable, calm and accessible look that matches the company brand, that modules inherit without effort, and that can be tested automatically so it does not drift.

On 2026-10-19 the owner reviewed three prototype directions and chose **direction C "Soft glass"**: an operating-system-like desktop inspired by Synology DSM but with its own forms, a narrow top bar (main menu left, open windows in the bar, tray right), translucent chrome with solid content surfaces, the System Logistics brand colour and typeface.

Goals:
- G1: A written, versioned **visual language**: principles, colour, type, space, shape, elevation, glass, motion, iconography, logo use (VL-1 to VL-11).
- G2: A single source of tokens that generates CSS custom properties (`--slg-*`) mapped onto Bootstrap 5.3 variables, in light, dark and forced-colors, with an automated contrast check (VL-2, VL-5).
- G3: The shell chrome (top bar, main menu, desktop, widgets, window frame, dialogs, toasts, tray) and the pre-session pages implemented to the language, including the sign-in page of Keycloak (VL-7 to VL-10, VL-12).
- G4: Glass that never costs legibility, accessibility or speed: solid fallbacks, a performance budget, tested on office hardware (VL-8).
- G5: Automated visual regression and accessibility checks on a component catalogue (VL-13), and a usability test with users from two departments before the language is frozen as version 1 (VL-15).
- G6: Modules inherit the look through tokens and components only; the contract for token changes is explicit (VL-14).

## 2. Non-goals
- New shell behaviour: window manager, keyboard model, session handling and admin functions stay as specified (PRD-0001, PRD-0003, PRD-0004). This PRD changes how they look, not what they do.
- A customer-facing website or marketing material; print layouts (a module's concern).
- User-uploaded wallpapers, user-defined colour themes, and a widget framework for modules (later; section 11).
- Languages beyond German and English; right-to-left layouts.
- Replacing the brand: colour, type family and logo come from the company (section 7.4); only AA corrections are made.
- Native mobile or kiosk optimisation (Q8, narrow mode stays a fallback).

## 3. Users and roles
- End users in all departments (the audience of the usability test), including users who rely on keyboard, screen reader, zoom, forced colors or reduced motion.
- Administrators (dense tables, many states).
- Module developers: consume tokens and components; need clear rules and a catalogue.
- Design owner: the owner decides the visual direction; a design reviewer with experience reviews visual quality before the language is frozen (Q56).

## 4. Functional requirements
Numbering `VL-n`. Every AC is verified by an automated test unless marked (manual).

### 4.1 Language and tokens

#### VL-1 Principles and direction
- AC1: The visual language document `docs/design/visual-language.md` states the principles: (1) **glass is chrome, content is solid**: translucency only on the top bar, window frames, menus and widgets, never behind running text of a module; (2) colour carries brand and state, never meaning alone; (3) calm density (compact tables are allowed, cluttered ones are not); (4) every effect has a fallback and an off switch; (5) one source of truth.
- AC2: The document includes the prototype boards as reference images (PNG exports stored in `docs/design/reference/`, small) and states which prototype details are normative (token values, structure, states) and which are illustrative (sample data, exact spacing of the sample content).
- AC3: Differences from the prototype that the implementation must make are listed there: no inline `style` attributes (CSP `style-src 'self'`, PRD-0001 NFR-1), theme through `data-bs-theme` and CSS files, fonts and icons from the bundle, no network requests.

#### VL-2 Colour tokens and contrast
- AC1: Colour tokens live in `design/tokens/color.json` (list-shaped entries with name, light value, dark value, usage). Required semantic tokens: `surface-canvas`, `surface-raised` (solid content surface), `surface-glass`, `surface-glass-solid` (fallback), `surface-scrim`, `edge-glass`, `divider`, `control-border`, `ink-strong`, `ink-muted`, `link`, `focus-ring`, `primary` (fill), `on-primary`, status pairs (`status-{default,success,warning,danger,neutral}-{text,bg}`), `solid-{danger,success,warning}-{fill,text}`.
- AC2: Normative values (verified contrast, WCAG 2.2, computed 2026-10-19):

| Token | Light | Dark | Check |
|---|---|---|---|
| `surface-canvas` | `#f2f2f2` | `#262626` | |
| `surface-raised` | `#ffffff` | `#333333` (content on glass) / `#404040` (raised on canvas) | |
| `ink-strong` | `#262626` | `#f2f2f2` | 15.13:1 on raised (light); 11.29:1 on `#333333` (dark) |
| `ink-muted` | `#595959` | `#bfbfbf` | 7.00:1 on raised (light); 6.87:1 on `#333333` (dark) |
| `link` | `#00729c` | `#66cef5` | 5.40:1 on raised, 4.83:1 on canvas (light); 7.05:1 (dark) |
| `focus-ring` | `#00729c` | `#66cef5` | 5.40:1 and 7.05:1 against raised, above 3:1 on glass |
| `control-border` | `#8c8c8c` | `#8c8c8c` | 3.36:1 on white; 3.76:1 on `#333333` |
| `primary` / `on-primary` | `#00aeef` / `#262626` | same | label 5.98:1; the fill against white is 2.53:1 (see AC4) |
| status pairs | per source system | per source system | every pair at least 7.7:1 |

- AC3: Glass text contrast: over the lightest and darkest parts of the wallpaper, text on glass meets 4.5:1 (computed worst cases: light glass `ink-strong` 13.7:1, `ink-muted` 6.3:1, `link` 4.9:1; dark glass 13.2:1, 8.0:1, 8.3:1). The wallpaper luminance range is part of the token set (VL-9) and a test composites the real glass colour over sampled wallpaper pixels and fails below the thresholds.
- AC4: The brand colour `#00aeef` fails as a text background for white text (2.53:1) and as a boundary on white. Rules: primary buttons use dark text; text in the brand hue uses `link`; a primary button's fill is not the only identification (the label and a visible focus ring are); focus rings use `focus-ring`, never `primary`. (Open for the design reviewer: whether a primary button on a white surface also needs a 3:1 outline, Q57.)
- AC5: A script `design/check-contrast` evaluates every declared pair (a `pairs` list in the token source: foreground, background, role, threshold 4.5, 3.0 for large text and UI components, in light, dark and over each glass backdrop), runs in CI and fails on any pair below threshold; a self-test with a deliberately failing token proves it.
- AC6: Forced-colors: tokens map to system colours (`Canvas`, `CanvasText`, `LinkText`, `ButtonFace`, `Highlight`); glass and shadows are removed; focus indicators and status are conveyed by shape or text. Tested with Playwright `forcedColors`.

#### VL-3 Typography
- AC1: The typeface is Murecho (company face), weights 300, 400, 600, shipped as self-hosted `woff2` files subsetted to Latin and German characters, loaded by the bundle with `font-display: swap` and a fallback stack with metric overrides (`size-adjust`, `ascent-override`) to avoid layout shift; no request leaves the host (CSP `font-src 'self'`). Its licence is verified and recorded in `docs/design/licences.md` before the files are added (Q49); if it does not permit self-hosting, ADR-0023 chooses a replacement and the tokens change in one place.
- AC2: The type scale (rem, root 16 px): `display` 32/44, `page-title` 24/33, `section` 20/28, `card-title` 16/22 (weight 600 for window and dialog titles; weight 300 is used only at 20 px and above, because light weights at small sizes lose contrast), `body` 16/24 (compact tables 14/20 in dense contexts), `body-strong` 600, `label` 13/16 600, `caption` 13/18, `mono` 14/20. Line heights hold when users apply the WCAG 1.4.12 text-spacing overrides; a test applies them to the catalogue and asserts no clipping or overlap.
- AC3: Numbers use tabular figures in tables; identifiers use the monospace token; German text up to +35 % length does not clip in any component (the pseudo-locale test of PRD-0003 SK-11 AC11 covers the catalogue).

#### VL-4 Space, shape, elevation, size
- AC1: Spacing steps 4, 8, 12, 16, 24, 32, 48 px as `space-1..7`; radius tokens: `radius-sm` 4 (badge corner for compact tables), `radius-md` 8 (inputs, tiles), `radius-lg` 12 (cards, menu items), `radius-xl` 16 (windows, menu, widgets), `radius-2xl` 20 (dialogs), `radius-pill` 9999 (buttons, chips, tabs, search field, status badges). The prototype uses pill buttons; this deliberately differs from the company website (6 px controls) and is part of the owner's choice of direction C (Q51).
- AC2: Elevation: `shadow-1` (cards, tabs), `shadow-2` (windows, menu), `shadow-3` (dialogs); inactive windows use a lighter shadow than the active one. Values from the prototype (rgba black, 0.18 and 0.30 alpha for the larger two).
- AC3: Touch and pointer targets: every interactive control is at least 44 x 44 CSS px in narrow mode and at least 36 px high in desktop mode with at least 24 x 24 px of non-overlapping target (WCAG 2.5.8); icon-only controls have accessible names.

#### VL-5 Token pipeline and Bootstrap mapping
- AC1: Tokens are authored once (`design/tokens/*.json`, list-shaped entries, no nested maps) and generated into: `tokens.css` (custom properties `--slg-*` under `:root`, `[data-bs-theme="dark"]` and `@media (forced-colors: active)`), a SCSS map for the Bootstrap build, a TypeScript constants file for tests, and the contrast pair list. The generated files are committed or built in CI; a CI check fails if the sources and outputs differ.
- AC2: Bootstrap 5.3 variables (`--bs-body-bg`, `--bs-body-color`, `--bs-link-color`, `--bs-border-color`, `--bs-primary` and the component variables) are set from `--slg-*` in one file; Bootstrap components used in the shell look right in both themes without per-component overrides; a test renders each used Bootstrap component in both themes and runs the contrast check on computed styles.
- AC3: Lint rules (extending PRD-0003 SK-12 AC2): no raw colours, no `!important`, no inline styles, no global selectors, no `--bs-*` overrides in module code, only `--slg-*` tokens; the rule set is published in `@slg-os/eslint-config` and `@slg-os/stylelint-config`.
- AC4: The token source is the contract for modules: renaming or removing a token, or changing a token so that a documented pair no longer passes, is a breaking change (VL-14).

### 4.2 Assets

#### VL-6 Iconography
- AC1: Lucide (ISC licence, verified in `docs/design/licences.md`) is the icon set, bundled by name; sizes 16, 20, 24, 28 px; stroke width 1.75; icons inherit `currentColor`; decorative icons are `aria-hidden`, icon-only controls carry `aria-label`.
- AC2: The design-system icon set is a fixed, versioned list (`design/icons.json`) of the icons used by shell and modules; module manifests reference an icon by identifier from this list (PRD-0004 PF-5 AC6); a module needing a new icon requests it by pull request to the design system.
- AC3: Test: every icon identifier used in the shell exists in the list; the list contains no unused entries; no icon is the only carrier of meaning in any catalogue state (checked by the catalogue's accessibility tests).

#### VL-7 Logo and brand use
- AC1: The company wordmark (SVG, single ink) is used unmodified in the main menu header and on the sign-in and pre-session pages, in `link`-hue ink on light and in a light variant on dark; no recolouring or redrawing. The files and the approved dark treatment are supplied by the owner (Q48); until then a typographic placeholder "SLG OS" is used and the placeholder is a visible entry in the verification report.
- AC2: The product name in the top bar is set in type (`ink-strong`, weight 600), as in the prototype.
- AC3: Favicon and application icon: a simple mark derived from the wordmark or the grid glyph, in light and dark variants, shipped as bundled files (`img-src 'self'`).

### 4.3 Shell implementation

#### VL-8 Glass, fallbacks and performance
- AC1: Glass is implemented as a single component class with three parts: translucent fill (`surface-glass`), 1 px edge (`edge-glass`) and backdrop blur (18 px with a slight saturation boost). Fallbacks apply in this order: without `backdrop-filter` support, under `prefers-reduced-transparency: reduce`, in forced-colors mode, and when the performance guard (AC3) trips, the surface becomes `surface-glass-solid` without blur. Tests: Playwright with each condition emulated or the feature stripped; computed backgrounds are opaque in the fallback.
- AC2: Content bodies of windows, dialogs, tables, forms and module content always sit on solid surfaces (`surface-raised`); a lint/test rule rejects a glass class on a module content root.
- AC3: Performance budget: with five open windows, dragging a window holds 60 fps at p95 frame time <= 16.7 ms on the reference office PC (PRD-0001 NFR-6; Q55 provides the PC class) and in the CI runner as an indicator. At most the active window and three further windows use blur at once; other windows use the solid fallback (a documented, visible but subtle difference). A runtime guard measures dropped frames during drag and, after sustained misses, switches all windows to the solid fallback for the session and records a metric (no personal data). Tests: frame-time measurement in CI (indicator), and the guard's logic with a simulated clock.
- AC4: The stacking and focus behaviour decided for the window manager (PRD-0003 SK-11 AC2) is not changed by glass; blur never reorders DOM or captures pointer events.

#### VL-9 Desktop, wallpaper and widgets
- AC1: The default wallpapers are two built-in variants (light, dark) of a calm high-bay racking motif made of CSS gradients (no image request): vertical posts and horizontal beams at low contrast, as in the prototype. Their luminance is bounded so that VL-2 AC3 holds; the bounds are tokens.
- AC2: Optional: a plain colour wallpaper (`surface-canvas`) for users who prefer it; stored as a user preference (PRD-0003 SK-10) with the values `rack` and `plain`. Uploaded or external images are not supported in this version (storage, content and XSS risk, plus contrast control); revisit in Q52.
- AC3: Desktop icons: 56 px glass tiles with 28 px icons and a two-line label below, grid snapping, keyboard-operable (roving tabindex), up to the user's pinned modules (PRD-0001 UI-10); unavailable modules appear disabled with the reason as text.
- AC4: Widgets: one built-in widget, "System status" for users with `platform.overview.read`, summarising alert counts (PRD-0004 PF-12), placed at the top right of the desktop; it is a glass card with solid text areas as needed to meet VL-2 AC3. Other widgets and a widget API are out of scope (Q54).

#### VL-10 Shell components in the new language
- AC1: Top bar: 44 px glass; main-menu button (grid glyph and product name), open-window tabs as pills (active tab on a solid pill with weight 600), clock, notification bell with an accessible count, language and theme controls, user menu with initials. In narrow mode (PRD-0003 SK-11) the tabs collapse into a switcher.
- AC2: Main menu: glass popover of 352 px width with a search field, category captions and a three-column tile grid (icon 26 px, label), the active entry marked with a 1 px `link` border, the user and "Abmelden" at the foot; keyboard model as in PRD-0001 UI-9 and UI-15. In narrow mode it is a full-screen sheet.
- AC3: Window frame: radius 16, glass frame of 48 px title bar with icon, title (weight 600, left), controls on the right (window menu, minimize, maximize, close; icon buttons 36 px); the content body is an inset solid surface (8 px frame, radius 10). Active and inactive states differ by shadow and title weight (not colour alone). Dialogs are solid, radius 20, over a scrim of 40 % black scoped to the owning window (PRD-0001 UI-16). Toasts and tooltips are solid.
- AC4: State components of PRD-0003 SK-12 (Loading, Empty, Error, Forbidden, NotFound, Offline, SessionExpired), the admin patterns of PRD-0004 PF-7 AC10 (data table, form layout, confirmation dialog, status badge, tabs, combobox, diff table, step list, live-region helper, disclosure) and the session-expiry banner are restyled; their APIs and behaviour do not change.
- AC5: Status badges are pills with an icon or word; the five tones come from the status token pairs; the text is always present.
- AC6: All components expose the same keyboard focus indicator: 2 px `focus-ring` with 2 px offset, visible on glass and solid surfaces and in forced-colors.

#### VL-11 Motion
- AC1: Motion tokens: `duration-fast` 120 ms, `duration-base` 200 ms, `duration-slow` 320 ms (used only for menus and window open/close), easing `standard` (cubic-bezier 0.2, 0, 0, 1) and `exit` (0.4, 0, 1, 1). Animated: window open/close/minimize/restore (scale 0.98 to 1 with opacity), menu and dialog open, toast enter, tab highlight. Not animated: tables, text, state changes inside content.
- AC2: Every animation sits in a rule gated by `prefers-reduced-motion: no-preference`; in reduced motion, changes are instantaneous and no transform or parallax runs; a test enumerates all `animation` and `transition` declarations in the built CSS and fails when one is not gated; users can also turn animations off in the user menu (stored preference, same effect).
- AC3: Animations do not delay interaction: focus moves to the new window or dialog immediately, and the animation is cosmetic (tested by measuring time to focus with animation on).

### 4.4 Pre-session pages and Keycloak

#### VL-12 Sign-in, pre-session and error pages
- AC1: The pages served by the core outside the shell (PRD-0003 SK-3 AC9: callback interstitial, signed-out, error, sign-in unavailable, popup-done) and the Keycloak login, logout and error pages use the visual language: solid card on the wallpaper, wordmark, `display` heading, primary action, language switch (de/en), light and dark by `prefers-color-scheme`, AA contrast; no external assets.
- AC2: Keycloak theme: a custom login theme `slg-os` extending the base theme, packaged in the repository (`deploy/keycloak/themes/slg-os`), mounted into the container, selected in the realm file; it contains only CSS, fonts, images and message bundles (de, en), no custom scripts. The import smoke test (PRD-0003 SK-15 AC3) asserts the theme is active and that the login page renders in both languages with no request to a foreign origin. The theme's CSS is generated from the same tokens (VL-5).
- AC3: These pages are tested with axe, in light, dark, forced-colors and at 320 CSS px width, and in the visual regression suite.

### 4.5 Quality and governance

#### VL-13 Catalogue, visual regression and accessibility tests
- AC1: A component catalogue page (PRD-0004 PF-7 AC10) lists every component and pattern in every state (default, hover, focus, active, disabled, error, loading, empty) in light, dark, forced-colors, narrow and pseudo-locale variants, using only the production components. It is served in development and test builds only.
- AC2: Visual regression: Playwright screenshots of every catalogue entry and of the reference screens (desktop with menu, admin table with dialog, sign-in page) in light and dark, Chromium only, in a pinned container image with the self-hosted fonts so renderings are reproducible; comparison threshold documented; changed baselines require a reviewer's approval in the pull request and are listed in the PR description. Firefox and WebKit run the catalogue for layout and axe checks, not pixel comparison.
- AC3: Accessibility: axe (wcag2a/aa/21aa/22aa, best-practice) on every catalogue entry and reference screen, `incomplete` results reviewed; keyboard and screen-reader semantics are covered by the tests of PRD-0003 SK-11 and PRD-0004 PF-7; contrast by VL-2 AC5.
- AC4: Static checks in CI: no `style` attribute in the built shell markup except those set through the CSSOM, CSS size budget (see NFR-V3), no unused tokens, no raw colours, generated files in sync.

#### VL-14 Design system package, versioning and module contract
- AC1: `@slg-os/design-system` exports tokens, CSS, components and the icon list; its version follows the contract version rules of PRD-0001 FR-6 and the manifest `requires.designSystem` range (ADR-0010).
- AC2: Change classes: **patch** (value tweak that keeps all documented pairs passing, visual only), **minor** (new token, new component, new icon), **major** (renamed or removed token, changed component API, a pair no longer passing). A pull request that changes tokens runs the contrast check and a diff report of token changes; a major change needs the migration path required by Q13.
- AC3: The kit of PRD-0004 PF-9 gains design-system checks: only `--slg-*` tokens used, no raw colours, theme rendering in both themes (render-count and contrast on the module's computed styles), and no glass on module content roots.
- AC4: Documentation: `docs/design/brand-book.md` (voice, colour, type, iconography, logo use, examples with token names), generated token reference, and the catalogue as the living reference. The existing Design System artifact stays an external convenience (Q53); the repository is authoritative.

#### VL-15 Usability test and freeze of version 1
- AC1: Before the visual language is declared version 1, a usability test is carried out with users from two departments (Q50): at least 5 participants per department, tasks: find and open a module, switch language and theme, recover from an expired session, find a notification, and (administrators) activate a module version and read an alert; one keyboard-only participant and one using zoom or a screen reader are included where available.
- AC2: Protocol and results go to `docs/verification/visual-language-usability.md`: tasks, success without help, time, errors, a standard questionnaire (SUS) and open comments. Targets: at least 80 % of tasks completed without help, SUS at least 70; misses produce a change list, not a silent pass.
- AC3: Participants are volunteers, informed about purpose and data, anonymous in the report (codes, no names, departments only in aggregate); no screen or audio recording is kept without written consent; the test is announced to the works council as a usability study that does not evaluate people (Q23 context).
- AC4: Freeze: after the change list is processed and the design reviewer (Q56) and the owner approve, the language is tagged `v1` (design-system minor release); later changes follow VL-14.

#### VL-16 Migration from the functional defaults
- AC1: The new look replaces the defaults of PRD-0003 SK-12 without API changes; the shell and the admin UI are re-skinned by switching the token source and component styles; no module code changes.
- AC2: Visual regression baselines are re-created once in the milestone that introduces the look; existing behavioural and accessibility tests pass unchanged.
- AC3: The reference module and the template module render correctly in both themes and under glass fallbacks (PRD-0004 PF-10 AC2 extended).

## 5. Non-functional requirements
- NFR-V1 Accessibility: WCAG 2.2 AA for every component and page; contrast values in VL-2 are minimums; focus visible in all modes; reduced motion, reduced transparency and forced colors honoured; zoom 200 % and reflow at 320 CSS px keep the chrome usable.
- NFR-V2 Performance: PRD-0001 NFR-6 holds with the new look (shell first load <= 2 s, drag 60 fps with 5 windows); glass guard per VL-8 AC3.
- NFR-V3 Payload: fonts <= 150 KB total (three weights, subset, `woff2`); design-system CSS <= 60 KB gzip; icons are tree-shaken to the listed set; budgets enforced in CI.
- NFR-V4 Quality: PRD-0003 SK-2 gates apply to all code (the token generator, the contrast script, components); the generator and the script have unit tests with golden outputs; coverage rules per package as set there.
- NFR-V5 Browser support: the look works in the supported browsers (PRD-0001 UI-29 as amended: Edge, Chrome, Firefox); WebKit in CI is informative; features without support degrade to the solid fallbacks.
- NFR-V6 No external runtime dependencies: no CDN, font service or analytics (project rule 7); the prototypes' use of a font CDN is not carried over.

## 6. UI/UX
The visual language is itself the UX topic; the flows are those of PRD-0001, PRD-0003 and PRD-0004. Texts are i18n keys in de and en (`shell`, `designSystem`, `platform`); German labels are reviewed by the owner. Layouts tolerate +35 % text length. The prototype boards are the visual reference; the catalogue is the implemented reference. The usability test (VL-15) is the validation of understandability, discoverability and error recovery, not of taste.

## 7. Data and interfaces

### 7.1 Files
`design/tokens/{color,type,space,radius,shadow,motion,size}.json`, `design/icons.json`, `design/pairs.json` (contrast pairs), generated `packages/design-system/dist/*`, `docs/design/{visual-language,brand-book,licences}.md`, `docs/design/reference/*.png`, `deploy/keycloak/themes/slg-os/`, `docs/verification/visual-language-usability.md`.

### 7.2 Interfaces
Public: the CSS custom properties `--slg-*`, the component library API (unchanged), the icon identifier list, the stylelint/eslint configs, and the kit checks. Preferences: wallpaper (`rack|plain`), animations (`on|off`) added to the user preference schema (PRD-0003 SK-10); both are schema-validated enumerations.

### 7.3 Data
Two new preference values per user (no new personal data category). No new tables. The performance guard metric is an aggregate counter without user ids.

### 7.4 Brand and sources
Colours, typeface and radii originate from systemlogistics.de via the brand design-system artifact; deviations (AA corrections, weight rule for light type, pill controls) are listed in `docs/design/visual-language.md` with the reason. The wordmark is supplied by the owner (Q48).

### 7.5 Decisions and ADRs produced by this phase
| # | Decision (proposal unless marked) | ADR |
|---|---|---|
| D1 | **Decided by the owner:** direction C "Soft glass", DSM-inspired but own, top bar, subtle contrast-safe effects | recorded in the visual language document |
| D2 | Token source in JSON, generated CSS/SCSS/TS, contrast check in CI | ADR-0023 |
| D3 | Murecho self-hosted subset, or the replacement if the licence does not permit | ADR-0023 |
| D4 | Glass fallbacks and the performance guard (solid after sustained frame misses) | ADR-0024 |
| D5 | Keycloak login theme as CSS-only extension of the base theme | ADR-0025 |
| D6 | Visual regression in Chromium with pinned image; other browsers for layout and axe | ADR-0026 |

## 8. Security considerations
- CSP: `style-src 'self'` and `img-src 'self'` stay; no inline styles, no data URIs for images, no external fonts. Prototype markup that uses inline styles and a font CDN is not production code (VL-1 AC3, VL-13 AC4).
- Supply chain: font and icon files come from reviewed sources, are checked in or locked with hashes, and their licences recorded; SVG assets are authored or reviewed and sanitised (no scripts, no external references); module-supplied SVG remains unsupported (PRD-0004 PF-5 AC6).
- The Keycloak theme contains no custom scripts; the realm import test asserts the theme and the absence of foreign-origin requests.
- Wallpaper options are enumerations; no uploads (Q52).
- Performance guard and telemetry: only an aggregate counter; no user identifiers.
- Usability test data protection: voluntary participation, anonymised report, consent for any recording, works council informed (VL-15 AC3). The test is not an evaluation of employees.

## 9. Testing strategy
- Unit: token generator (golden outputs, rejection of invalid token shapes, alias resolution), contrast script (known pairs, failing fixture), preference schema, performance-guard logic with a fake clock.
- Component: Vitest + Testing Library for components; Playwright for the catalogue: states, themes, forced-colors, narrow, pseudo-locale, text-spacing overrides, reduced motion, reduced transparency, no-backdrop-filter fallback.
- Visual regression (Chromium, pinned container) and axe on catalogue and reference screens; Firefox and WebKit for layout and axe only.
- Pre-session pages and Keycloak theme: Playwright against the compose stack (PRD-0003 SK-18 harness), both languages, light and dark, no foreign-origin requests.
- Performance: frame-time measurement of window drag with 5 windows in CI (indicator) and on the reference office PC (manual gate in phase 1c, Q55); payload budgets in CI.
- Manual: the design review (Q56) against the prototype and the principles; the usability test (VL-15); a check on the reference office PC for glass cost and legibility.
- Coverage: gates of PRD-0003 SK-2 apply; tests assert behaviour including failure paths; each fixed defect gets a regression test.

## 10. Rollout and operations
- Milestones (each its own pull request through the implementation workflow):
  - V1 Tokens and pipeline: token sources, generator, Bootstrap mapping, contrast script with self-test, lint configs, licences file, ADR-0023 (VL-2, VL-4, VL-5).
  - V2 Assets: font subsetting and loading, icon list, wordmark placeholder and favicon, payload budgets (VL-3, VL-6, VL-7). Needs Q48 and Q49 answers for the final assets.
  - V3 Chrome: glass component, fallbacks and guard, top bar, main menu, window frame, dialogs, toasts, tray, state components (VL-8, VL-10). Depends on PRD-0003 M4 (shell) and PRD-0004 B5a (patterns); starts after both.
  - V4 Desktop: wallpapers, desktop icons, widget, motion, preferences (VL-9, VL-11).
  - V5 Pre-session pages and Keycloak theme (VL-12).
  - V6 Catalogue and visual regression: catalogue page, baselines, CI jobs, migration of baselines (VL-13, VL-16).
  - V7 Usability test and freeze: protocol, test, change list, `v1` tag, brand book (VL-14, VL-15).
  V1 and V2 can run in parallel with PRD-0003/0004 work; V3 to V6 follow the shell; V7 closes the phase.
- The phase is done when: every automatable AC is green on `main`; the usability test is held and its change list is processed or accepted by the owner; the design reviewer and the owner approved the freeze; ADR-0023 to 0026 are merged.
- Operations: the Keycloak theme is part of the compose configuration; fonts and CSS are in the shell bundle; no new service. Rollback is a normal image rollback; preferences added by this phase are tolerated by the previous version (unknown enum values ignored).

## 11. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| Q48 | Wordmark and logo files (SVG, single ink), the approved light-on-dark variant, favicon source, and rules for use on glass | Benjamin Honauer (marketing/brand owner) | before V2 closes |
| Q49 | Murecho licence (Google Fonts, believed to be SIL Open Font License) and approval to self-host and subset; fallback typeface if not permitted | platform team | start of V2 |
| Q50 | Which two departments, who recruits and moderates, time frame for the usability test | Benjamin Honauer | before V7 starts |
| Q51 | Pill-shaped controls (prototype) against the 6 px of the company website: assumed confirmed by the choice of direction C; the owner may veto | Benjamin Honauer | before V3 starts |
| Q52 | Wallpapers: built-in only (proposal) or a company wallpaper set by an administrator, or user images later | Benjamin Honauer | before V4 starts |
| Q53 | Keep and extend the existing Design System artifact as a secondary reference, or retire it once the repository holds the brand book | Benjamin Honauer | before V7 closes |
| Q54 | Which widgets beyond "System status" are wanted (clock, calendar, module shortcuts) and who may add them | Benjamin Honauer | later PRD |
| Q55 | A reference office PC (CPU, GPU, browser) for the glass performance check | Benjamin Honauer | before V3 closes |
| Q56 | Who is the design reviewer with design experience (internal or external), and review capacity | Benjamin Honauer | before V3 starts |
| Q57 | Does a primary button on white also need a 3:1 outline, given the fill is 2.53:1 against white while the label is 5.98:1; decided by the design reviewer with the accessibility reviewer | design reviewer | V1 |
| Q47, Q23, Q25b | Unchanged (see PRD-0004, PRD-0001) | see there | see there |

## 12. Consistency notes on earlier documents
Not changed in the earlier documents before the owner approves; PRD-0001 revision 3 collects them with C1 to C25.

| # | Finding | Handling |
|---|---|---|
| C26 | PRD-0001 UI-21 describes the token namespace `--slg-*` mapped onto Bootstrap variables and AA-compliant tokens; the brand source system uses white text on `#00aeef` (2.53:1) for primary buttons | VL-2 AC4 sets dark text and darker link/focus tokens; revision 3 states the brand deviation |
| C27 | PRD-0001 NFR-1 forbids inline styles (`style-src 'self'`); the prototypes use inline style attributes and a font CDN | prototypes are illustrative only (VL-1 AC3, VL-13 AC4) |
| C28 | PRD-0003 SK-12 AC1 names `Bootstrap 5.3.8` components and AA tokens but not the glass fallbacks | VL-8 and VL-16 extend it without API changes |
| C29 | The brand system sets card titles in weight 300 at 16 px; thin weights at small sizes risk contrast | weight 300 only from 20 px up (VL-3 AC2) |
| C30 | PRD-0001 UI-29 (Safari supported) is superseded by the owner decision of 2026-10-19; backdrop-filter support differences in Safari therefore need no extra work | NFR-V5; revision 3 |
| C31 | PRD-0001 NFR-6 requires 60 fps drag with 5 windows; blur on five windows is a known risk on integrated GPUs | VL-8 AC3 limits blurred windows and adds a runtime guard |

## 13. Review log
No review yet (Draft).
