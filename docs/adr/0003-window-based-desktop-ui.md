# 0003. Window-based desktop UI

- Status: Accepted
- Date: 2026-10-19

## Context
The platform should feel like an operating system rather than a link list.

## Decision
The shell is a window-based desktop: movable and resizable windows, taskbar, start menu / app launcher, desktop icons, notifications area. Modules render inside shell-managed windows.

## Consequences
- Strongest OS look and feel; requires a window manager component in the shell (build on a small library or implement in-house, to be decided in the shell PRD).
- Window state (position, size, open apps) is a per-user preference stored by the core.
- Accessibility (keyboard navigation, focus management, screen readers) and small-screen behaviour need explicit requirements; a responsive fallback (e.g. maximized single-window mode on narrow viewports) must be specified in the shell PRD.
- Modules must not assume the full viewport; they must work in a resizable window.
- Theming (light/dark) and language (de/en) are owned by the shell.

## Alternatives considered
- Tile launcher with desktop theme: faster and more robust, less OS feel.
- Phased hybrid: rejected in favour of designing for windows from the start.
