# 0001. Keycloak as identity provider in front of Active Directory

- Status: Accepted
- Date: 2026-10-19

## Context
Users and groups live in the local Active Directory. slg-os and every module need authentication and group information without each talking to AD.

## Decision
Run Keycloak as the identity provider. Keycloak federates the AD via LDAP; slg-os and all modules authenticate via OIDC and receive tokens carrying the user's identity and AD groups. Neither the core nor modules bind to AD directly.

## Consequences
- One additional service to operate (roughly 1.5 GB RAM) and to back up (realm configuration).
- Kerberos/SPNEGO single sign-on for domain PCs is possible in Keycloak. Whether it is required is **not yet decided**; it is tracked as an open question in PRD-0001.
- Modules validate tokens locally (JWT signature, issuer, audience); no per-request call to Keycloak.
- Realm configuration must be reproducible (exported, versioned, applied in Docker deployment).

## Alternatives considered
- Direct LDAP/AD in Spring Security: no extra service, but no SSO and every service couples to AD.
- Direct Kerberos/SPNEGO in Spring: passwordless but keytab/SPN operations burden on the core and no standard token for modules.
- Authentik: lighter, but Kerberos support is unconfirmed; Keycloak is the safer fit for AD.
