package de.systemlogistics.slgos.core.db;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the migration again every minute while the start-time attempt did not succeed (for example because the lock holder
 * took longer than the wait limit), so a container does not need a restart once the cause is gone. A migration that fails
 * for good keeps failing and keeps readiness DOWN.
 */
@Component
class MigrationRetry {

    private final MigrationRunner runner;
    private final MigrationState state;

    MigrationRetry(MigrationRunner runner, MigrationState state) {
        this.runner = runner;
        this.state = state;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void retry() {
        if (state.status() != MigrationState.Status.MIGRATED) {
            runner.run();
        }
    }
}
