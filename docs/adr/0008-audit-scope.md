# 0008. Core audit log covers security events only

- Status: Accepted
- Date: 2026-10-19

## Context
Audit logging of employee actions raises data protection and works-council (Betriebsrat) concerns.

## Decision
The core audit log records **security-relevant events only**: logins and failed logins, logouts, permission/role mapping changes, module registration and changes, administrative actions. Business actions within a module are logged by the module itself if its own PRD requires it; the core does not mandate or collect them.

## Consequences
- Minimal personal data in the core; simpler data-protection story.
- Retention period and access restrictions for the audit log still need to be specified (PRD-0001).
- The core may offer an optional audit API that modules can use voluntarily; this is not part of the mandatory contract.
- Structured application logging with correlation IDs remains mandatory for operations, without logging personal content.
- If the works council or data protection officer requires more or less, supersede this ADR.
