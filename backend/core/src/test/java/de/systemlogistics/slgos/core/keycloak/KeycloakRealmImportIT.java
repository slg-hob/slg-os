package de.systemlogistics.slgos.core.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.systemlogistics.slgos.core.testsupport.KeycloakStack;
import de.systemlogistics.slgos.core.testsupport.KeycloakStack.Reply;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Import smoke test (PRD-0003 SK-15 AC3): a fresh production-mode Keycloak (`kc start --optimized --import-realm`, PostgreSQL,
 * the image of deploy/keycloak) imports both committed realm files; the settings, the clients, the built-in scopes, the
 * mapper, the locales and the lifetimes are asserted through the admin API, and the login page renders in German and English.
 * Skipped without Docker; runs on CI.
 */
class KeycloakRealmImportIT {

    private static KeycloakStack keycloak;
    private static String admin;

    @BeforeAll
    static void start() {
        assumeTrue(KeycloakStack.dockerAvailable(), "Docker is not available");
        keycloak = KeycloakStack.shared();
        admin = keycloak.adminToken();
    }

    private JsonNode get(String path) {
        Reply reply = keycloak.admin("GET", path, admin, null);
        assertThat(reply.status()).as(path).isEqualTo(200);
        return reply.json(keycloak.mapper());
    }

    private List<String> names(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.path(field).asString()));
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"slg", "slg-e2e"})
    void theRealmIsImportedWithTheProductionSettings(String realm) {
        JsonNode r = get("/" + realm);
        assertThat(r.path("enabled").asBoolean()).isTrue();
        assertThat(r.path("sslRequired").asString()).isEqualTo("all");
        assertThat(r.path("registrationAllowed").asBoolean(true)).isFalse();
        assertThat(r.path("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(r.path("ssoSessionIdleTimeout").asInt()).isGreaterThanOrEqualTo(1800);
        assertThat(r.path("ssoSessionMaxLifespan").asInt()).isGreaterThanOrEqualTo(36000);
        assertThat(r.path("revokeRefreshToken").asBoolean()).isTrue();
        assertThat(r.path("bruteForceProtected").asBoolean()).isTrue();
        assertThat(r.path("internationalizationEnabled").asBoolean()).isTrue();
        assertThat(r.path("defaultLocale").asString()).isEqualTo("de");
        List<String> locales = new ArrayList<>();
        r.path("supportedLocales").forEach(l -> locales.add(l.asString()));
        assertThat(locales).containsExactlyInAnyOrder("de", "en");
    }

    @ParameterizedTest
    @ValueSource(strings = {"slg", "slg-e2e"})
    void theBuiltInClientScopesSurviveTheImport(String realm) {
        assertThat(names(get("/" + realm + "/client-scopes"), "name")).contains("basic", "profile", "email", "roles", "web-origins");
    }

    @ParameterizedTest
    @ValueSource(strings = {"slg", "slg-e2e"})
    void theCoreClientHasPkceTheGroupMapperAndTheSubstitutedSecret(String realm) {
        JsonNode clients = get("/" + realm + "/clients?clientId=slg-core");
        assertThat(clients).hasSize(1);
        JsonNode client = clients.get(0);
        assertThat(client.path("publicClient").asBoolean(true)).isFalse();
        assertThat(client.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("webOrigins")).isEmpty();
        assertThat(client.path("redirectUris").get(0).asString()).isEqualTo("https://shell.slg.test/core/auth/callback");
        assertThat(client.path("attributes").path("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(client.path("attributes").path("backchannel.logout.url").asString())
                .isEqualTo("http://core-backend:8080/core/internal/backchannel-logout");
        assertThat(names(client.path("protocolMappers"), "protocolMapper")).contains("oidc-group-membership-mapper");
        JsonNode secret = get("/" + realm + "/clients/" + client.path("id").asString() + "/client-secret");
        assertThat(secret.path("value").asString()).as("the placeholder was replaced from the environment")
                .isEqualTo(KeycloakStack.CLIENT_SECRET);
        assertThat(names(get("/" + realm + "/clients/" + client.path("id").asString() + "/default-client-scopes"), "name"))
                .contains("basic", "profile", "email", "roles", "web-origins");
    }

    @Test
    void theTestRealmHasTheGeneratedUsersAndGroupsAndTheProductionRealmHasNone() {
        assertThat(names(get("/slg-e2e/users?max=20"), "username")).containsExactlyInAnyOrder("admin", "user-a", "user-b", "disabled");
        assertThat(names(get("/slg-e2e/groups"), "name")).containsExactlyInAnyOrder("slg-admins", "slg-users");
        assertThat(get("/slg/users?max=20")).isEmpty();
        JsonNode disabled = get("/slg-e2e/users?username=disabled&exact=true").get(0);
        assertThat(disabled.path("enabled").asBoolean(true)).isFalse();
    }

    @Test
    void theLdapFederationIsPresentAndOnlyEnabledInTheProductionRealm() {
        JsonNode prod = get("/slg/components?type=org.keycloak.storage.UserStorageProvider");
        assertThat(prod).hasSize(1);
        assertThat(prod.get(0).path("config").path("connectionUrl").get(0).asString()).isEqualTo("ldap://directory.example.internal:389");
        assertThat(prod.get(0).path("config").path("changedSyncPeriod").get(0).asString()).isEqualTo("600");
        JsonNode e2e = get("/slg-e2e/components?type=org.keycloak.storage.UserStorageProvider");
        assertThat(e2e.get(0).path("config").path("enabled").get(0).asString()).isEqualTo("false");
    }

    private String loginPage(String realm, String query, String language) {
        Reply reply = keycloak.send("GET", "/realms/" + realm + "/protocol/openid-connect/auth?client_id=slg-core&response_type=code"
                + "&scope=openid&redirect_uri=https%3A%2F%2Fshell.slg.test%2Fcore%2Fauth%2Fcallback&state=s&nonce=n&code_challenge=" + "a".repeat(43)
                + "&code_challenge_method=S256" + query, language == null ? Map.of() : Map.of("Accept-Language", language), null);
        assertThat(reply.status()).isEqualTo(200);
        return reply.body();
    }

    @Test
    void theLoginPageRendersInGermanAndInEnglish() {
        String german = loginPage("slg-e2e", "", "de-DE");
        assertThat(german).contains("lang=\"de\"").contains("Anmelden");
        String english = loginPage("slg-e2e", "", "en-US");
        assertThat(english).contains("lang=\"en\"").contains("Sign in");
        assertThat(loginPage("slg-e2e", "", null)).as("the realm default is German").contains("lang=\"de\"");
    }

    @Test
    void aRedirectUriOutsideTheConfiguredOneIsRefused() {
        Reply reply = keycloak.send("GET", "/realms/slg-e2e/protocol/openid-connect/auth?client_id=slg-core&response_type=code&scope=openid"
                + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcb&state=s&code_challenge=" + "a".repeat(43) + "&code_challenge_method=S256",
                Map.of(), null);
        assertThat(reply.status()).isEqualTo(400);
    }

    @Test
    void theAuthorizationRequestWithoutPkceIsRefused() {
        Reply reply = keycloak.send("GET", "/realms/slg-e2e/protocol/openid-connect/auth?client_id=slg-core&response_type=code&scope=openid"
                + "&redirect_uri=https%3A%2F%2Fshell.slg.test%2Fcore%2Fauth%2Fcallback&state=s", Map.of(), null);
        assertThat(reply.status() == 302 && String.valueOf(reply.headers().get("location")).contains("error=")
                || reply.status() == 400 || reply.body().contains("Missing parameter: code_challenge_method")).isTrue();
    }
}
