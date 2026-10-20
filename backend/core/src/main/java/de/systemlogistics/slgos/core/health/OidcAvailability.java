package de.systemlogistics.slgos.core.health;

import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether sign-in works right now. A probe of Keycloak's key endpoint runs every 10 s; failed token calls report immediately.
 * The core starts without Keycloak and shows the "sign-in unavailable" page instead (PRD-0003 SK-16 AC1).
 */
@Component
public class OidcAvailability {

    private final OidcClient oidc;
    private volatile boolean available = true;

    public OidcAvailability(OidcClient oidc) {
        this.oidc = oidc;
    }

    public boolean isAvailable() {
        return available;
    }

    public void report(boolean ok) {
        this.available = ok;
    }

    @Scheduled(initialDelay = 1_000, fixedDelay = 10_000)
    void probe() {
        available = oidc.reachable();
    }
}
