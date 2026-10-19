# PRD-0005: Visual language and design system

- Status: **Approved** by the owner on 2026-10-19 (independently reviewed by requirements, security, UI/UX and operations agents; owner interview decisions applied)
- Owner: Benjamin Honauer
- Date: 2026-10-19
- Related ADRs: 0003 (window-based desktop UI), 0005 (Module Federation), 0010 and 0011 (proposed), 0012; new ADRs written during this phase: section 7.5 (numbered from 0023)
- Related PRDs: PRD-0001 (UI-9 to UI-29, FR-4, FR-6, NFR-1, NFR-3, NFR-4, NFR-6, Q13), PRD-0003 (SK-3 AC9, SK-10, SK-11, SK-12, SK-15, SK-18), PRD-0004 (PF-5 AC6, PF-7, PF-9, PF-10, PF-12; sections 9 and 10)
- Related: `ROADMAP.md` ("Visual language and design system"), prototype canvas <https://claude.ai/artifact/81E7iLnfiCQE8mQW5f1rEy> (boards `RichtungC.dc.html` light and `RichtungC-dunkel.dc.html` dark; disposable, the repository is the source of truth), brand source <https://claude.ai/artifact/1BFqkAjen74quEsYTfTyuy> (design system of the maintenance planner, derived from systemlogistics.de)

## 1. Problem and goals
The skeleton ships functional defaults for the shell and the design system (PRD-0003 SK-11, SK-12): they work and meet the accessibility rules, but they have no identity. The platform will be used daily by many departments; it needs a recognisable, calm and accessible look that matches the company brand, that modules inherit without effort, and that can be tested automatically so it does not drift.

On 2026-10-19 the owner reviewed three prototype directions and chose **direction C "Soft glass"**: an operating-system-like desktop inspired by Synology DSM but with its own forms, a narrow top bar (main menu left, open windows in the bar, tray right), translucent chrome with solid content surfaces, the System Logistics brand colour and typeface.

Goals:
- G1: A written, versioned **visual language**: principles, colour, type, space, shape, elevation, glass, motion, iconography, logo use (VL-1 to VL-7).
- G2: A single source of tokens that generates CSS custom properties (`--slg-*`) mapped onto Bootstrap 5.3 variables, in light, dark, high-contrast and forced-colors, with an automated contrast check (VL-2, VL-5).
- G3: The shell chrome (top bar, main menu, desktop, widgets, window frame, dialogs, toasts, tray) and the pre-session pages implemented to the language, including the sign-in page of Keycloak (VL-8 to VL-12).
- G4: Glass that never costs legibility, accessibility, security clarity or speed: solid fallbacks, a performance budget with a guard, tested on office hardware (VL-8).
- G5: Automated visual regression and accessibility checks on a component catalogue (VL-13), and a formative usability test with users from two departments before the language is frozen as version 1 (VL-15).
- G6: Modules inherit the look through tokens and components only; the contract for token changes is explicit and consistent with Q13 (VL-14).

## 2. Non-goals
- New shell behaviour: window manager, keyboard model, session handling and admin functions stay as specified (PRD-0001, PRD-0003, PRD-0004). This PRD changes how they look, not what they do. Where a requirement below adds a visible control (a preference, a tooltip, a trust marker), the behaviour is named explicitly.
- A customer-facing website or marketing material; print layouts (a module's concern).
- User-uploaded wallpapers, user-defined colour themes, and a widget framework for modules (later; section 11).
- Languages beyond German and English; right-to-left layouts.
- Replacing the brand: colour, type family and logo come from the company (section 7.4); only accessibility corrections are made.
- Native mobile or kiosk optimisation (Q8, narrow mode stays a fallback).

## 3. Users and roles
- End users in all departments (the audience of the usability test), including users who rely on keyboard, screen reader, zoom, forced colors, high contrast or reduced motion.
- Administrators (dense tables, many states).
- Module developers: consume tokens and components; need clear rules and a catalogue.
- Design owner: the owner decides the visual direction and is the only design reviewer (decision 2026-10-19, Q56). Objective quality criteria are covered by the automated contrast, accessibility and visual-regression checks; the missing independent design review is a recorded residual risk.

## 4. Functional requirements
Numbering `VL-n`. Each AC names its verification: **(auto)** a test in CI, **(manual)** evidence recorded in `docs/verification/visual-language.md`. An AC without a mark is (auto). Dimensions in VL-10 are asserted by computed-style tests on the catalogue and by the visual-regression baselines.

### 4.1 Language and tokens

#### VL-1 Principles and direction
- AC1 (manual): The document `docs/design/visual-language.md` states the principles: (1) **glass is chrome, content is solid**: translucency only on the top bar, window frames, menus and widgets, never behind running text of a module; (2) colour carries brand and state, never meaning alone; (3) calm density; (4) every effect has a fallback, and glass, wallpaper and animation also have a user switch (VL-8 AC5, VL-9, VL-11); (5) one source of truth.
- AC2 (manual): The document includes the prototype boards as reference images (PNG exports in `docs/design/reference/`, small, synthetic data only; a PII scan covers the folder) and states which prototype details are normative (token values, structure, states) and which are illustrative (sample data, exact spacing, ARIA roles).
- AC3: Differences the implementation must make against the prototype (CI-checked where noted): no inline `style` attributes, no `<style>` elements injected at run time and no `data:` URLs (VL-13 AC4); theme through `data-bs-theme` and CSS files; fonts and icons from the bundle; no network requests; semantics are corrected: tabs are buttons with `aria-current`, the main menu is a disclosure containing a list of links (no `role="menu"` unless the full menu keyboard model is implemented), the theme control has an explicit three-state value (system, light, dark) with `aria-pressed` or the state in its name, dialogs carry `aria-modal`.

#### VL-2 Colour tokens and contrast
- AC1: Colour tokens live in `design/tokens/color.json` (list-shaped entries: name, light value, dark value, usage). Required semantic tokens: `surface-canvas`, `surface-raised`, `surface-raised-high`, `surface-glass`, `surface-glass-solid`, `surface-scrim`, `edge-glass`, `divider`, `control-border`, `ink-strong`, `ink-muted`, `link`, `focus-ring`, `primary`, `on-primary`, status pairs (`status-{default,success,warning,danger,neutral}-{text,bg}`), `solid-{danger,success,warning}-{fill,text}`, and the wallpaper luminance bounds (VL-9).
- AC2: Normative values and their verified contrast (WCAG 2.2, computed 2026-10-19):

| Token | Light | Dark | Check |
|---|---|---|---|
| `surface-canvas` | `#f2f2f2` | `#262626` | |
| `surface-raised` | `#ffffff` | `#333333` | content surface on glass |
| `surface-raised-high` | `#ffffff` | `#404040` | cards on canvas |
| `ink-strong` | `#262626` | `#f2f2f2` | 15.13:1 on `surface-raised` (light), 11.29:1 (dark) |
| `ink-muted` | `#595959` | `#bfbfbf` | 7.00:1 (light), 6.87:1 (dark) on `surface-raised`; 5.64:1 on `#404040` |
| `link` | `#00729c` | `#66cef5` | 5.40:1 on raised, 4.83:1 on canvas (light); 7.05:1 (dark) |
| `focus-ring` | `#00729c` | `#66cef5` | 5.40:1 and 7.05:1 against raised; at least 3:1 on solid surfaces |
| `control-border` | `#737373` | `#9a9a9a` | 4.74:1 on white, 4.24:1 on canvas (light); 4.49:1 on `#333333`, 3.68:1 on `#404040` (dark) |
| `primary` / `on-primary` | `#00aeef` / `#262626` | same | label 5.98:1; the fill against white is 2.53:1 and against canvas 2.26:1 (AC4) |
| status pairs | per brand source | per brand source | every pair at least 7.7:1 |
| `surface-glass` | `rgba(255,255,255,.78)` | `rgba(38,38,38,.74)` | see AC3 |

- AC3: Text on glass. Glass is composited over arbitrary backdrops (wallpaper, other windows, module content, from pure black to pure white). **Only `ink-strong` may be placed directly on glass**; it meets 4.5:1 over any backdrop (light glass over black 8.0:1, dark glass over white 5.79:1). Secondary text, links, control borders and icons that carry meaning sit on a solid plate (`surface-raised`) or use `ink-strong` (inactive tabs and titles are distinguished by weight and by a divider, not by `ink-muted`). Checked by a token test over black, white and the wallpaper extremes and by a Playwright test with black and white striped fixtures behind every glass element.
- AC4: The brand colour `#00aeef` fails as a text background for white text (2.53:1) and as a boundary on white. Rules: primary buttons use dark text; text in the brand hue uses `link`; focus rings use `focus-ring`, never `primary`; links inside running text are underlined (1 px, offset 2 px, also on hover and focus) because `link` against `ink-strong` is only about 2.8:1 (WCAG 1.4.1); links in menus and tiles are exempt. Hover, pressed and disabled fills of every button variant are defined in `color.json` and checked by the pair list. In forced-colors mode a primary button gets a 1 px `CanvasText` border. Decision (Q57, confirmed by the owner on 2026-10-19): the fill of a primary button is not a required boundary because its label (5.98:1) identifies it.
- AC5: A script `design/check-contrast` works on tokens, not on rendering. It evaluates every declared pair (foreground, background, role, threshold 4.5 for text, 3.0 for large text and UI components, in light, dark and high-contrast) and, for glass, composites `surface-glass` over the backdrop extremes (black, white, and the wallpaper luminance bounds) with the sRGB alpha formula; blur is ignored (it only narrows the extremes). A Playwright test renders the wallpaper at 1280 x 720, measures its real minimum and maximum luminance and asserts they lie inside the token bounds; a second test reads computed styles of the shell and the used Bootstrap components in both themes and checks the pairs. A deliberately failing token fixture proves the script fails (self-test).
- AC6: Forced-colors and high contrast: under `forced-colors: active` and `prefers-contrast: more` the wallpaper is replaced by `Canvas`, glass and shadows are removed, windows, menus and dialogs get a 1 px (dialogs 2 px) solid `CanvasText` border, a scrim is replaced by that border plus the text "Window blocked by dialog", the active tab, active window and selected tile are marked by a 2 px border or underline and `aria-current`, `control-border` is the system text colour, and the wordmark and icons use `currentColor` (`forced-color-adjust: auto`). No state relies on background colour. Tested with Playwright emulation, including a modal state.

#### VL-3 Typography
- AC1: **Typeface (decided by the owner on 2026-10-19, Q49): Source Sans 3** (SIL Open Font License 1.1; npm `@fontsource-variable/source-sans-3` 5.3.1, which ships Latin and Latin-Extended subsets as `woff2`), replacing the company face Murecho; the brand deviation is deliberate and recorded in the visual language document. The variable font covers weights 300, 400 and 600. It is self-hosted from the package (pinned by lockfile; source files and SHA-256 recorded in `docs/design/assets.lock`, CI verifies) and loaded with `font-display: swap`, a fallback stack with metric overrides (`size-adjust`, `ascent-override`) to avoid layout shift and `font-synthesis: none`. No request leaves the host (CSP `font-src 'self'`). The licence text ships in `THIRD-PARTY-NOTICES`. Changing the font later is a token change plus a catalogue baseline update (ADR-0023). (Murecho is also listed under the OFL; the replacement is the owner's choice, not a licence necessity.)
- AC2: The type scale (rem, root 16 px; normative): `display` 32/44 300; `page-title` 24/33 300; `section` 20/28 300 (also dialog titles); window and card titles 14/20 600 in chrome and 16/22 600 in content cards; `body` 16/24 400; `table` 14/20 400 (dense contexts); `button` 14/20 600; `body-strong` 600; `label` 13/16 600; `caption` 13/18; `mono` 14/20. Weight 300 is used only at 20 px and above, because light weights at small sizes lose contrast (the brand source uses 300 at 16 px; deviation recorded). Heights of rows, bars, buttons and titles are `min-height`, never fixed `height`, so the WCAG 1.4.12 text-spacing overrides do not clip; a test applies the overrides to the catalogue.
- AC3: Tabular figures are used in tables (Source Sans 3 provides `tnum`; a test asserts the feature is present in the shipped files); identifiers use the monospace token. Short strings (10 characters or fewer) tolerate +100 % length and sentences +35 % without clipping (pseudo-locale test on the catalogue); labels use `hyphens: auto` with `lang`, `overflow-wrap: anywhere` and at most three lines before an ellipsis, with the full name in the accessible name; no manual `<br>` inside words.

#### VL-4 Space, shape, elevation, size
- AC1: Spacing steps 4, 8, 12, 16, 24, 32, 48 px as `space-1..7`. Radius tokens: `radius-sm` 4, `radius-md` 8 (inputs, tiles, window content inset), `radius-lg` 12 (cards, menu items), `radius-xl` 16 (windows, menu, widgets), `radius-2xl` 20 (dialogs), `radius-pill` 9999. Pill shape is for single-line controls only (buttons, chips, tabs, status badges, search field); multi-line controls (textarea, listbox, combobox with chips, wrapping buttons) use `radius-md`; pill buttons have at least 16 px horizontal padding and may wrap. The pill look differs deliberately from the company website (6 px controls) and is part of the owner's choice of direction C (Q51).
- AC2: Elevation `shadow-1` (cards, tabs), `shadow-2` (windows, menu), `shadow-3` (dialogs); the inactive window has the lighter shadow and a 1 px solid `divider` hairline (at least 1.5:1 against the backdrop), so the active state is visible beyond the title weight.
- AC3: Pointer targets: in desktop mode every interactive control is at least 36 px high with a non-overlapping target of at least 24 x 24 px (WCAG 2.5.8), top-bar tabs 36 px; in narrow mode at least 44 x 44 px.
- AC4: Content containers keep at least 4 px inner padding so a 2 px focus ring with 2 px offset is never clipped by `overflow: hidden`; `scroll-padding-top` equals the top-bar height; windows cannot be positioned under the top bar (WCAG 2.4.11). Test: Tab through the catalogue and assert the ring's bounding box is fully visible and unobscured.

#### VL-5 Token pipeline and Bootstrap mapping
- AC1: Tokens are authored once in `design/tokens/*.json` (list-shaped entries, validated by a JSON schema) in the workspace package `@slg-os/design-tools` and generated into `packages/design-system/src/generated/`: `tokens.css` (custom properties `--slg-*` under `:root`, `[data-bs-theme="dark"]`, `@media (prefers-contrast: more)` and `@media (forced-colors: active)`), a SCSS map, TypeScript constants and the contrast pair list. Generated files are committed with a "generated, do not edit" header; the CI job `tokens-sync` regenerates and fails on `git diff --exit-code`. The generator is a Node script with pinned dependencies and no network access.
- AC2: Bootstrap 5.3 is built from SCSS with the generated map (Sass `@use ... with`), so derived values (hover shades, `rgba` helpers) follow the tokens; runtime `--bs-*` variables are set from `--slg-*` for themeable values; a test asserts every `--bs-*` used by the shell resolves to a `--slg-*`, and renders each used Bootstrap component in both themes with the contrast check on computed styles.
- AC3: Lint rules (extending PRD-0003 SK-12 AC2) in `@slg-os/eslint-config` and `@slg-os/stylelint-config`: no raw colours, no `!important`, no inline styles, no global selectors, no `--bs-*` overrides in module code, only `--slg-*` tokens, no password inputs and no shell dialog classes in module code.
- AC4: The token source is the contract for modules: renaming or removing a token, or changing a token so that a documented pair no longer passes, is a breaking change (VL-14).
- AC5: `design/**` has CODEOWNERS review; the package carries the 80 % Vitest gate with golden-output tests (JSON in, files out, byte-equal), failing-input tests and a CI self-test fault patch in `ci/selftest/` (a token pair below threshold turns the `design` job red).

### 4.2 Assets

#### VL-6 Iconography and accessible names
- AC1: Lucide (ISC licence; the MIT notice of the Feather-derived parts is kept) is the icon set, bundled by name; sizes 16, 20, 24, 28 px; stroke width 1.75; icons inherit `currentColor`; decorative icons are `aria-hidden`. The accessible name of a control with visible text contains that visible text (WCAG 2.5.3; for example "SLG OS, Hauptmenü"); a test compares visible text and accessible name for every catalogue control.
- AC2: The design-system icon set is a fixed, versioned list (`design/icons.json`) of the icons used by shell and modules; a manifest icon is a list identifier or a custom PNG (PRD-0004 PF-5 AC6); a module needing a new icon requests it by pull request. Entries marked `contract: true` are exempt from the unused-entry check, which covers shell icons as a warning.
- AC3: Icon-only controls (bell, theme, window controls) show a tooltip on hover and keyboard focus (WCAG 1.4.13: dismissible with `Esc`, hoverable, persistent; delay 400 ms); the notification bell shows its count as visible text. No icon is the only carrier of meaning in any catalogue state.
- AC4: Security of assets: a CI check allowlists SVG elements and attributes for icon sources, the product mark and the favicon (no `script`, `foreignObject`, `style`, `image`, `animate*`, `on*`; no `href` except `#fragment`; no `data:`); SVG is used as compiled components or `<img>` only; fixtures with malicious SVG must fail the check (self-test).

#### VL-7 Logo and brand use
- AC1 (manual): Product mark (decided by the owner on 2026-10-19, Q48): the project designs a simple product logotype "SLG OS" itself, for internal use only: set in the chosen typeface with a grid glyph, as SVG in light and dark variants, stored in the repository and approved by the owner. It does not reproduce or imitate the System Logistics company logo; the company wordmark is not used unless the owner supplies it later. The mark appears in the main menu header and on the sign-in and pre-session pages.
- AC2: The product name in the top bar is set in type (`ink-strong`, weight 600), as in the prototype.
- AC3: Favicon and application icon: a simple mark in light and dark variants, shipped as bundled files (`img-src 'self'`).
- AC4: `docs/design/assets.lock` records source URL, version, SHA-256 and licence of every font, icon package and brand file; CI verifies hashes and a licence scanner fails on an unlisted licence; licence texts ship in `THIRD-PARTY-NOTICES`.

### 4.3 Shell implementation

#### VL-8 Glass, fallbacks and performance
- AC1: Glass is one component class with three parts: translucent fill (`surface-glass`), 1 px edge (`edge-glass`) and backdrop blur (18 px with slight saturation). The solid fallback (`surface-glass-solid`, no blur) applies, in this precedence: user preference `glass: off` (AC5), the performance guard (AC3), `prefers-reduced-transparency: reduce`, `prefers-contrast: more`, forced-colors, and missing `backdrop-filter` support. Fallback tests use Playwright `emulateMedia` (`reducedMotion`, `forcedColors`, `contrast`) and an init script that makes `CSS.supports('backdrop-filter', ...)` false together with a stylesheet-level assertion for the `@supports not` rule; `prefers-reduced-transparency` is asserted at the stylesheet level and checked manually with the browser flag where it exists, recorded in the report. Computed backgrounds are opaque in every fallback.
- AC2: Content bodies of windows, dialogs, tables, forms and module content always sit on solid surfaces (`surface-raised`); a lint and test rule rejects a glass class on a module content root.
- AC3: Performance. CI measures drag frame times with `requestAnimationFrame` deltas for five open windows and stores them as a trend artifact; there is no absolute gate on the software-rendered runner, only a relative regression check (p95 more than 25 % worse than the `main` baseline of the same runner class, median of three runs). The 60 fps requirement (p95 frame time <= 16.7 ms, PRD-0001 NFR-6) is a (manual) gate measured on the owner's development machine and, as a proxy for weaker office PCs, in Chromium with 4x CPU throttling and GPU rasterisation disabled; no office reference PC exists (Q55), so the residual risk is accepted by the owner and recorded in the verification report. The default for `glass` stays `auto`, and the guard thresholds are conservative. At most the active window and three further windows use blur; other windows use the solid fallback. The runtime guard is evaluated only during a drag or a window animation over a sliding window of 60 frames, ignores hidden-tab frames and the first 500 ms, trips when more than 20 % of frames exceed 33 ms in 3 consecutive windows, sets `data-glass="solid"` for the session, trips at most once per session and never recovers automatically (no flapping); a reload resets it. The guard reports nothing to the server in this version (local only). Unit tests with a fake clock cover below threshold, sustained miss, single spike, hidden tab and no recovery.
- AC4: Glass never changes stacking, focus or hit-testing: blur does not reorder DOM or capture pointer events, and glass classes are never an ancestor of shell-level dialogs, menus or toasts (those render in a portal at the shell root, because `backdrop-filter` and animated `transform` create containing blocks). Test: with five glass windows and a step-up dialog open, `elementFromPoint` on the dialog and on the scrim returns the expected elements and inert windows ignore pointer and keyboard input.
- AC5: A user preference `glass: on | off | auto` (user menu; `auto` follows the OS settings and the guard) switches all glass to the solid fallback. It is stored in the user preferences (PRD-0003 SK-10) and validated as an enumeration.

#### VL-9 Desktop, wallpaper and widgets
- AC1: The default wallpapers are two built-in variants (light, dark) of a calm high-bay racking motif made of CSS gradients (no image request): vertical posts and horizontal beams at low contrast. Their luminance range is bounded by tokens so that VL-2 AC3 and AC5 hold. A plain wallpaper (`surface-canvas`) is a second option; the stored values are `rack` and `plain`.
- AC2: Uploaded or external images are not supported in this version (storage, content and XSS risk, contrast control); Q52 asks for later options.
- AC3: Desktop icons: 56 px glass tiles with 28 px icons and a label of at most three lines below, laid out on a grid (visual alignment); text directly on the wallpaper uses `ink-strong` only, and a selected or focused icon gets a glass plate behind its label; keyboard-operable with roving tabindex; unavailable modules appear disabled with the reason as text. Rearranging icons has a keyboard alternative (the single-pointer and keyboard alternatives of PRD-0003 SK-11 apply to every drag).
- AC4: One built-in widget, "System status", for users with `platform.overview.read`, shows alert counts from PRD-0004 PF-12 as a labelled region (not a live region), with full words ("Zertifikat läuft in 41 Tagen ab") rather than abbreviations, on a solid plate. It is delivered after PRD-0004 B5d; before that it is omitted behind the permission check. Other widgets and a widget API are out of scope (Q54).

#### VL-10 Shell components in the new language
- AC1: Top bar: 44 px glass; main-menu button (grid glyph and product name; accessible name contains the visible text), open-window tabs as pills (active tab on a solid pill, weight 600, `aria-current`) with min width 96 px, max 200 px, ellipsis and the full name in the accessible name; at overflow the surplus collapses into a "+n" window list (disclosure, keyboard-operable) and the active tab stays visible; clock as `<time>` with minute resolution, not a live region, formatted by `Intl` (24-hour in German); notification bell with visible count; language and theme controls; user menu with initials. Collapse order at narrow widths: clock, then language, then theme into the user menu. Narrow mode (below 768 CSS px) uses the window switcher of PRD-0003 SK-11. Tested with 12 windows at 1366 px, at 200 % zoom and at 320 px.
- AC2: Main menu: glass popover of 352 px width with a search field, category captions and a three-column tile grid (icon 26 px, label as in VL-3 AC3), the active entry marked with a 1 px `link` border, the user and the sign-out action (i18n key) at the foot; keyboard model as in PRD-0001 UI-9 and UI-15; in narrow mode a full-screen sheet.
- AC3: Window frame: radius 16, glass title bar of 48 px with icon, title (14/20 600), and controls (window menu, minimize, maximize, close; 36 px icon buttons); the content body is an inset solid surface (8 px frame, `radius-md`). Dialogs are solid, `radius-2xl`, with `aria-modal`, over a scrim of 40 % black scoped to the owning window (PRD-0001 UI-16). Toasts and tooltips are solid.
- AC4: The state components of PRD-0003 SK-12, the admin patterns of PRD-0004 PF-7 AC10 and the session-expiry banner are restyled; their APIs and behaviour do not change. Specified in the visual language document with states: checkbox, radio, switch, select, textarea, validation message, table hover, selected and sort, tray and notification panel, user menu, window menu, scrollbars, and hover and pressed fills over glass.
- AC5: Status badges are single-line pills with an icon or word; five tones from the status token pairs; the text is always present.
- AC6: One focus indicator for all components: 2 px `focus-ring` with 2 px offset, visible on glass and solid surfaces and in forced-colors.
- AC7: **Shell security surfaces are not imitable by modules.** The step-up dialog, the session-expiry banner, SessionExpired and sign-out are rendered by the shell at the shell root, outside every window's stacking context, always solid, and carry a shell-only trust marker (a lock glyph in the tray plus the text "Shell notice"). They never contain a credential input: sign-in and step-up happen only on the Keycloak origin, and the shell dialog offers a button that navigates there. Window titles come from the registry, not from the module, and are visibly truncated. Module code cannot use password inputs or the shell dialog classes (lint and kit). Test: a hostile fixture module draws a lookalike dialog; the shell notice stays above it and the lint rejects the fixture.

#### VL-11 Motion
- AC1: Motion tokens: `duration-fast` 120 ms, `duration-base` 200 ms, `duration-slow` 320 ms (menus and window open/close only), easing `standard` (cubic-bezier 0.2, 0, 0, 1) and `exit` (0.4, 0, 1, 1). Animated: window open, close, minimize and restore (scale 0.98 to 1 with opacity), menu and dialog open, toast enter, tab highlight. Not animated: tables, text, state changes inside content.
- AC2: Every animation, transition, `scroll-behavior` and `backdrop-filter` transition sits in a rule gated by `prefers-reduced-motion: no-preference`; in reduced motion changes are instantaneous and no transform runs; a test enumerates all such declarations in the built CSS and fails when one is not gated. Users can also turn animations off (preference `animation: on | off`, user menu, same effect).
- AC3: Animation is cosmetic: focus is on the new window or dialog by the next `requestAnimationFrame` after the open event (measured and recorded, not time-gated).

### 4.4 Pre-session pages and Keycloak

#### VL-12 Sign-in, pre-session and error pages
- AC1: The pages served by the core outside the shell (PRD-0003 SK-3 AC9: callback interstitial, signed-out, error, sign-in unavailable, popup-done) use the visual language: solid card on the wallpaper, wordmark, `display` heading, primary action, light and dark by `prefers-color-scheme` only. They contain no inline script and no inline style; the language is chosen as in SK-3 AC9 and can be changed through a link to the same page with `?lang=de|en` (allowlisted, never reflected); they send the shell's CSP and `frame-ancestors 'none'`.
- AC2: Keycloak theme: a custom login theme `slg-os` extending the base theme, packaged in `deploy/keycloak/themes/slg-os`, mounted read-only (owned by root) and selected in the realm file. The directory contains no `*.js` and no `*.ftl` (CI extension allowlist: css, woff2, svg, png, ico, properties); message values are plain text (a test renders every key and asserts no `<`). The realm sets the content security policy (`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; frame-ancestors 'none'; object-src 'none'`) and `X-Frame-Options: DENY`. The import smoke test asserts the theme is active, the headers on login, logout and error pages, both languages, zero requests to a foreign origin and zero CSP violations, and that error pages do not echo `error_description` unescaped (test with `<img onerror>`). Theme caching is off only in the dev profile; the Keycloak image is pinned by digest and an update re-runs the theme test. The theme's CSS is generated from the same tokens (VL-5).
- AC3: These pages are tested with axe in light, dark, forced-colors and at 320 CSS px width, and in the visual regression suite.

### 4.5 Quality and governance

#### VL-13 Catalogue, visual regression and accessibility tests
- AC1: A component catalogue page (PRD-0004 PF-7 AC10), whose skeleton is built in V2 and which every later milestone extends, lists every component and pattern in every state (default, hover, focus, active, disabled, error, loading, empty) using only production components. It is compiled out of production by a build flag; CI asserts the production bundle and the image contain no catalogue chunk and that the proxy answers 404 for its route (compose E2E). Test builds are a separate image tag that is never pushed.
- AC2: Visual regression runs in Chromium in a container image pinned by digest, GPU disabled, device scale factor 1, fixed viewport, animations off via reduced motion, fonts awaited (`document.fonts.ready`), clock frozen. Glass entries are shot over a flat fixed backdrop. Baselines are created only by the CI job or by `npm run vr:update` (same image); updating them is a manually triggered workflow that commits to the pull request; changed baselines are listed in the PR description, and CODEOWNERS on the baseline folder enforces a reviewer's approval. Comparison `maxDiffPixelRatio` 0.1 % with per-entry overrides that need a reason. A determinism test runs twice on the same commit and requires zero diff. Baselines are cropped to the component, optimised PNG, at most 1 MB change per pull request and 40 MB in total (CI fails above); only the three reference screens (desktop with menu, admin table with dialog, sign-in page) are full-page; ADR-0026 decides on LFS or an artifact store if the limit is reached. Baselines are created from V3a on for what a milestone delivers (no one-time re-creation).
- AC3: CI split. **Pull-request gate** (within the 25-minute budget of PRD-0004 section 9): Chromium, screenshots and axe in light and dark, sharded, at most 8 minutes per job (timing check). **Nightly**: Firefox and WebKit layout and axe (WebKit jobs are non-blocking, Chromium and Firefox are blocking), forced-colors, high-contrast, narrow, pseudo-locale and text-spacing variants, the performance trend, and the 1 MB / 40 MB baseline check. Nightly failures open an issue and block release tags, not pull requests.
- AC4: Static and runtime checks: the build sets `assetsInlineLimit: 0` and emits CSS as files for the shell and for remotes (no style-loader); CI scans the built output and fails on `data:` URLs, `<style>` elements, `style=` in server-rendered HTML, and `@import url(http` or `url(http` in CSS; dynamic values use the CSSOM or custom properties on classes; a Playwright run in Chromium and Firefox (WebKit non-blocking) asserts zero `securitypolicyviolation` events on the catalogue and the reference screens; unused-token and raw-colour checks run as in VL-5 and VL-6.
- AC5: Accessibility: axe (wcag2a/aa/21aa/22aa, best-practice) on every catalogue entry and reference screen, `incomplete` results reviewed; keyboard and screen-reader semantics are covered by the tests of PRD-0003 SK-11 and PRD-0004 PF-7; contrast by VL-2 AC5.

#### VL-14 Design system package, versioning and module contract
- AC1: `@slg-os/design-system` exports tokens, CSS, components and the icon list; it is a Module Federation singleton provided by the shell (ADR-0005), and the manifest `requires` design-system range of PRD-0001 FR-4 and FR-6 AC6 is the only version gate.
- AC2: Change classes: **patch** (value tweak that keeps all documented pairs passing), **minor** (new token, component or icon), **major** (renamed or removed token, changed component API, a pair no longer passing). Patch and minor changes ship without module changes. A major change is released only together with a contract major and updated modules (PRD-0001 Q13, FR-6). A pull request that changes tokens runs the contrast check and produces a token diff report.
- AC3: Delivered with the kit and the template (PRD-0004 B7a, B7b): the kit gains design checks (only `--slg-*` tokens, no raw colours, rendering in both themes with contrast on the module's computed styles, no glass on module content roots, no password inputs, no shell dialog classes), and the template module is extended accordingly. The freeze (VL-15 AC4) waits for them if B7 lands later than V7.
- AC4 (manual): Documentation: `docs/design/brand-book.md` (voice, colour, type, iconography, logo use, examples with token names), a generated token reference and the catalogue as the living reference. The existing Design System artifact stays an external convenience (Q53); the repository is authoritative.

#### VL-15 Usability test and freeze of version 1
- AC1 (manual): Before the language is declared version 1, a **formative** usability test is held with users from two departments (Sales and Technology/Service, Q50; recruiting and moderation Q60): at least 5 participants per department, a pilot session first, a German moderation script, and a seeded test instance with synthetic users, data and a test Keycloak realm (never production). Tasks: find and open a module, switch language and theme, recover from an expired session, find a notification, and (administrators) activate a module version and read an alert; tasks whose feature is not built yet are replaced and the replacement recorded. At least two participants use assistive technology (screen reader, or keyboard and zoom) and are reported separately, not pooled; low-vision and colour-vision-deficient participants are included where possible. The test runs on the development machine or a typical office PC if one is available then, and on a 1366 x 768 display at 125 % scale, with glass on and off, and includes dark mode and a forced-colors check set up through OS settings.
- AC2 (manual): Protocol and results go to `docs/verification/visual-language-usability.md`: tasks, success without help, time, errors, severity rating of each finding, a standard questionnaire (SUS, benchmark 68) and open comments. Because the samples are small, results are reported as counts with a confidence interval, not as percentages with significance. Reference targets: tasks completed without help for at least 80 % of attempts and SUS at least 70; misses produce a change list, not a silent pass.
- AC3 (manual): Participants are volunteers with no consequence for declining; line managers do not attend and do not see raw data. Decision of the owner on 2026-10-19 (Q58): no works-council agreement and no DPO confirmation are required for this study; the following minimum rules apply regardless. Needs related to disability are not recorded individually (the report says "assistive-technology participant" only in aggregate and merges groups under five). No recording by default; a recording needs written consent, is stored on the restricted share and deleted within 30 days after the report; raw notes are deleted after 90 days; reports use codes, not names.
- AC4 (manual): A lightweight early check of menu, tabs and window chrome with three to five users takes place after V3b so that glass and pill decisions are not discovered late; its result feeds the change list.
- AC5 (manual): Freeze: after the change list is processed and the owner (also the design reviewer, Q56) approves, the language is tagged `v1` (design-system minor release). Until then the system is labelled `v1-rc`; modules may rely on the token contract from `v1-rc`. VL-15 is not a merge gate: merges of V1 to V6 depend on the CI gate only. If no participants are available within six weeks after V6, the owner may freeze without the test and the risk is recorded in the verification report.

#### VL-16 Migration from the functional defaults
- AC1: The new look replaces the defaults of PRD-0003 SK-12 without API changes; the shell and the admin UI are re-skinned by switching the token source and component styles; no module code changes.
- AC2: Existing behavioural and accessibility tests pass unchanged; visual baselines follow VL-13 AC2.
- AC3: The reference module and the template module render correctly in both themes and under every glass fallback (PRD-0004 PF-10 AC2 extended).

## 5. Non-functional requirements
- NFR-V1 Accessibility: WCAG 2.2 AA for every component and page; contrast values in VL-2 are minimums; focus visible in all modes; reduced motion, reduced transparency, high contrast and forced colors honoured; zoom 200 % and reflow at 320 CSS px keep the chrome usable.
- NFR-V2 Performance: PRD-0001 NFR-6 holds with the new look (shell first load <= 2 s, drag 60 fps with 5 windows on the development machine and the throttled proxy profile (VL-8 AC3)); glass guard per VL-8 AC3.
- NFR-V3 Payload: fonts <= 150 KB total (three weights from the variable font, Latin and Latin-Extended subsets, `woff2`), design-system CSS <= 60 KB gzip (measured with `gzip -9` by a script with a unit test and a failing fixture); icons tree-shaken to the listed set; budgets enforced in CI. If the font budget is missed, ADR-0023 decides between fewer subsets and a raised budget.
- NFR-V4 Quality: PRD-0003 SK-2 gates apply to all code (generator, contrast script, components); coverage rules per package as set there.
- NFR-V5 Browser support: the look works in the supported browsers (PRD-0001 UI-29 as amended by the owner decision of 2026-10-19: Edge, Chrome, Firefox); WebKit in CI is informative and non-blocking; features without support degrade to the solid fallbacks.
- NFR-V6 No external runtime dependencies: no CDN, font service or analytics (project rule 7); the prototypes' use of a font CDN is not carried over.

## 6. UI/UX
The visual language is itself the UX topic; the flows are those of PRD-0001, PRD-0003 and PRD-0004. Texts are i18n keys in de and en (`shell`, `designSystem`, `platform`); German labels are reviewed by the owner. Layouts tolerate longer German text (VL-3 AC3). The prototype boards are the visual reference (illustrative where VL-1 AC2 says so); the catalogue is the implemented reference. The usability test (VL-15) validates understandability, discoverability and error recovery, not taste.

## 7. Data and interfaces

### 7.1 Files
`design/tokens/{color,type,space,radius,shadow,motion,size}.json`, `design/icons.json`, `design/pairs.json`, generated `packages/design-system/src/generated/*`, `docs/design/{visual-language,brand-book,assets.lock,licences}.md`, `docs/design/reference/*.png`, `deploy/keycloak/themes/slg-os/`, `e2e/visual/baselines/`, `docs/verification/{visual-language,visual-language-usability}.md`.

### 7.2 Interfaces
Public: the CSS custom properties `--slg-*`, the component library API (unchanged), the icon identifier list, the lint configurations and the kit checks. User preferences added to the schema of PRD-0003 SK-10: `wallpaper` (`rack|plain`), `animation` (`on|off`), `glass` (`on|off|auto`); all are enumerations validated on the server. The client maps preferences through an allowlist to fixed class names or `data-` values (never string-concatenated); an unknown or missing value yields the default and is not written back; `data-bs-theme` accepts only `light|dark|auto`. Tests with `'rack onload=1'`, `__proto__`, long strings and arrays.

### 7.3 Data
Three new preference values per user (no new personal data category). No new tables. The performance guard sends nothing to the server in this version.

### 7.4 Brand and sources
Colours and radii originate from systemlogistics.de via the brand design-system artifact; the typeface is Source Sans 3 instead of the company face (owner decision, Q49); deviations (accessibility corrections, weight rule for light type, pill controls, typeface) are listed in `docs/design/visual-language.md` with the reason. The product mark is designed in the project (Q48).

### 7.5 Decisions and ADRs produced by this phase
| # | Decision (proposal unless marked) | ADR |
|---|---|---|
| D1 | **Decided by the owner:** direction C "Soft glass", DSM-inspired but own, top bar, subtle contrast-safe effects | recorded in the visual language document |
| D2 | Token source in JSON, committed generated outputs with sync check, Sass map for Bootstrap, contrast check in CI | ADR-0023 |
| D3 | **Decided by the owner:** Source Sans 3 (OFL, self-hosted via fontsource) instead of Murecho | ADR-0023 |
| D4 | Glass fallbacks, text-on-glass rule, and the local performance guard | ADR-0024 |
| D5 | Keycloak login theme as CSS-only extension of the base theme with realm CSP | ADR-0025 |
| D6 | Visual regression in Chromium with pinned image and size limits; other browsers for layout and axe | ADR-0026 |

## 8. Security considerations
- CSP: `style-src 'self'`, `img-src 'self'` and `font-src 'self'` stay. Build settings and scans prevent inlined `data:` assets and run-time `<style>` elements (VL-13 AC4); prototype markup that uses inline styles and a font CDN is not production code.
- UI spoofing and clickjacking: security-relevant shell surfaces are solid, outside window stacking contexts, carry a trust marker, never ask for credentials, and cannot be imitated by module code (VL-10 AC7); glass never encloses shell dialogs (VL-8 AC4); the modal signal survives forced-colors (VL-2 AC6).
- Supply chain: font, icon and brand files are pinned by hash with licences (VL-7 AC4); SVG assets pass an allowlist check (VL-6 AC4); module-supplied SVG remains unsupported (PRD-0004 PF-5 AC6); fonts are never fetched at build time without a hash.
- Keycloak theme: no scripts or templates, plain-text messages, realm CSP and frame headers asserted, read-only mount (VL-12 AC2). Pre-session pages: no inline script or style, allowlisted language parameter.
- Preferences are enumerations validated server- and client-side (7.2).
- Telemetry: none in this version; the performance guard is local. Any later reporting is a separate decision of the owner (Q23).
- Reference images, baselines and the usability material use synthetic data only; a PII scan covers `docs/design/reference/` and the baselines.
- Usability test data protection: voluntary participation, written works-council agreement, DPO-confirmed notice and basis, aggregated assistive-technology reporting, retention limits, synthetic test instance (VL-15 AC3, AC1).

## 9. Testing strategy
- Unit: token generator (golden outputs, schema rejection, alias resolution), contrast script (known pairs, failing fixture, glass extremes), preference allowlist and schema, performance-guard logic with a fake clock, asset allowlist checks (malicious fixtures), hash and licence checks, CSS size script.
- Component: Vitest + Testing Library; Playwright catalogue tests for states, themes, forced-colors, high-contrast, narrow, pseudo-locale, text spacing, reduced motion, glass fallbacks, label-in-name, focus visibility, tooltips, 12-window tab overflow.
- Visual regression (Chromium, pinned container, determinism test) and axe on catalogue and reference screens; Firefox for blocking layout and axe, WebKit informative.
- Pre-session pages and Keycloak theme: Playwright against the compose stack (PRD-0003 SK-18 harness), both languages, light and dark, headers, CSP violations, no foreign-origin requests.
- Security: lookalike-dialog fixture module; stacking test with five glass windows and a dialog; CSP-violation run; SVG and theme extension allowlists.
- Performance: frame-time trend in CI; development-machine measurement and throttled proxy profile (manual gate); payload budgets in CI.
- Manual: design review by the owner against the prototype and principles (Q56); usability test (VL-15); legibility and glass cost on the development machine and the throttled proxy profile.
- Coverage: gates of PRD-0003 SK-2 apply; tests assert behaviour including failure paths; each fixed defect gets a regression test.

## 10. Rollout and operations
- Placement: V1 and V2 during phase 1b, V3 to V6 after PRD-0003 M4 and PRD-0004 B5a during 1b/1c, V7 before the phase 1c gate. ROADMAP is updated accordingly.
- Milestones (each its own pull request through the implementation workflow):
  - V1 Tokens and pipeline: token sources, generator and schema, Bootstrap mapping, contrast script with self-test, lint configurations, CODEOWNERS, `assets.lock` and licences, ADR-0023 (VL-2, VL-4, VL-5). Needs Q49, Q51, Q56 answered.
  - V2 Assets and catalogue skeleton: fonts, icon list, product mark and favicon, asset checks, payload budgets, catalogue skeleton (VL-3, VL-6, VL-7, VL-13 AC1).
  - V3a Glass: component, fallbacks, guard, preference, ADR-0024 (VL-8). Depends on PRD-0003 M4.
  - V3b Top bar, main menu, tray (VL-10 AC1, AC2) and the trust marker (AC7). Early check with users follows (VL-15 AC4).
  - V3c Window frame, dialogs, toasts, state components, admin pattern styling (VL-10 AC3 to AC6). Depends on PRD-0004 B5a.
  - V4 Desktop: wallpapers, desktop icons, widget (after B5d), motion, remaining preferences (VL-9, VL-11).
  - V5 Pre-session pages and Keycloak theme, ADR-0025 (VL-12).
  - V6 Visual regression hardening: CI jobs, PR/nightly split, baseline workflow and limits, ADR-0026 (VL-13).
  - V7 Usability test and freeze: protocol, test, change list, kit and template extension (VL-14 AC3), brand book, `v1` tag (VL-14, VL-15). Needs V3 to V6, PRD-0004 B5b to B5d and B7a.
  V1 and V2 can run in parallel with PRD-0003/0004 work. V7 is not a merge gate (VL-15 AC5).
- The phase is done when: every automatable AC is green on `main`; every manual AC has recorded evidence; the usability test is held and its change list processed or accepted by the owner (or the owner froze without it, recording the risk); the design reviewer and the owner approved the freeze; ADR-0023 to 0026 are merged.
- Operations: the Keycloak theme is part of the compose configuration; fonts and CSS are in the shell bundle; no new service. Rollback is a normal image rollback; preferences added by this phase are tolerated by the previous version (unknown enumerations ignored).

## 11. Open questions
| # | Question | Owner | Needed by |
|---|---|---|---|
| Q48 | Wordmark and logo | Benjamin Honauer | **Decided 2026-10-19:** the project designs an internal product mark "SLG OS" (VL-7 AC1); the owner approves the look in V2 |
| Q49 | Typeface | Benjamin Honauer | **Decided 2026-10-19:** Source Sans 3 instead of Murecho (VL-3 AC1) |
| Q50 | Departments for the usability test | Benjamin Honauer | **Decided 2026-10-19:** Sales and Technology/Service; recruiting, moderation and calendar slot are Q60 (PRD-0001), before V3 |
| Q51 | Pill-shaped single-line controls | Benjamin Honauer | **Decided 2026-10-19:** yes (VL-4 AC1) |
| Q52 | Wallpapers | Benjamin Honauer | **Decided 2026-10-19:** built-in rack and plain only, no uploads (VL-9) |
| Q53 | Existing Design System artifact | Benjamin Honauer | **Decided 2026-10-19:** lapses; the repository is the single source |
| Q54 | Widgets beyond "System status" | Benjamin Honauer | **Decided 2026-10-19:** later clock and calendar, module shortcuts and recently used items (a later PRD); no module-supplied widgets for now |
| Q55 | Reference office PC for the glass performance gate | Benjamin Honauer | **Decided 2026-10-19:** none; measurement on the development machine plus a throttled proxy profile; risk accepted (VL-8 AC3) |
| Q56 | Design reviewer | Benjamin Honauer | **Decided 2026-10-19:** the owner alone; automated checks cover the objective criteria; independent design review is a recorded residual risk |
| Q57 | Primary button on white | Benjamin Honauer | **Decided 2026-10-19:** no outline except in forced colors |
| Q58 | Works council and DPO for the usability test | Benjamin Honauer | **Decided 2026-10-19:** not required; minimum protection rules stay (VL-15 AC3) |
| Q47, Q23, Q25b | Unchanged (see PRD-0004, PRD-0001) | see there | see there |

## 12. Consistency notes on earlier documents
Not changed in the earlier documents before the owner approves; PRD-0001 revision 3 collects them with C1 to C25 (PRD-0003, PRD-0004).

| # | Finding | Handling |
|---|---|---|
| C26 | PRD-0001 UI-21 describes the token namespace `--slg-*` and AA-compliant tokens; the brand source system uses white text on `#00aeef` (2.53:1) for primary buttons | VL-2 AC4 sets dark text and darker link and focus tokens; revision 3 states the brand deviation |
| C27 | PRD-0001 NFR-1 forbids inline styles (`style-src 'self'`); the prototypes use inline style attributes and a font CDN | prototypes are illustrative only (VL-1 AC3, VL-13 AC4) |
| C28 | PRD-0003 SK-12 AC1 names Bootstrap 5.3.8 components and AA tokens but not the glass fallbacks | VL-8 and VL-16 extend it without API changes |
| C29 | The brand system sets card titles in weight 300 at 16 px; thin weights at small sizes risk contrast | weight 300 only from 20 px up (VL-3 AC2) |
| C30 | PRD-0001 UI-29 (Safari supported) is superseded by the owner decision of 2026-10-19 (same as PRD-0004 C25) | NFR-V5; one entry in revision 3 |
| C31 | PRD-0001 NFR-6 requires 60 fps drag with 5 windows; blur on five windows is a known risk on integrated GPUs | VL-8 AC3 limits blurred windows, adds a local guard and a manual gate on the development machine with a throttled proxy profile |
| C32 | ADR-0003 and PRD-0001 UI-17 speak of a taskbar; direction C moves the open-window list into the top bar | look only; recorded in revision 3 and in the consequences of a new note on ADR-0003 (the ADR itself is accepted and not edited) |
| C33 | PRD-0001 FR-6 AC6 and ADR-0010 name the design-system range check at registration; the field spelling differs between documents | VL-14 AC1 relies on `requires` as written in FR-4; revision 3 aligns the spelling |

## 13. Review log
Four independent review agents (fresh context, read-only) reviewed the draft on 2026-10-19; the author integrated the findings.

| Perspective | Findings | Result |
|---|---|---|
| Requirements completeness and consistency | 22 (3 high) | per-AC verification marks, versioning consistent with Q13, milestone dependencies and placement, V3 and V7 changes, token and icon rules vs contract, radius and surface tokens, open-question timing, C32/C33 |
| Security and data protection | 14 (5 high) | shell-surface spoofing (VL-10 AC7), build settings against CSP breakage, SVG allowlist, Keycloak theme verification and realm CSP, pre-session pages, supply chain pins and licences, preference allowlist, telemetry removed, usability test data protection |
| UI/UX, accessibility, i18n | 22 (4 high) | text-on-glass rule over any backdrop, underlined links, label in name, forced-colors and high-contrast rules, glass user switch, focus clipping, control border, normative type sizes, long labels, pill rule, tab overflow, ARIA corrections, tooltips, usability test design |
| Operations and testability | 14 (5 high) | PR/nightly split, visual-regression determinism and baseline workflow, 60 fps as manual gate and trend, guard thresholds, token-based contrast method, committed generated outputs, font subsetting, Keycloak theme tests, catalogue absence test, V3 split and V7 gating, design-tools coverage and CODEOWNERS |

Resolutions where reviews differed or the author chose:
- Glass transparency. The UI review showed that at the prototype alpha (0.78 light, 0.74 dark) secondary text and links fail over black or white backdrops, and proposed alpha of at least 0.92 or solid plates. Raising the alpha would remove most of the glass character the owner chose, so the rule is: only `ink-strong` directly on glass (it passes over any backdrop), everything else on solid plates (VL-2 AC3).
- Control border. The brand source's border (`#bfbfbf`) and the first draft (`#8c8c8c`, exactly 3.0:1 on canvas) were both too weak; `#737373` light and `#9a9a9a` dark were computed.
- Performance. Operations: CI cannot gate 60 fps without a GPU; the gate is a manual check on the development machine and a throttled proxy profile (no office reference PC exists), CI keeps a relative trend. Security: telemetry removed (the guard is local), so no works-council item arises from it.
- Usability test. Operations and requirements: not a merge gate, with a `v1-rc` label and a six-week fallback; security and UI: stronger consent, anonymity and assistive-technology participation. All applied.
- Primary button outline (Q57): the UI review judged a label of 5.98:1 sufficient; the author decided no outline except in forced colors and left the reviewer able to reopen.
- Principle "every effect has an off switch": delivered with the new `glass` preference rather than weakened.

Not verifiable before implementation: the `tnum` feature in the shipped Source Sans 3 files, glass performance on weaker office PCs (no reference PC exists), `prefers-reduced-transparency` behaviour in current browsers, Keycloak 26.8 theme and header settings, whether Bootstrap's Sass variables cover all derived values, and the real wallpaper luminance range.

Owner interview 2026-10-19 (after the reviews): Q48 product mark designed in the project; Q49 Source Sans 3; Q50 postponed; Q51 pill shapes yes; Q52 built-in wallpapers; Q55 no office reference PC; Q56 the owner is the only design reviewer; Q58 no works-council or DPO step for the usability study. The author recorded the consequences: a throttled-profile proxy for performance, conservative guard defaults, and the independent design review as residual risk.

Owner approval 2026-10-19. Q50, Q53, Q54 and Q57 were decided afterwards (section 11). PRD-0001 revision 3 (C1 to C33) records them.
