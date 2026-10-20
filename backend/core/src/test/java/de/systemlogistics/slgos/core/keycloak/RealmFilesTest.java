package de.systemlogistics.slgos.core.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Static checks of the committed realm files (PRD-0003 SK-15 AC2, AC3, AC4). The import into a real production-mode Keycloak
 * is tested in {@link KeycloakRealmImportIT}; these checks run everywhere and need no Docker.
 */
class RealmFilesTest {

    private static final Path DIRECTORY = Path.of("..", "..", "deploy", "keycloak");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static String text(String file) throws IOException {
        return Files.readString(DIRECTORY.resolve(file));
    }

    private static JsonNode realm(String file) throws IOException {
        return MAPPER.readTree(text(file));
    }

    private static JsonNode coreClient(JsonNode realm) {
        for (JsonNode client : realm.path("clients")) {
            if ("slg-core".equals(client.path("clientId").asString())) {
                return client;
            }
        }
        throw new AssertionError("client slg-core missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"realm.json", "realm-e2e.json"})
    void bothRealmsHaveTheProductionSecuritySettings(String file) throws IOException {
        JsonNode realm = realm(file);
        assertThat(realm.path("sslRequired").asString()).isEqualTo("all");
        assertThat(realm.path("registrationAllowed").asBoolean(true)).isFalse();
        assertThat(realm.path("resetPasswordAllowed").asBoolean(true)).isFalse();
        assertThat(realm.path("bruteForceProtected").asBoolean()).isTrue();
        assertThat(realm.path("revokeRefreshToken").asBoolean()).isTrue();
        assertThat(realm.path("refreshTokenMaxReuse").asInt(-1)).isZero();
        assertThat(realm.path("internationalizationEnabled").asBoolean()).isTrue();
        assertThat(realm.path("defaultLocale").asString()).isEqualTo("de");
        List<String> locales = new ArrayList<>();
        realm.path("supportedLocales").forEach(l -> locales.add(l.asString()));
        assertThat(locales).containsExactly("de", "en");
    }

    @ParameterizedTest
    @ValueSource(strings = {"realm.json", "realm-e2e.json"})
    void lifetimesFollowThePrd(String file) throws IOException {
        JsonNode realm = realm(file);
        assertThat(realm.path("accessTokenLifespan").asInt()).as("access token").isEqualTo(300);
        assertThat(realm.path("ssoSessionIdleTimeout").asInt()).as("SSO idle >= 30 minutes").isGreaterThanOrEqualTo(1800);
        assertThat(realm.path("ssoSessionMaxLifespan").asInt()).as("SSO max >= 10 hours").isGreaterThanOrEqualTo(36000);
        assertThat(realm.path("ssoSessionIdleTimeout").asInt()).as("refresh lives at least as long as the session")
                .isGreaterThanOrEqualTo(realm.path("accessTokenLifespan").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"realm.json", "realm-e2e.json"})
    void theCoreClientIsConfidentialWithPkceAndNoImplicitOrPasswordGrant(String file) throws IOException {
        JsonNode client = coreClient(realm(file));
        assertThat(client.path("publicClient").asBoolean(true)).isFalse();
        assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(client.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("webOrigins")).isEmpty();
        assertThat(client.path("attributes").path("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(client.path("attributes").path("backchannel.logout.url").asString())
                .isEqualTo("http://core-backend:8080/core/internal/backchannel-logout");
        assertThat(client.path("attributes").path("backchannel.logout.session.required").asString()).isEqualTo("true");
        assertThat(client.path("redirectUris")).hasSize(1);
        assertThat(client.path("redirectUris").get(0).asString()).doesNotContain("*").endsWith("/core/auth/callback");
        assertThat(client.path("attributes").path("post.logout.redirect.uris").asString()).doesNotContain("*");
        List<String> scopes = new ArrayList<>();
        client.path("defaultClientScopes").forEach(s -> scopes.add(s.asString()));
        assertThat(scopes).contains("basic", "profile", "email", "roles", "web-origins");
        JsonNode mapper = client.path("protocolMappers").get(0);
        assertThat(mapper.path("protocolMapper").asString()).isEqualTo("oidc-group-membership-mapper");
        assertThat(mapper.path("config").path("full.path").asString()).isEqualTo("true");
        assertThat(mapper.path("config").path("claim.name").asString()).isEqualTo("groups");
    }

    @ParameterizedTest
    @ValueSource(strings = {"realm.json", "realm-e2e.json"})
    void noSecretIsWrittenInTheFiles(String file) throws IOException {
        String content = text(file);
        JsonNode realm = MAPPER.readTree(content);
        assertThat(coreClient(realm).path("secret").asString()).isEqualTo("${SLG_CORE_CLIENT_SECRET}");
        for (JsonNode client : realm.path("clients")) {
            assertThat(client.path("secret").asString("${placeholder}")).as(client.path("clientId").asString()).startsWith("${");
        }
        for (JsonNode user : realm.path("users")) {
            for (JsonNode credential : user.path("credentials")) {
                assertThat(credential.path("value").asString()).isEqualTo("${E2E_USER_PASSWORD}");
            }
        }
        realm.path("components").path("org.keycloak.storage.UserStorageProvider").forEach(
                component -> assertThat(component.path("config").path("bindCredential").get(0).asString()).isEqualTo("${LDAP_BIND_PASSWORD}"));
        assertThat(content).doesNotContain("\"password\":", "\"hash\"", "\"salt\"");
    }

    @Test
    void theProductionRealmHasNoTestUsersNoTestClientAndNoTestNames() throws IOException {
        String content = text("realm.json");
        JsonNode realm = MAPPER.readTree(content);
        assertThat(realm.path("realm").asString()).isEqualTo("slg");
        assertThat(realm.has("users")).isFalse();
        assertThat(content).doesNotContain("E2E_", "slg-e2e", ".test", "localhost", "reference", "user-a", "access.token.lifespan");
        List<String> clients = new ArrayList<>();
        realm.path("clients").forEach(c -> clients.add(c.path("clientId").asString()));
        assertThat(clients).containsExactly("slg-core");
    }

    @Test
    void theE2eRealmIsSeparateAndHasTheTestUsers() throws IOException {
        JsonNode realm = realm("realm-e2e.json");
        assertThat(realm.path("realm").asString()).isEqualTo("slg-e2e");
        List<String> users = new ArrayList<>();
        realm.path("users").forEach(u -> users.add(u.path("username").asString()));
        assertThat(users).containsExactly("admin", "user-a", "user-b", "disabled");
        for (JsonNode user : realm.path("users")) {
            assertThat(user.path("enabled").asBoolean()).isEqualTo(!"disabled".equals(user.path("username").asString()));
        }
        assertThat(realm.path("users").get(0).path("groups").get(0).asString()).isEqualTo("/slg-admins");
    }

    @Test
    void theLdapFederationOfTheTestRealmIsOffUntilTheScenarioNeedsIt() throws IOException {
        JsonNode component = realm("realm-e2e.json").path("components").path("org.keycloak.storage.UserStorageProvider").get(0);
        assertThat(component.path("providerId").asString()).isEqualTo("ldap");
        assertThat(component.path("config").path("enabled").get(0).asString()).isEqualTo("false");
        assertThat(component.path("config").path("changedSyncPeriod").get(0).asString()).isEqualTo("600");
    }

    @Test
    void theProductionFederationSyncsEveryTenMinutesAndReadsOnly() throws IOException {
        JsonNode config = realm("realm.json").path("components").path("org.keycloak.storage.UserStorageProvider").get(0).path("config");
        assertThat(config.path("changedSyncPeriod").get(0).asString()).isEqualTo("600");
        assertThat(config.path("editMode").get(0).asString()).isEqualTo("READ_ONLY");
        assertThat(config.path("connectionUrl").get(0).asString()).isEqualTo("${LDAP_URL}");
    }
}
