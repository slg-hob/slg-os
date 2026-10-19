# Specification review checklist

Used by the independent review agent (fresh context, not the author) for every PRD. The reviewer corrects and extends the PRD directly and records findings in the PRD's review log. Review each perspective separately.

## Requirements completeness
- Goals, non-goals, users and roles stated; every requirement numbered, testable, with acceptance criteria.
- Edge cases, error states, concurrency, migration of existing data, rollback.
- Consistency with existing PRDs and ADRs; conflicts flagged.
- Open questions listed with an owner; nothing implicit.

## Security
- Authentication and authorization for every interface; server-side enforcement; deny by default.
- Input validation, output encoding, injection, CSRF/XSS, SSRF, file handling.
- Secrets handling, token lifetime/revocation, least privilege between modules and core.
- Threats considered for new trust boundaries; security events identified for the audit log.

## UI/UX
- Flows, loading/empty/error states, consistency with the shell and design system.
- German and English texts via i18n keys; no layout assumptions that break with longer German text.
- Accessibility: keyboard, focus, screen reader, contrast; behaviour inside resizable windows.

## Permissions
- Permissions declared, named, and mapped to roles; no module-specific AD group names.
- Admin workflows to grant/revoke; effect on active sessions.

## Operations
- Deployment, configuration, health endpoints, logging with correlation IDs, backup/restore, upgrade and rollback.
- Resource impact on the single-server deployment.

## Data protection
- Personal data collected, purpose, retention, access; consistent with ADR-0008.

## Testability
- Each requirement can be verified automatically; strategy supports the 80 % coverage gate.
