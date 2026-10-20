package de.systemlogistics.slgos.core.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Database, fake Keycloak, clock and secret files for one test run, plus the matching {@code slg.*} properties. */
public final class TestEnvironment {

    public static final String SHELL_URL = "https://shell.slg.test";
    public static final String VAULT_KEY_1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    public static final String VAULT_KEY_2 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    public static final String CSRF_KEY = "csrf-key-for-tests-0123456789abcdef-long";

    public final TestDatabase.Db db;
    public final MutableClock clock;
    public final FakeKeycloak keycloak;
    public final Path secrets;

    private TestEnvironment(TestDatabase.Db db, MutableClock clock, FakeKeycloak keycloak, Path secrets) {
        this.db = db;
        this.clock = clock;
        this.keycloak = keycloak;
        this.secrets = secrets;
    }

    public static TestEnvironment create() {
        try {
            MutableClock clock = MutableClock.atNoon();
            Path dir = Files.createTempDirectory("slg-test-secrets");
            write(dir, "client-secret", FakeKeycloak.CLIENT_SECRET);
            write(dir, "vault-keys", "k1=" + VAULT_KEY_1 + "\nk2=" + VAULT_KEY_2 + "\n");
            write(dir, "csrf-key", CSRF_KEY);
            write(dir, "app-password", TestDatabase.APP_PASSWORD);
            write(dir, "migrator-password", TestDatabase.MIGRATOR_PASSWORD);
            return new TestEnvironment(TestDatabase.create(), clock, new FakeKeycloak(clock::instant), dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** The settings of a core pointing at this environment. */
    public Map<String, String> properties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("slg.env", "test");
        p.put("slg.shell-base-url", SHELL_URL);
        p.put("slg.internal-hosts", "core-backend,localhost");
        p.put("slg.bootstrap-admin-group", "/slg/admins");
        p.put("slg.oidc.issuer", keycloak.issuer());
        p.put("slg.oidc.client-secret-file", secrets.resolve("client-secret").toString());
        p.put("slg.session.vault-keys-file", secrets.resolve("vault-keys").toString());
        p.put("slg.session.vault-active-key-id", "k1");
        p.put("slg.session.csrf-key-file", secrets.resolve("csrf-key").toString());
        p.put("slg.db.url", db.url());
        p.put("slg.db.app-user", TestDatabase.APP_USER);
        p.put("slg.db.app-password-file", secrets.resolve("app-password").toString());
        p.put("slg.db.migrator-user", TestDatabase.MIGRATOR_USER);
        p.put("slg.db.migrator-password-file", secrets.resolve("migrator-password").toString());
        p.put("slg.db.pool-size", "6");
        p.put("management.server.port", "0");
        return p;
    }
}
