package de.systemlogistics.slgos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.systemlogistics.slgos.core.config.ConfigurationProblemException.ConfigProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigValidatorTest {

    @TempDir
    Path dir;

    private Path secret;
    private Path keys;
    private Path csrf;

    @BeforeEach
    void files() throws IOException {
        secret = Files.writeString(dir.resolve("secret"), "s3cr3t-do-not-leak\n");
        keys = Files.writeString(dir.resolve("keys"), "# comment\nk1=" + Base64.getEncoder().encodeToString(new byte[32]) + "\n");
        csrf = Files.writeString(dir.resolve("csrf"), "x".repeat(40));
    }

    private SlgProperties valid(String env, String shell, String issuer) {
        return new SlgProperties(env, shell, List.of("core-backend"), List.of("172.20.0.0/16"), null,
                new SlgProperties.Oidc(issuer, "slg-core", secret.toString(), Duration.ofSeconds(3)),
                new SlgProperties.Session(Duration.ofMinutes(30), Duration.ofHours(10), 10, Duration.ofSeconds(60),
                        Duration.ofMinutes(5), keys.toString(), "k1", csrf.toString()),
                new SlgProperties.Db("jdbc:postgresql://db/platform", "slg_app", secret.toString(), "slg_migrator",
                        secret.toString(), 10, "classpath:db/migration"));
    }

    private SlgProperties valid() {
        return valid("test", "https://shell.slg.test", "https://sso.slg.test/realms/slg");
    }

    private static List<String> variables(List<ConfigProblem> problems) {
        return problems.stream().map(ConfigProblem::variable).toList();
    }

    @Test
    void acceptsACompleteConfiguration() {
        assertThat(ConfigValidator.validate(valid())).isEmpty();
    }

    @Test
    void namesEveryMissingVariableInBothLanguages() {
        SlgProperties empty = new SlgProperties("dev", null, null, null, null,
                new SlgProperties.Oidc(null, "slg-core", null, Duration.ofSeconds(3)),
                new SlgProperties.Session(Duration.ofMinutes(30), Duration.ofHours(10), 10, Duration.ofSeconds(60),
                        Duration.ofMinutes(5), null, null, null),
                new SlgProperties.Db(null, null, null, null, null, 10, "classpath:db/migration"));
        List<ConfigProblem> problems = ConfigValidator.validate(empty);
        assertThat(variables(problems)).contains("SLG_SHELL_BASE_URL", "SLG_OIDC_ISSUER", "SLG_OIDC_CLIENT_SECRET_FILE",
                "SLG_SESSION_VAULT_KEYS_FILE", "SLG_SESSION_VAULT_ACTIVE_KEY_ID", "SLG_SESSION_CSRF_KEY_FILE", "SLG_DB_URL",
                "SLG_DB_APP_USER", "SLG_DB_MIGRATOR_USER", "SLG_DB_APP_PASSWORD_FILE", "SLG_DB_MIGRATOR_PASSWORD_FILE");
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.german()).isNotBlank();
            assertThat(p.english()).isNotBlank();
        });
    }

    @Test
    void requireThrowsWithAMessageThatListsTheVariables() {
        SlgProperties broken = valid("nonsense", "https://shell.slg.test", "https://sso.slg.test/realms/slg");
        assertThatThrownBy(() -> ConfigValidator.require(broken))
                .isInstanceOf(ConfigurationProblemException.class)
                .hasMessageContaining("SLG_ENV")
                .hasMessageContaining("Ungültige Konfiguration")
                .hasMessageContaining("Invalid configuration");
    }

    @Test
    void rejectsHttpOutsideDevAndTest() {
        assertThat(variables(ConfigValidator.validate(valid("e2e", "http://shell.slg.test", "https://sso.slg.test/realms/slg"))))
                .containsExactly("SLG_SHELL_BASE_URL");
        assertThat(ConfigValidator.validate(valid("dev", "http://localhost:8080", "http://localhost:8081/realms/slg"))).isEmpty();
    }

    @Test
    void rejectsAShellUrlWithAPath() {
        assertThat(variables(ConfigValidator.validate(valid("test", "https://shell.slg.test/app", "https://sso.slg.test/realms/slg"))))
                .containsExactly("SLG_SHELL_BASE_URL");
    }

    @Test
    void productionRefusesTestHostsAndTheE2eRealm() {
        List<String> problems = variables(ConfigValidator.validate(
                valid("production", "https://shell.slg.test", "https://sso.slg.test/realms/slg-e2e")));
        assertThat(problems).containsExactlyInAnyOrder("SLG_SHELL_BASE_URL", "SLG_OIDC_ISSUER", "SLG_OIDC_ISSUER");
        assertThat(ConfigValidator.validate(valid("production", "https://os.example.com", "https://sso.example.com/realms/slg")))
                .isEmpty();
    }

    @Test
    void productionNeedsTheProxyNetworkAndRefusesLocalhostAsInternalHost() {
        SlgProperties base = valid("production", "https://os.example.com", "https://sso.example.com/realms/slg");
        SlgProperties open = new SlgProperties("production", base.shellBaseUrl(), List.of("core-backend", "localhost"), List.of(),
                null, base.oidc(), base.session(), base.db());
        assertThat(variables(ConfigValidator.validate(open))).containsExactlyInAnyOrder("SLG_TRUSTED_PROXY_CIDRS",
                "SLG_INTERNAL_HOSTS");
        SlgProperties closed = new SlgProperties("production", base.shellBaseUrl(), List.of("core-backend"),
                List.of("172.20.0.0/16"), null, base.oidc(), base.session(), base.db());
        assertThat(ConfigValidator.validate(closed)).isEmpty();
    }

    @Test
    void validatesSessionSettings() {
        SlgProperties base = valid();
        SlgProperties.Session s = base.session();
        SlgProperties bad = new SlgProperties(base.env(), base.shellBaseUrl(), base.internalHosts(), base.trustedProxyCidrs(), null,
                base.oidc(), new SlgProperties.Session(Duration.ZERO, Duration.ofMinutes(1), 0, s.renewMargin(), s.stepUpMaxAge(),
                        s.vaultKeysFile(), s.vaultActiveKeyId(), s.csrfKeyFile()), base.db());
        assertThat(variables(ConfigValidator.validate(bad))).containsExactlyInAnyOrder("SLG_SESSION_IDLE_TIMEOUT",
                "SLG_SESSION_MAX_PER_USER");
    }

    @Test
    void rejectsAnUnknownActiveKeyAndMalformedKeyFiles() throws IOException {
        SlgProperties base = valid();
        SlgProperties.Session s = base.session();
        SlgProperties wrongId = new SlgProperties(base.env(), base.shellBaseUrl(), base.internalHosts(), base.trustedProxyCidrs(),
                null, base.oidc(), new SlgProperties.Session(s.idleTimeout(), s.absoluteTimeout(), 10, s.renewMargin(),
                s.stepUpMaxAge(), s.vaultKeysFile(), "missing", s.csrfKeyFile()), base.db());
        assertThat(variables(ConfigValidator.validate(wrongId))).containsExactly("SLG_SESSION_VAULT_ACTIVE_KEY_ID");

        Path shortKey = Files.writeString(dir.resolve("short"), "k1=" + Base64.getEncoder().encodeToString(new byte[16]));
        SlgProperties shortKeys = new SlgProperties(base.env(), base.shellBaseUrl(), base.internalHosts(), base.trustedProxyCidrs(),
                null, base.oidc(), new SlgProperties.Session(s.idleTimeout(), s.absoluteTimeout(), 10, s.renewMargin(),
                s.stepUpMaxAge(), shortKey.toString(), "k1", s.csrfKeyFile()), base.db());
        assertThat(variables(ConfigValidator.validate(shortKeys))).containsExactly("SLG_SESSION_VAULT_KEYS_FILE");

        Path weakCsrf = Files.writeString(dir.resolve("weak"), "short");
        SlgProperties weak = new SlgProperties(base.env(), base.shellBaseUrl(), base.internalHosts(), base.trustedProxyCidrs(),
                null, base.oidc(), new SlgProperties.Session(s.idleTimeout(), s.absoluteTimeout(), 10, s.renewMargin(),
                s.stepUpMaxAge(), s.vaultKeysFile(), "k1", weakCsrf.toString()), base.db());
        assertThat(variables(ConfigValidator.validate(weak))).containsExactly("SLG_SESSION_CSRF_KEY_FILE");
    }

    @Test
    void rejectsUnreadableOrEmptySecretFilesAndBadDatabaseSettings() throws IOException {
        SlgProperties base = valid();
        Path empty = Files.writeString(dir.resolve("empty"), "  \n");
        SlgProperties bad = new SlgProperties(base.env(), base.shellBaseUrl(), base.internalHosts(), List.of("not-a-cidr"), null,
                new SlgProperties.Oidc(base.oidc().issuer(), "slg-core", dir.resolve("absent").toString(), Duration.ofSeconds(3)),
                base.session(), new SlgProperties.Db("mysql://x", "slg_app", empty.toString(), "slg_migrator",
                secret.toString(), 1, "classpath:db/migration"));
        assertThat(variables(ConfigValidator.validate(bad))).containsExactlyInAnyOrder("SLG_OIDC_CLIENT_SECRET_FILE",
                "SLG_DB_URL", "SLG_DB_APP_PASSWORD_FILE", "SLG_DB_POOL_SIZE", "SLG_TRUSTED_PROXY_CIDRS");
    }

    @Test
    void secretsAreNeverPartOfTheMessages() {
        SlgProperties broken = valid("nonsense", "https://shell.slg.test", "https://sso.slg.test/realms/slg");
        assertThat(ConfigValidator.validate(broken).toString()).doesNotContain("s3cr3t-do-not-leak");
    }
}
