package de.systemlogistics.slgos.core.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A fresh PostgreSQL database per call, with the two application roles. It uses an external server when
 * {@code SLG_TEST_PG_URL} (plus {@code SLG_TEST_PG_USER} and {@code SLG_TEST_PG_PASSWORD}) is set, else a Testcontainers
 * PostgreSQL 17 (CI). Both paths use a real database; nothing is mocked (PRD-0003 section 9).
 */
public final class TestDatabase {

    public static final String APP_USER = "slg_app";
    public static final String APP_PASSWORD = "app-test-password";
    public static final String MIGRATOR_USER = "slg_migrator";
    public static final String MIGRATOR_PASSWORD = "migrator-test-password";

    /** Connection data of one created database. */
    public record Db(String url, String adminUrl, String adminUser, String adminPassword, String name) {

        public Connection admin() throws SQLException {
            return DriverManager.getConnection(adminUrl, adminUser, adminPassword);
        }

        public Connection app() throws SQLException {
            return DriverManager.getConnection(url, APP_USER, APP_PASSWORD);
        }

        public Connection migrator() throws SQLException {
            return DriverManager.getConnection(url, MIGRATOR_USER, MIGRATOR_PASSWORD);
        }
    }

    private static PostgreSQLContainer container;

    private TestDatabase() {}

    private static synchronized String[] server() {
        String url = System.getenv("SLG_TEST_PG_URL");
        if (url != null && !url.isBlank()) {
            return new String[] {url, System.getenv().getOrDefault("SLG_TEST_PG_USER", "postgres"),
                    System.getenv().getOrDefault("SLG_TEST_PG_PASSWORD", "")};
        }
        if (container == null) {
            container = new PostgreSQLContainer("postgres:17");
            container.start();
        }
        return new String[] {container.getJdbcUrl(), container.getUsername(), container.getPassword()};
    }

    public static Db create() {
        String[] server = server();
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(server[0], server[1], server[2]);
                Statement st = admin.createStatement()) {
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN "
                    + "CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'; END IF; END $$");
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + MIGRATOR_USER + "') THEN "
                    + "CREATE ROLE " + MIGRATOR_USER + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'; END IF; END $$");
            st.execute("CREATE DATABASE " + name + " OWNER " + MIGRATOR_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the test database", e);
        }
        String url = server[0].replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        try (Connection admin = DriverManager.getConnection(url, server[1], server[2]);
                Statement st = admin.createStatement()) {
            st.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot prepare the test database", e);
        }
        return new Db(url, url, server[1], server[2], name);
    }
}
