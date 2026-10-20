package de.systemlogistics.slgos.core.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.systemlogistics.slgos.core.testsupport.KeycloakStack;
import de.systemlogistics.slgos.core.testsupport.KeycloakStack.Reply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decision experiment of PRD-0003 SK-19 (Q26): which mechanism gives the gateway an audience-bound token per module, on
 * production-mode Keycloak with the confidential {@code slg-core} client?
 * (a) standard token exchange v2, (b) refresh-token down-scoping with serialised grants.
 * The test records what each mechanism does (status, error, claims, lifetime, rotation, concurrency) in
 * {@code target/sk19-token-mechanism.json} and in the build log, and asserts the invariants the decision rests on. The
 * outcome is written up in ADR-0016. Skipped without Docker; runs on CI.
 */
class TokenMechanismExperimentIT {

    private static final String REALM = KeycloakStack.E2E_REALM;
    private static final String TOKEN = "/realms/" + REALM + "/protocol/openid-connect/token";
    private static final String CORE_AUTH = KeycloakStack.basic("slg-core", KeycloakStack.CLIENT_SECRET);

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void recordsWhatTokenExchangeAndDownScopingDo() throws Exception {
        assumeTrue(KeycloakStack.dockerAvailable(), "Docker is not available");
        KeycloakStack keycloak = KeycloakStack.shared();
        String admin = keycloak.adminToken();
        String coreId = clientId(keycloak, admin, "slg-core");
        try {
            experiment(keycloak, admin, coreId);
        } finally {
            // The Keycloak instance is shared with the other tests: put the client back as the realm file defines it.
            JsonNode rep = keycloak.admin("GET", "/" + REALM + "/clients/" + coreId, admin, null).json(mapper);
            ((tools.jackson.databind.node.ObjectNode) rep).put("directAccessGrantsEnabled", false);
            keycloak.admin("PUT", "/" + REALM + "/clients/" + coreId, admin, mapper.writeValueAsString(rep));
        }
    }

    private String login(KeycloakStack keycloak, String scope) {
        Reply login = keycloak.postForm(TOKEN, Map.of("grant_type", "password", "username", "user-a",
                "password", KeycloakStack.E2E_PASSWORD, "scope", scope), CORE_AUTH);
        assertThat(login.status()).as("password grant for the experiment").isEqualTo(200);
        return login.json(mapper).path("refresh_token").asString();
    }

    private void experiment(KeycloakStack keycloak, String admin, String coreId) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();

        // Preparation through the admin API, as the provisioner will do it per module: an audience scope on the core client.
        JsonNode coreRep = keycloak.admin("GET", "/" + REALM + "/clients/" + coreId, admin, null).json(mapper);
        ((tools.jackson.databind.node.ObjectNode) coreRep).put("directAccessGrantsEnabled", true);
        assertThat(keycloak.admin("PUT", "/" + REALM + "/clients/" + coreId, admin, mapper.writeValueAsString(coreRep)).status())
                .isEqualTo(204);
        String scopeBody = "{\"name\":\"reference\",\"protocol\":\"openid-connect\",\"attributes\":{\"include.in.token.scope\":\"true\"},"
                + "\"protocolMappers\":[{\"name\":\"audience-reference\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\","
                + "\"config\":{\"included.client.audience\":\"reference\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\","
                + "\"introspection.token.claim\":\"true\"}}]}";
        Reply created = keycloak.admin("POST", "/" + REALM + "/client-scopes", admin, scopeBody);
        assertThat(created.status()).isEqualTo(201);
        String location = created.headers().get("location").get(0);
        String scopeId = location.substring(location.lastIndexOf('/') + 1);
        assertThat(keycloak.admin("PUT", "/" + REALM + "/clients/" + coreId + "/optional-client-scopes/" + scopeId, admin, null).status())
                .isEqualTo(204);

        // The user's tokens as the core holds them after the authorization code flow.
        Reply login = keycloak.postForm(TOKEN, Map.of("grant_type", "password", "username", "user-a",
                "password", KeycloakStack.E2E_PASSWORD, "scope", "openid"), CORE_AUTH);
        assertThat(login.status()).as("password grant for the experiment").isEqualTo(200);
        JsonNode user = login.json(mapper);
        String accessToken = user.path("access_token").asString();
        String refreshToken = user.path("refresh_token").asString();
        report.put("userAccessToken", describe(accessToken, 0));

        // (a) standard token exchange v2
        List<Map<String, Object>> exchange = new ArrayList<>();
        for (boolean withScope : new boolean[] {false, true}) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
            form.put("subject_token", accessToken);
            form.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
            form.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
            form.put("audience", "reference");
            if (withScope) {
                form.put("scope", "reference");
            }
            exchange.add(outcome(keycloak.postForm(TOKEN, form, CORE_AUTH), "withScope=" + withScope));
        }
        report.put("a_tokenExchangeV2", exchange);

        // (b) refresh-token down-scoping: the response rotates the refresh token, grants must be serialised.
        Map<String, String> down = new LinkedHashMap<>();
        down.put("grant_type", "refresh_token");
        // A refresh grant can only narrow the scopes of the original login, so the login names the module scope.
        down.put("refresh_token", login(keycloak, "openid reference"));
        down.put("scope", "reference");
        Map<String, String> notGranted = new LinkedHashMap<>(down);
        notGranted.put("refresh_token", refreshToken);
        Reply plain = keycloak.postForm(TOKEN, notGranted, CORE_AUTH);
        Map<String, Object> plainOutcome = outcome(plain, "login without the module scope, refresh asks for it");
        report.put("b0_scopeNotInTheOriginalLogin", plainOutcome);
        Reply first = keycloak.postForm(TOKEN, down, CORE_AUTH);
        Map<String, Object> firstOutcome = outcome(first, "scope=reference");
        report.put("b_downScoping_first", firstOutcome);
        assertThat(first.status()).as("down-scoping by refresh grant works").isEqualTo(200);
        JsonNode firstJson = first.json(mapper);
        String rotated = firstJson.path("refresh_token").asString();
        assertThat(rotated).as("the refresh token rotates").isNotEqualTo(refreshToken);
        JWTClaimsSet scoped = claims(firstJson.path("access_token").asString());
        assertThat(scoped.getAudience()).as("audience of the module token").contains("reference");
        assertThat(String.valueOf(scoped.getClaim("azp"))).isEqualTo("slg-core");

        Reply reuse = keycloak.postForm(TOKEN, down, CORE_AUTH);
        report.put("b_downScoping_reuseOfRotatedToken", Map.of("status", reuse.status(), "error", errorOf(reuse)));

        Map<String, String> second = new LinkedHashMap<>(down);
        second.put("refresh_token", rotated);
        Reply serial = keycloak.postForm(TOKEN, second, CORE_AUTH);
        report.put("b_downScoping_serialisedSecondGrant", Map.of("status", serial.status()));
        String current = serial.status() == 200 ? serial.json(mapper).path("refresh_token").asString() : null;

        if (current != null) {
            Map<String, String> concurrent = new LinkedHashMap<>(down);
            concurrent.put("refresh_token", current);
            CompletableFuture<Reply> one = CompletableFuture.supplyAsync(() -> keycloak.postForm(TOKEN, concurrent, CORE_AUTH));
            CompletableFuture<Reply> two = CompletableFuture.supplyAsync(() -> keycloak.postForm(TOKEN, concurrent, CORE_AUTH));
            int successes = (one.get().status() == 200 ? 1 : 0) + (two.get().status() == 200 ? 1 : 0);
            report.put("b_downScoping_concurrentGrantsWithOneToken", Map.of("successes", successes,
                    "statuses", List.of(one.get().status(), two.get().status())));
            assertThat(successes).as("two concurrent grants must not both succeed").isLessThanOrEqualTo(1);
        }

        String rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("sk19-token-mechanism.json"), rendered, StandardCharsets.UTF_8);
        System.out.println("SK19-RESULT-BEGIN\n" + rendered + "\nSK19-RESULT-END");
    }

    private String clientId(KeycloakStack keycloak, String admin, String clientId) {
        return keycloak.admin("GET", "/" + REALM + "/clients?clientId=" + clientId, admin, null).json(mapper).get(0).path("id").asString();
    }

    private Map<String, Object> outcome(Reply reply, String label) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("variant", label);
        result.put("status", reply.status());
        if (reply.status() == 200) {
            JsonNode body = reply.json(mapper);
            result.put("tokenType", body.path("issued_token_type").asString(body.path("token_type").asString("")));
            result.put("hasRefreshToken", body.has("refresh_token"));
            result.put("expiresIn", body.path("expires_in").asInt());
            result.put("accessToken", describe(body.path("access_token").asString(), 0));
        } else {
            result.put("error", errorOf(reply));
        }
        return result;
    }

    private String errorOf(Reply reply) {
        try {
            JsonNode body = reply.json(mapper);
            return body.path("error").asString("") + ": " + body.path("error_description").asString("");
        } catch (RuntimeException e) {
            return "unparseable";
        }
    }

    private JWTClaimsSet claims(String token) throws ParseException {
        return SignedJWT.parse(token).getJWTClaimsSet();
    }

    private Map<String, Object> describe(String token, int unused) {
        try {
            JWTClaimsSet c = claims(token);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("aud", c.getAudience());
            d.put("azp", c.getClaim("azp"));
            d.put("scope", c.getClaim("scope"));
            d.put("sub", c.getSubject() == null ? null : "present");
            d.put("lifetimeSeconds", (c.getExpirationTime().getTime() - c.getIssueTime().getTime()) / 1000);
            d.put("hasGroups", c.getClaim("groups") != null);
            return d;
        } catch (ParseException | RuntimeException e) {
            return Map.of("opaque", true);
        }
    }
}
