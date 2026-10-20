package de.systemlogistics.slgos.core.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/** DEGRADED while Keycloak is unreachable: the core keeps running and serves the "sign-in unavailable" page. */
@Component("oidc")
public class OidcHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final OidcAvailability availability;

    public OidcHealthIndicator(OidcAvailability availability) {
        this.availability = availability;
    }

    @Override
    public Health health() {
        return availability.isAvailable() ? Health.up().build() : Health.status(DEGRADED).withDetail("signIn", "unavailable").build();
    }
}
