# Implementation review checklist

Used by the independent review agent (fresh context, not the author) after implementation and tests are done. The reviewer corrects and extends the implementation directly and reports what was changed.

## Requirements completeness
- Every requirement and acceptance criterion in the PRD is implemented; deviations are justified and reflected in the PRD or an ADR.
- No unrequested behaviour; no leftover TODOs or dead code.

## Security
- Permission checks on every backend endpoint; no reliance on frontend hiding.
- Token validation, input validation, output encoding, safe error messages, no secrets or personal data in logs.
- Dependencies reviewed and scanned; secure headers/CSP; SQL via parameters/ORM only.

## UI/UX
- Uses the shared shell and design system; no module-specific global styles.
- Loading, empty and error states present; works in resizable windows, light and dark theme.
- Keyboard and screen reader behaviour checked.

## Internationalization
- No hard-coded user-visible strings; every key exists in German and English; formats (dates, numbers) locale-aware.

## Tests and coverage
- Tests exist for success, failure and permission-denied paths; they assert behaviour, not implementation details.
- Global coverage >= 80 % and the build fails below it; new code is not hiding behind exclusions.

## Contract and architecture
- Module contract and SDK respected; no cross-module database access; migrations versioned and reversible where feasible.
- Matches ADRs; code, identifiers, comments and commit messages in English.

## Operations
- Runs in Docker; configuration via environment/secrets; health endpoints; documentation updated.
