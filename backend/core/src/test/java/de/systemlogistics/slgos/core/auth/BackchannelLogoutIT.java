package de.systemlogistics.slgos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import de.systemlogistics.slgos.core.testsupport.Browser;
import de.systemlogistics.slgos.core.testsupport.FakeKeycloak;
import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;

/** Back-channel logout from Keycloak (PRD-0003 SK-3 AC5): validation, replay and the effect on the session. */
class BackchannelLogoutIT extends IntegrationTestBase {

    private static final AtomicInteger USERS = new AtomicInteger();

    private Browser browser;

    @BeforeEach
    void browser() {
        browser = new Browser(mvc, ENV.keycloak, ENV.clock);
    }

    private static String newUser() {
        return "bc-user-" + USERS.incrementAndGet() + "-" + System.nanoTime();
    }

    private MockHttpServletResponse logout(String host, String token) throws Exception {
        return mvc.perform(post("/core/internal/backchannel-logout").header("Host", host)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", token)).andReturn().getResponse();
    }

    private MockHttpServletResponse session() throws Exception {
        return mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse();
    }

    private List<String> reasons(String sub) {
        return jdbc.sql("SELECT reason FROM audit_event WHERE event_type = 'session.terminated' AND actor_id = ? ORDER BY id")
                .param(sub).query(String.class).list();
    }

    @Test
    void aValidLogoutTokenEndsTheSessionImmediately() throws Exception {
        String sub = newUser();
        Browser.Session me = browser.login(sub);
        assertThat(session().getStatus()).isEqualTo(200);
        MockHttpServletResponse response = logout("core-backend:8080", ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build()));
        assertThat(response.getStatus()).isEqualTo(200);
        MockHttpServletResponse after = session();
        assertThat(after.getStatus()).isEqualTo(401);
        assertThat(after.getContentAsString()).contains("SESSION_EXPIRED");
        assertThat(reasons(sub)).containsExactly("backchannel_logout");
    }

    @Test
    void onlyTheSessionsOfThatKeycloakSessionEnd() throws Exception {
        String sub = newUser();
        Browser.Session first = browser.login(sub);
        Browser other = new Browser(mvc, ENV.keycloak, ENV.clock);
        other.login(sub);
        assertThat(logout("core-backend", ENV.keycloak.sign(ENV.keycloak.logoutClaims(first.keycloakSid(), null).build())).getStatus())
                .isEqualTo(200);
        assertThat(session().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(other.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aReplayedTokenIsRejected() throws Exception {
        Browser.Session me = browser.login(newUser());
        String token = ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build());
        assertThat(logout("core-backend", token).getStatus()).isEqualTo(200);
        assertThat(logout("core-backend", token).getStatus()).isEqualTo(400);
    }

    @Test
    void forgedExpiredAndWrongAudienceTokensAreRejectedAndDoNotEndAnything() throws Exception {
        String sub = newUser();
        Browser.Session me = browser.login(sub);
        RSAKey attacker = FakeKeycloak.generateKey("test-key");
        String[] bad = {
                FakeKeycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build(), attacker),
                ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).audience("other-client").build()),
                ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null)
                        .issueTime(Date.from(ENV.clock.instant().minus(Duration.ofMinutes(10)))).build()),
                ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).claim("nonce", "x").build()),
                ENV.keycloak.sign(new JWTClaimsSet.Builder(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build())
                        .claim("events", null).build()),
                ENV.keycloak.sign(ENV.keycloak.idClaims(sub, "n").claim("sid", me.keycloakSid()).build()),
                "not-a-jwt", ""};
        for (String token : bad) {
            assertThat(logout("core-backend", token).getStatus()).as(token.length() > 20 ? token.substring(0, 20) : token).isEqualTo(400);
        }
        assertThat(session().getStatus()).as("the session is untouched").isEqualTo(200);
    }

    @Test
    void anUnknownSessionAnswers200WithoutEffectOnOthers() throws Exception {
        Browser.Session me = browser.login(newUser());
        assertThat(logout("core-backend", ENV.keycloak.sign(ENV.keycloak.logoutClaims("sid-nobody-has", null).build())).getStatus())
                .isEqualTo(200);
        assertThat(session().getStatus()).isEqualTo(200);
        assertThat(me.csrf()).isNotBlank();
    }

    @Test
    void aMissingParameterOrWrongContentTypeIsRejected() throws Exception {
        assertThat(mvc.perform(post("/core/internal/backchannel-logout").header("Host", "core-backend")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/core/internal/backchannel-logout").header("Host", "core-backend")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isBetween(400, 415);
        assertThat(logout("core-backend", "x".repeat(9000)).getStatus()).isEqualTo(400);
    }

    @Test
    void theEndpointIsNotReachableOnThePublicHostName() throws Exception {
        Browser.Session me = browser.login(newUser());
        String token = ENV.keycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build());
        assertThat(logout(Browser.HOST, token).getStatus()).isEqualTo(404);
        assertThat(logout("evil.example", token).getStatus()).isEqualTo(421);
        assertThat(session().getStatus()).isEqualTo(200);
    }

    @Test
    void anUnreachableKeySetAnswers503SoKeycloakRetries() throws Exception {
        Browser.Session me = browser.login(newUser());
        // a token signed with a key the core has not seen yet forces a key fetch; with Keycloak down that is a retryable failure
        RSAKey rotated = FakeKeycloak.generateKey("rotated-key");
        ENV.clock.advance(Duration.ofMinutes(2));
        ENV.keycloak.stop();
        try {
            String token = FakeKeycloak.sign(ENV.keycloak.logoutClaims(me.keycloakSid(), null).build(), rotated);
            assertThat(logout("core-backend", token).getStatus()).isEqualTo(503);
        } finally {
            ENV.keycloak.restart();
        }
    }
}
