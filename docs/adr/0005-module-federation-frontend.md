# 0005. Module Federation for module frontends

- Status: Accepted
- Date: 2026-10-19

## Context
Module frontends are built and deployed independently (ADR-0002) but must appear inside shell windows with identical look, theme and language.

## Decision
Module frontends are exposed as federated remotes (Module Federation) and loaded by the shell at runtime from the module's deployment. Shared singletons (React, the design-system/Bootstrap theme, i18n runtime, the shell SDK) are provided by the shell and must not be bundled by modules.

## Consequences
- Seamless integration: shared auth context, theme, language, window API.
- Tight version coupling on shared libraries: the module contract pins React major version and the design-system version range; contract versioning (see `CLAUDE.md`) governs upgrades.
- Vite support for Module Federation is provided by a plugin; its maturity must be verified in a spike before PRD-0001 is finalized.
- A module that fails to load must degrade gracefully inside its window (error boundary), never break the shell.
- CSP and CORS for loading remote entry points must be specified (same origin via reverse proxy preferred).
- Embedding third-party/legacy tools without React remains possible only via an explicit, separately specified exception (e.g. iframe); not part of the default contract.

## Alternatives considered
- iframes: maximal isolation, but SSO, theming, i18n and messaging must be bridged manually.
- Supporting both from the start: two integration paths to maintain.
