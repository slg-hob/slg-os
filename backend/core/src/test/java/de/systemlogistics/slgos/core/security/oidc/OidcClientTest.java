package de.systemlogistics.slgos.core.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import de.systemlogistics.slgos.core.testsupport.FakeKeycloak;
import de.systemlogistics.slgos.core.testsupport.MutableClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OidcClientTest {

    private MutableClock clock;
    private FakeKeycloak keycloak;
    private OidcClient client;

    private OidcClient clientFor(String issuer, Duration timeout, String secret) {
        return new OidcClient(new OidcEndpoints(issuer), FakeKeycloak.CLIENT_ID, secret, timeout, JsonMapper.builder().build());
    }

    @BeforeEach
    void setUp() {
        clock = MutableClock.atNoon();
        keycloak = new FakeKeycloak(clock::instant);
        client = clientFor(keycloak.issuer(), Duration.ofSeconds(3), FakeKeycloak.CLIENT_SECRET);
    }

    @AfterEach
    void tearDown() {
        keycloak.close();
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(p -> p.split("=", 2)).collect(Collectors.toMap(
                p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8), p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    private String code(String sub, String verifier) {
        return keycloak.issueCode(keycloak.user(sub), "n-1", Pkce.challenge(verifier), clock.instant());
    }

    @Test
    void endpointsFollowKeycloaksPathsAndDropATrailingSlash() {
        OidcEndpoints endpoints = new OidcEndpoints("https://sso.slg.test/realms/slg/");
        assertThat(endpoints.issuer()).isEqualTo("https://sso.slg.test/realms/slg");
        assertThat(endpoints.authorization()).isEqualTo("https://sso.slg.test/realms/slg/protocol/openid-connect/auth");
        assertThat(endpoints.token()).endsWith("/protocol/openid-connect/token");
        assertThat(endpoints.jwks()).endsWith("/protocol/openid-connect/certs");
        assertThat(endpoints.logout()).endsWith("/protocol/openid-connect/logout");
    }

    @Test
    void authorizationUrlCarriesPkceStateNonceAndLocale() {
        URI uri = client.authorizationUri("https://shell.slg.test/core/auth/callback", "st", "no", "ch", "en", false);
        Map<String, String> q = query(uri);
        assertThat(uri.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/auth?");
        assertThat(q).containsEntry("client_id", "slg-core").containsEntry("response_type", "code")
                .containsEntry("redirect_uri", "https://shell.slg.test/core/auth/callback").containsEntry("state", "st")
                .containsEntry("nonce", "no").containsEntry("code_challenge", "ch").containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "openid profile email").containsEntry("ui_locales", "en")
                .doesNotContainKeys("prompt", "max_age");
    }

    @Test
    void stepUpForcesANewAuthentication() {
        Map<String, String> q = query(client.authorizationUri("https://s/cb", "st", "no", "ch", "de", true));
        assertThat(q).containsEntry("prompt", "login").containsEntry("max_age", "0");
    }

    @Test
    void endSessionUrlCarriesTheHintAndTheRedirect() {
        Map<String, String> q = query(client.endSessionUri("the-id-token", "https://shell.slg.test/core/auth/signed-out"));
        assertThat(q).containsEntry("id_token_hint", "the-id-token").containsEntry("client_id", "slg-core")
                .containsEntry("post_logout_redirect_uri", "https://shell.slg.test/core/auth/signed-out");
    }

    @Test
    void exchangesACodeOnceAndRotatesTheRefreshToken() {
        String verifier = Pkce.randomValue();
        String code = code("alice", verifier);
        OidcTokens tokens = client.exchangeCode(code, verifier, "https://shell.slg.test/core/auth/callback");
        assertThat(tokens.accessToken()).startsWith("access-");
        assertThat(tokens.refreshToken()).startsWith("refresh-");
        assertThat(tokens.idToken().split("\\.")).hasSize(3);
        assertThat(tokens.expiresInSeconds()).isEqualTo(300);
        assertThatThrownBy(() -> client.exchangeCode(code, verifier, "x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));

        OidcTokens renewed = client.refresh(tokens.refreshToken());
        assertThat(renewed.refreshToken()).isNotEqualTo(tokens.refreshToken());
        assertThatThrownBy(() -> client.refresh(tokens.refreshToken())).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).as("reuse of a rotated token").isEqualTo(Kind.REJECTED));
    }

    @Test
    void pkceVerifierMustMatch() {
        String code = code("bob", Pkce.randomValue());
        assertThatThrownBy(() -> client.exchangeCode(code, Pkce.randomValue(), "x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
    }

    @Test
    void aWrongClientSecretIsRejected() {
        OidcClient wrong = clientFor(keycloak.issuer(), Duration.ofSeconds(3), "wrong");
        assertThatThrownBy(() -> wrong.refresh("x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
    }

    @Test
    void serverErrorsMeanUnavailableBecauseNothingWasConsumed() {
        keycloak.failRefreshWith(503);
        assertThatThrownBy(() -> client.refresh("x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void aStoppedServerMeansUnavailable() {
        keycloak.stop();
        assertThat(client.reachable()).isFalse();
        assertThatThrownBy(() -> client.refresh("x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        keycloak.restart();
        assertThat(client.reachable()).isTrue();
    }

    @Test
    void aSlowAnswerIsALostResponse() {
        keycloak.delayRefresh(Duration.ofMillis(1500));
        OidcClient impatient = clientFor(keycloak.issuer(), Duration.ofMillis(300), FakeKeycloak.CLIENT_SECRET);
        assertThatThrownBy(() -> impatient.refresh("x")).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.LOST_RESPONSE));
    }

    @Test
    void aDroppedConnectionAfterTheRequestIsALostResponse() {
        String verifier = Pkce.randomValue();
        OidcTokens tokens = client.exchangeCode(code("carol", verifier), verifier, "x");
        keycloak.dropNextRefreshResponse();
        assertThatThrownBy(() -> client.refresh(tokens.refreshToken())).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.LOST_RESPONSE));
        assertThat(keycloak.refreshGrants.get()).as("the token was consumed on the server").isEqualTo(1);
    }

    @Test
    void anIncompleteOrMalformedTokenResponseIsRejected() throws Exception {
        for (String body : new String[] {"{\"access_token\":\"a\"}", "not json", "{\"access_token\":\"a\",\"refresh_token\":\"\",\"id_token\":\"i\"}"}) {
            HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            stub.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            stub.start();
            try {
                OidcClient stubbed = clientFor("http://localhost:" + stub.getAddress().getPort() + "/realms/x", Duration.ofSeconds(3), "s");
                assertThatThrownBy(() -> stubbed.refresh("r")).isInstanceOfSatisfying(OidcException.class,
                        e -> assertThat(e.kind()).as(body).isEqualTo(Kind.REJECTED));
            } finally {
                stub.stop(0);
            }
        }
    }

    @Test
    void tokenValuesNeverShowInToString() {
        assertThat(new OidcTokens("secret-access", "secret-refresh", "secret-id", 5).toString()).doesNotContain("secret");
    }

    @Test
    void pkceChallengeFollowsRfc7636() {
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertThat(Pkce.randomValue()).hasSize(43).isNotEqualTo(Pkce.randomValue());
    }
}
