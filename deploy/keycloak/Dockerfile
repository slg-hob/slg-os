# Keycloak in production mode (kc start --optimized), PostgreSQL, health and metrics on the management port (PRD-0003 SK-15).
# Token exchange v2 is a default feature of this version; no legacy feature flag is enabled (SK-19 AC2).
FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chmod=0755 entrypoint.sh /opt/keycloak/slg-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/slg-entrypoint.sh"]
CMD ["start", "--optimized", "--import-realm"]
