# 0009. Single-server Docker Compose deployment

- Status: Accepted
- Date: 2026-10-19

## Context
Expected scale is small: fewer than 100 users on one server.

## Decision
Deploy with Docker Compose on a single host: reverse proxy, Keycloak, slg-os backend and shell, PostgreSQL, and one set of containers per module. No Kubernetes and no high availability in the first versions.

## Consequences
- Simple operations; short maintenance windows are acceptable.
- Backup and restore of PostgreSQL volumes and the Keycloak realm are mandatory and documented.
- Configuration through environment variables and Docker secrets; no secrets in images or the repository.
- Design should not preclude later scale-out (stateless services, externalised state), but no effort is spent on HA now.
- Resource budget for the single host must be tracked as modules are added (Keycloak alone needs roughly 1.5 GB RAM).
