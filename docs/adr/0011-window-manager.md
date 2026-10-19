# 0011. Window manager

- Status: Proposed (accepted pending the manual screen-reader verification that PRD-0002 deferred; needs independent review and owner acceptance)
- Date: 2026-10-19

## Context
ADR-0003 requires a window-based desktop. PRD-0002 asked whether a library or an in-house implementation meets UI-1 to UI-20.

## Decision
Build the window manager **in-house** inside the shell, with a shell-owned, versioned, serialisable state store, and no window library. Two engines were implemented behind the same store and the same accessibility layer and run through one acceptance suite: in-house pointer engine and `react-rnd` 10.5.3.

| Candidate | Result |
|---|---|
| In-house | 14 of 14 acceptance tests pass (keyboard, focus, live region, persistence, narrow mode, modal scoping, axe, performance, leak check) |
| `react-rnd` | 12 of 13 pass, 1 skipped: pointer snapping not provided by the library (adapter work); the leak check exceeded the 10 % threshold (11.9 %, within measurement noise of the in-house 9 %); narrow mode cannot unmount its drag layer without remounting the module |
| WinBox 0.2.82 | disqualified by the maintenance gate: last release 2023-07 |
| `dockview` 8.6.0, `rc-dock` 4.1.0 | pre-screened by npm metadata only (maintained, MIT/Apache-2.0); not implemented, they are docking layouts rather than free-floating windows |
| Commercial components | excluded by owner decision (S2) |

The accessibility semantics (labelled window sections, one `main`, `aria-current` taskbar, `inert` hidden windows, live region, keyboard window menu with move/resize/snap, focus management, close-guard dialog) live in shell-owned code and are the same for both engines. A library only replaces about 100 lines of pointer handling, so the build cost of the in-house engine is low while a library adds dependencies and constraints.

## Consequences
- Contract rule for modules: they must work in a resizable window, must not assume the viewport, and must not own window chrome.
- Findings that shaped the design: windows need their own stacking context (resize handles of a lower window otherwise intercept pointer events); focusing a covered window must raise it (WCAG 2.4.11), implemented by raising on focus; Bootstrap's default `btn-outline-secondary` fails AA contrast on tertiary backgrounds, so the design system defines emphasis-colour button tokens; the launcher must wrap at 320 px.
- Raising windows reorders DOM nodes to keep DOM order equal to stack order; moving a focused element can drop focus, which is handled by re-focusing. `Element.moveBefore()` was not evaluated.
- **Not done in this spike** (owner decision S1/S4): manual screen-reader pass (NVDA + Firefox), real Edge on an office PC, Firefox. Until the manual pass exists this ADR is not final; the simplified single-window layout (narrow-mode renderer) is the fallback if blockers appear.
