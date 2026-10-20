package de.systemlogistics.slgos.core.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real production-mode Keycloak of the stack (image built from {@code deploy/keycloak}, PostgreSQL, both realm files
 * imported) for the import smoke test and the token mechanism experiment. Needs Docker; tests skip themselves without it.
 * One instance is shared by all tests of the JVM.
 */
public final class KeycloakStack {

    public static final String E2E_REALM = "slg-e2e";
    public static final String PROD_REALM = "slg";
    public static final String CLIENT_SECRET = "core-client-secret-for-the-test-stack";
    public static final String E2E_PASSWORD = "e2e-user-password-for-the-test-stack";
    public static final String ADMIN_PASSWORD = "bootstrap-admin-password-for-the-test-stack";
    public static final String REFERENCE_SECRET = "reference-client-secret-for-the-test-stack";
    public static final String LDAP_PASSWORD = "ldap-bind-password-for-the-test-stack";

    private static KeycloakStack shared;

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer postgres;
    private final GenericContainer<?> keycloak;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private KeycloakStack() {
        Path deploy = Path.of("..", "..", "deploy").toAbsolutePath().normalize();
        postgres = new PostgreSQLContainer("postgres:17.6-bookworm").withNetwork(network).withNetworkAliases("postgres")
                .withDatabaseName("keycloak").withUsername("keycloak").withPassword("keycloak-db-password-for-the-test-stack");
        postgres.start();
        ImageFromDockerfile image = new ImageFromDockerfile("slgos-keycloak-test", false)
                .withFileFromPath(".", deploy.resolve("keycloak"));
        keycloak = new GenericContainer<>(image)
                .withNetwork(network)
                .withExposedPorts(8080, 9000)
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak")
                .withEnv("KC_HTTP_ENABLED", "true")
                .withEnv("KC_HOSTNAME_STRICT", "false")
                .withEnv("KC_PROXY_HEADERS", "xforwarded")
                .withEnv("KC_HTTP_MANAGEMENT_PORT", "9000")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "bootstrap-admin")
                .withEnv("SLG_SHELL_BASE_URL", "https://shell.slg.test")
                .withEnv("E2E_USER_PASSWORD", E2E_PASSWORD)
                .withEnv("LDAP_URL", "ldap://directory.example.internal:389")
                .withEnv("LDAP_BIND_DN", "cn=reader,dc=example,dc=internal")
                .withEnv("LDAP_USERS_DN", "ou=users,dc=example,dc=internal")
                .withCopyToContainer(Transferable.of("keycloak-db-password-for-the-test-stack"), "/run/secrets/kc_db_password")
                .withCopyToContainer(Transferable.of(ADMIN_PASSWORD), "/run/secrets/kc_bootstrap_admin_password")
                .withCopyToContainer(Transferable.of(CLIENT_SECRET), "/run/secrets/slg_core_client_secret")
                .withCopyToContainer(Transferable.of(LDAP_PASSWORD), "/run/secrets/ldap_bind_password")
                .withCopyToContainer(Transferable.of(REFERENCE_SECRET), "/run/secrets/reference_client_secret")
                .withCopyFileToContainer(MountableFile.forHostPath(deploy.resolve("keycloak/realm.json")), "/opt/keycloak/data/import/realm.json")
                .withCopyFileToContainer(MountableFile.forHostPath(deploy.resolve("keycloak/realm-e2e.json")),
                        "/opt/keycloak/data/import/realm-e2e.json")
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(6)));
        keycloak.start();
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static synchronized KeycloakStack shared() {
        if (shared == null) {
            shared = new KeycloakStack();
            Runtime.getRuntime().addShutdownHook(new Thread(shared::stop));
        }
        return shared;
    }

    private void stop() {
        keycloak.stop();
        postgres.stop();
        network.close();
    }

    public String baseUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    /** What an HTTP call returned. */
    public record Reply(int status, String body, java.util.Map<String, java.util.List<String>> headers) {
        public JsonNode json(JsonMapper mapper) {
            return mapper.readTree(body);
        }
    }

    public JsonMapper mapper() {
        return json;
    }

    /** Sends a request as the public proxy would: with the forwarded headers that make Keycloak see https and the public host. */
    public Reply send(String method, String path, Map<String, String> headers, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(30))
                    .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "sso.slg.test").header("X-Forwarded-Port", "443");
            headers.forEach(builder::header);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException("request to Keycloak failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    public Reply postForm(String path, Map<String, String> values, String basicAuth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (basicAuth != null) {
            headers.put("Authorization", "Basic " + basicAuth);
        }
        return send("POST", path, headers, form(values));
    }

    public static String basic(String id, String secret) {
        return java.util.Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** Administrator token of the master realm (bootstrap admin). */
    public String adminToken() {
        Reply reply = postForm("/realms/master/protocol/openid-connect/token", Map.of("grant_type", "password",
                "client_id", "admin-cli", "username", "bootstrap-admin", "password", ADMIN_PASSWORD), null);
        if (reply.status() != 200) {
            throw new IllegalStateException("admin login failed: " + reply.status());
        }
        return reply.json(json).path("access_token").asString();
    }

    public Reply admin(String method, String path, String token, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        return send(method, "/admin/realms" + path, headers, body);
    }
}
