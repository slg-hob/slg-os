package de.systemlogistics.slgos.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.systemlogistics.slgos.core.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Migrations against a real PostgreSQL: advisory lock, failure handling, and the database roles of PRD-0003 SK-9. */
class MigrationAndPrivilegesIT {

    private static DataSource migrator(TestDatabase.Db db) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(db.url());
        ds.setUser(TestDatabase.MIGRATOR_USER);
        ds.setPassword(TestDatabase.MIGRATOR_PASSWORD);
        return ds;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static MigrationState migrate(TestDatabase.Db db) {
        MigrationState state = new MigrationState();
        new MigrationRunner(migrator(db), state).run();
        return state;
    }

    @Test
    void migratesAnEmptyDatabaseAndIsIdempotent() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        MigrationState first = migrate(db);
        assertThat(first.status()).isEqualTo(MigrationState.Status.MIGRATED);
        assertThat(first.appliedCount()).isEqualTo(1);
        MigrationState second = migrate(db);
        assertThat(second.status()).isEqualTo(MigrationState.Status.MIGRATED);
        assertThat(second.appliedCount()).isZero();
        try (Connection c = db.admin()) {
            assertThat(scalar(c, "SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(1);
        }
    }

    @Test
    void twoStartingContainersMigrateOnceUnderTheAdvisoryLock() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        CompletableFuture<MigrationState> a = CompletableFuture.supplyAsync(() -> migrate(db));
        CompletableFuture<MigrationState> b = CompletableFuture.supplyAsync(() -> migrate(db));
        assertThat(a.get(60, TimeUnit.SECONDS).status()).isEqualTo(MigrationState.Status.MIGRATED);
        assertThat(b.get(60, TimeUnit.SECONDS).status()).isEqualTo(MigrationState.Status.MIGRATED);
        assertThat(a.get().appliedCount() + b.get().appliedCount()).as("exactly one container applied it").isEqualTo(1);
    }

    @Test
    void aHeldLockMakesTheRunnerWaitUntilItIsReleased() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        try (Connection holder = db.migrator()) {
            holder.createStatement().execute("SELECT pg_advisory_lock(" + MigrationRunner.LOCK_KEY + ")");
            CompletableFuture<MigrationState> waiting = CompletableFuture.supplyAsync(() -> migrate(db));
            Thread.sleep(1500);
            assertThat(waiting.isDone()).as("blocked by the lock holder").isFalse();
            try (Connection admin = db.admin()) {
                assertThat(scalar(admin, "SELECT count(*) FROM pg_tables WHERE tablename = 'session'")).isZero();
            }
            holder.createStatement().execute("SELECT pg_advisory_unlock(" + MigrationRunner.LOCK_KEY + ")");
            assertThat(waiting.get(30, TimeUnit.SECONDS).status()).isEqualTo(MigrationState.Status.MIGRATED);
        }
    }

    @Test
    void aFailingMigrationIsRecordedRolledBackAndDoesNotThrow() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        MigrationState state = new MigrationState();
        new MigrationRunner(migrator(db), state, "classpath:db/broken").run();
        assertThat(state.status()).isEqualTo(MigrationState.Status.FAILED);
        assertThat(state.failure()).isNotBlank().doesNotContain("THIS IS NOT SQL");
        try (Connection c = db.admin()) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_tables WHERE tablename = 'ok_table'")).as("rolled back").isZero();
            assertThat(scalar(c, "SELECT pg_try_advisory_lock(" + MigrationRunner.LOCK_KEY + "::bigint)::int"))
                    .as("the lock was released").isEqualTo(1);
        }
    }

    @Test
    void anUnreachableDatabaseIsRecordedAsFailed() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:1/none");
        ds.setConnectTimeout(1);
        MigrationState state = new MigrationState();
        new MigrationRunner(ds, state).run();
        assertThat(state.status()).isEqualTo(MigrationState.Status.FAILED);
    }

    @Test
    void theStateStartsPending() {
        assertThat(new MigrationState().status()).isEqualTo(MigrationState.Status.PENDING);
    }

    // ---- roles (SK-9 AC1, AC2)

    @Test
    void theApplicationRoleCanOnlyAppendAndReadAuditEvents() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        migrate(db);
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            st.execute("INSERT INTO audit_event (occurred_at, event_type, outcome) VALUES (now(), 'auth.login', 'success')");
            assertThat(scalar(app, "SELECT count(*) FROM audit_event")).isEqualTo(1);
            for (String forbidden : new String[] {
                    "UPDATE audit_event SET event_type = 'x'", "DELETE FROM audit_event", "TRUNCATE audit_event",
                    "ALTER TABLE audit_event ADD COLUMN x int", "DROP TABLE audit_event", "CREATE TABLE evil (id int)",
                    "ALTER TABLE audit_event DISABLE TRIGGER ALL", "DROP TABLE session"}) {
                assertThatThrownBy(() -> st.execute(forbidden)).as(forbidden).isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).as("insufficient privilege").isEqualTo("42501"));
            }
            assertThat(scalar(app, "SELECT count(*) FROM audit_event")).as("nothing changed").isEqualTo(1);
        }
    }

    @Test
    void theApplicationRoleHasNormalAccessToSessionsButNoAccessToTheMigrationHistory() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        migrate(db);
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            st.execute("INSERT INTO logout_jti (jti, seen_at) VALUES ('j1', now())");
            st.execute("DELETE FROM logout_jti");
            assertThatThrownBy(() -> st.execute("SELECT * FROM flyway_schema_history")).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> st.execute("UPDATE logout_jti SET jti = 'x'")).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void theMigratorRoleOwnsTheTablesAndIsNotUsedByTheApplication() throws Exception {
        TestDatabase.Db db = TestDatabase.create();
        migrate(db);
        try (Connection c = db.admin()) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_tables WHERE tablename IN ('session','audit_event','logout_jti') "
                    + "AND tableowner = '" + TestDatabase.MIGRATOR_USER + "'")).isEqualTo(3);
            assertThat(scalar(c, "SELECT count(*) FROM pg_roles WHERE rolname = 'slg_app' AND (rolsuper OR rolcreatedb OR rolcreaterole)"))
                    .isZero();
        }
    }
}
