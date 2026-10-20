package de.systemlogistics.slgos.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the Flyway migrations as the migrator role under a PostgreSQL advisory lock, so two starting containers never
 * migrate at once (PRD-0003 SK-20 AC2). A failing migration does not stop the process: the state becomes FAILED, readiness
 * stays DOWN and an older container keeps serving.
 */
public class MigrationRunner {

    /** Fixed key of the advisory lock (the ASCII bytes of "slgos-m1"). */
    static final long LOCK_KEY = 0x736c676f732d6d31L;

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);
    private static final int LOCK_WAIT_SECONDS = 120;

    private final DataSource migratorDataSource;
    private final MigrationState state;
    private final String location;

    public MigrationRunner(DataSource migratorDataSource, MigrationState state) {
        this(migratorDataSource, state, "classpath:db/migration");
    }

    /** @param location Flyway location of the migration scripts (tests point it at broken scripts) */
    public MigrationRunner(DataSource migratorDataSource, MigrationState state, String location) {
        this.migratorDataSource = migratorDataSource;
        this.state = state;
        this.location = location;
    }

    /** Migrates and records the result; never throws. */
    public void run() {
        try (Connection lockConnection = migratorDataSource.getConnection()) {
            lock(lockConnection);
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(migratorDataSource)
                        .locations(location)
                        .validateMigrationNaming(true)
                        .cleanDisabled(true)
                        .load();
                MigrateResult result = flyway.migrate();
                state.migrated(result.migrationsExecuted);
                LOG.info("database migrations done, {} applied", result.migrationsExecuted);
            } finally {
                unlock(lockConnection);
            }
        } catch (Exception e) {
            // No SQL text or credentials in the state; the log has the cause for the operator.
            LOG.error("database migration failed, readiness stays DOWN (Datenbankmigration fehlgeschlagen)", e);
            state.failed(e.getClass().getSimpleName());
        }
    }

    private static void lock(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(LOCK_WAIT_SECONDS);
            st.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement st = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            st.setLong(1, LOCK_KEY);
            st.execute();
        } catch (SQLException e) {
            LOG.warn("could not release the migration lock, it ends with the connection", e);
        }
    }
}
