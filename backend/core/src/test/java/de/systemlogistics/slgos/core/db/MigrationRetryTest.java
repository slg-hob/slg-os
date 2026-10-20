package de.systemlogistics.slgos.core.db;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class MigrationRetryTest {

    @Test
    void aFailedOrPendingMigrationIsRunAgain() {
        MigrationRunner runner = mock(MigrationRunner.class);
        MigrationState state = new MigrationState();
        MigrationRetry retry = new MigrationRetry(runner, state);
        retry.retry();
        state.failed("Boom");
        retry.retry();
        verify(runner, times(2)).run();
    }

    @Test
    void aMigratedDatabaseIsLeftAlone() {
        MigrationRunner runner = mock(MigrationRunner.class);
        MigrationState state = new MigrationState();
        state.migrated(1);
        new MigrationRetry(runner, state).retry();
        verify(runner, never()).run();
    }
}
