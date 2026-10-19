# 0011. Window manager

- Status: Proposed (independently reviewed 2026-10-19; accepted only pending the manual screen-reader verification that PRD-0002 deferred, the completed rubric scores and the open gaps listed under Consequences)
- Date: 2026-10-19

## Context
ADR-0003 requires a window-based desktop. PRD-0002 asked whether a library or an in-house implementation meets UI-1 to UI-20.

## Decision
Build the window manager **in-house** inside the shell, with a shell-owned, versioned, serialisable state store, and no window library. Two engines were implemented behind the same store and the same accessibility layer and run through one acceptance suite: in-house pointer engine and `react-rnd` 10.5.3.

| Candidate | Result |
|---|---|
| In-house | 14 of 14 acceptance tests pass (keyboard, focus, live region, persistence, narrow mode, modal scoping, axe, performance, leak check) |
| `react-rnd` | 14 tests: 12 passed, 1 skipped (pointer snapping: the adapter was not written, estimated at under half a person-day), 1 failed (heap growth 10.1 / 13.4 / 12.1 % in the logged run against the 10 % threshold; in-house 6.0 / 9.2 / 5.4 %; both are within measurement noise). Narrow mode keeps the drag layer mounted with dragging disabled; the PRD gate "drag layer unmounted" was not asserted for this engine |
| WinBox 0.2.82 | disqualified by the maintenance gate: last release 2023-07 |
| `dockview` 8.6.0, `rc-dock` 4.1.0 | pre-screened by npm metadata only (maintained, MIT/Apache-2.0); not implemented, they are docking layouts rather than free-floating windows |
| Commercial components | excluded by owner decision (S2) |

The accessibility layer is identical for both engines, so the suite does not rank them on accessibility. Rubric scores (0/1/2), tie-break weights and patch-effort estimates were **not recorded** in the spike; the decision rests on pass/fail results and on the judgement that a library would replace only the pointer handling. They must be completed before acceptance. The accessibility semantics (labelled window sections, one `main`, `aria-current` taskbar, `inert` hidden windows, live region, keyboard window menu with move/resize/snap, focus management, close-guard dialog) live in shell-owned code and are the same for both engines. A library only replaces the pointer handling (roughly 100 lines in the spike, without edge cases such as `pointercancel`), so the build cost of the in-house engine is low while a library adds dependencies and constraints.

## Consequences
- Contract rule for modules: they must work in a resizable window, must not assume the viewport, and must not own window chrome.
- Findings that shaped the design: windows need their own stacking context (resize handles of a lower window otherwise intercept pointer events); focusing a covered window must raise it (WCAG 2.4.11), implemented by raising on focus; Bootstrap's default `btn-outline-secondary` fails AA contrast on tertiary backgrounds, so the design system defines emphasis-colour button tokens; the launcher must wrap at 320 px.
- Raising windows reorders DOM nodes to keep DOM order equal to stack order; moving a focused element can drop focus, which is handled by re-focusing. `Element.moveBefore()` was not evaluated.
- Known gaps of the in-house engine to close before acceptance: `pointercancel` and `lostpointercapture` handling; roving tabindex and Tab-closes behaviour of the window menu (its keyboard pattern is untested); a single-pointer alternative to free move/resize (WCAG 2.5.7); inert siblings inside the owning window and focus return to the invoking button for the close dialog (today it is effectively page-modal); focus retention when a window is raised by click or Tab (the DOM reorder can blur the focused element; alternatives `Element.moveBefore()` or `z-index` stacking with a documented tab-order exception); Alt+Space on Windows/Edge. `main` on the active window and one `h1` per window are deliberate and provisional until the manual screen-reader pass; the fallback is the single-window renderer.
- **Not done in this spike** (owner decision S1/S4): manual screen-reader pass (NVDA + Firefox), real Edge on an office PC, Firefox. Until the manual pass exists this ADR is not final; the simplified single-window layout (narrow-mode renderer) is the fallback if blockers appear.
