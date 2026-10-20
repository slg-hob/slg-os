package de.systemlogistics.slgos.core.health;

import de.systemlogistics.slgos.core.db.MigrationState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** DOWN after a failed migration, so an older container keeps serving (PRD-0003 SK-20 AC2). */
@Component("migration")
public class MigrationHealthIndicator implements HealthIndicator {

    private final MigrationState state;

    public MigrationHealthIndicator(MigrationState state) {
        this.state = state;
    }

    @Override
    public Health health() {
        return switch (state.status()) {
            case MIGRATED -> Health.up().build();
            case PENDING -> Health.down().withDetail("migration", "pending").build();
            case FAILED -> Health.down().withDetail("migration", "failed").build();
        };
    }
}
