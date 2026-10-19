# Module registration checklist

Platform team review before an administrator registers a module (ADR-0007).

- Source: internal GitHub repository, owner team named, maintainer contact recorded.
- Manifest valid for the supported contract version; id unique; permissions named and documented.
- Authentication via the SDK only; no direct AD or Keycloak access; no own login.
- Own database schema and credentials; no access to other modules' tables.
- UI uses the shell design system, i18n complete in German and English, works in a window.
- Health/readiness endpoints; structured logs with correlation ID; no personal data in logs.
- CI present: tests with coverage >= 80 %, dependency scanning, container build.
- Resource requirements stated and acceptable for the single-server deployment (ADR-0009).
- Spec and implementation reviews (independent agent) documented in the module repository.
