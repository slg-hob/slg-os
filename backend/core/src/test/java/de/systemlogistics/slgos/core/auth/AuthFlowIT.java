package de.systemlogistics.slgos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.systemlogistics.slgos.core.health.OidcAvailability;
import de.systemlogistics.slgos.core.session.SessionIds;
import de.systemlogistics.slgos.core.testsupport.Browser;
import de.systemlogistics.slgos.core.testsupport.FakeKeycloak;
import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** The sign-in, session, sign-out and CSRF behaviour of PRD-0003 SK-3 and SK-4 through the whole core. */
class AuthFlowIT extends IntegrationTestBase {

    private static final AtomicInteger USERS = new AtomicInteger();

    @Autowired
    OidcAvailability availability;

    private Browser browser;

    @BeforeEach
    void browser() {
        browser = new Browser(mvc, ENV.keycloak, ENV.clock);
    }

    @AfterEach
    void restore() {
        availability.report(true);
    }

    private static String newUser() {
        return "user-" + USERS.incrementAndGet() + "-" + System.nanoTime();
    }

    private long audit(String type, String actor) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = ? AND actor_id = ?").params(type, actor)
                .query(Long.class).single();
    }

    private List<String> terminations(String actor) {
        return jdbc.sql("SELECT reason FROM audit_event WHERE event_type = 'session.terminated' AND actor_id = ? ORDER BY id")
                .param(actor).query(String.class).list();
    }

    // ---- login redirect

    @Test
    void loginRedirectsToKeycloakWithPkceAndSetsASignedPreLoginCookie() throws Exception {
        MockHttpServletResponse response = mvc.perform(browser.shell(get("/core/auth/login")
                .param("return", "/app/reference/notes").header("Accept-Language", "en-GB,en;q=0.9"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        URI location = URI.create(response.getHeader("Location"));
        assertThat(location.toString()).startsWith(ENV.keycloak.issuer() + "/protocol/openid-connect/auth?");
        Map<String, String> q = Browser.query(location.getRawQuery());
        assertThat(q).containsEntry("client_id", "slg-core").containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256").containsEntry("ui_locales", "en")
                .containsEntry("redirect_uri", "https://shell.slg.test/core/auth/callback");
        assertThat(q.get("state")).hasSize(43);
        assertThat(q.get("nonce")).hasSize(43).isNotEqualTo(q.get("state"));
        assertThat(q.get("code_challenge")).hasSize(43);
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("__Host-slg-login=").contains("Max-Age=600", "SameSite=Lax", "HttpOnly", "Secure", "Path=/")
                .doesNotContain("Domain", q.get("code_challenge"));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void loginDefaultsTheLocaleToGermanAndEveryLoginUsesNewRandomValues() throws Exception {
        Map<String, String> first = browser.startLogin("");
        Map<String, String> second = browser.startLogin("");
        assertThat(first.get("ui_locales")).isEqualTo("de");
        assertThat(first.get("state")).isNotEqualTo(second.get("state"));
        assertThat(first.get("nonce")).isNotEqualTo(second.get("nonce"));
    }

    @Test
    void loginIsRateLimitedPerClient() throws Exception {
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            MockHttpServletResponse response = mvc.perform(browser.shell(get("/core/auth/login")
                    .with(r -> { r.setRemoteAddr("203.0.113." + 77); return r; }))).andReturn().getResponse();
            if (response.getStatus() == 429) {
                limited++;
                assertThat(response.getContentAsString()).contains("role=\"alert\"");
            }
        }
        assertThat(limited).isEqualTo(10);
    }

    // ---- callback

    @Test
    void theCallbackAnswers200WithAnInterstitialAndIssuesAHardenedSessionCookie() throws Exception {
        String sub = newUser();
        FakeKeycloak.User user = ENV.keycloak.user(sub, "/slg/users");
        Map<String, String> request = browser.startLogin("?return=/app/reference/notes");
        MockHttpServletResponse response = browser.callback(request, user, ENV.clock.instant());

        assertThat(response.getStatus()).as("a 200 document, not a 302 (SameSite=Strict)").isEqualTo(200);
        assertThat(response.getContentAsString()).contains("<meta http-equiv=\"refresh\" content=\"0;url=/app/reference/notes\">",
                "<a href=\"/app/reference/notes\">");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'", "frame-ancestors 'none'");

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        String session = cookies.stream().filter(c -> c.startsWith("__Host-slg-session=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/").doesNotContain("Domain", "Max-Age", "Expires");
        assertThat(cookies.stream().filter(c -> c.startsWith("__Host-slg-login=")).findFirst().orElseThrow())
                .as("the pre-login cookie is deleted").contains("Max-Age=0");

        String id = browser.cookie("__Host-slg-session");
        assertThat(SessionIds.isWellFormed(id)).isTrue();
        assertThat(audit("auth.login", sub)).isEqualTo(1);
    }

    @Test
    void onlyTheHashOfTheSessionIdAndEncryptedTokensAreStored() throws Exception {
        String sub = newUser();
        browser.login(sub);
        String id = browser.cookie("__Host-slg-session");
        Map<String, Object> row = jdbc.sql("SELECT * FROM session WHERE user_id = ?").param(sub).query().singleRow();
        assertThat((byte[]) row.get("id_hash")).isEqualTo(SessionIds.hash(id)).isNotEqualTo(id.getBytes(StandardCharsets.UTF_8));
        for (String column : new String[] {"refresh_token_ct", "access_token_ct", "id_token_ct"}) {
            String stored = new String((byte[]) row.get(column), StandardCharsets.ISO_8859_1);
            assertThat(stored).as(column).doesNotContain("refresh-", "access-", "eyJ");
        }
        assertThat(row.get("vault_key_id")).isEqualTo("k1");
        assertThat(jdbc.sql("SELECT count(*) FROM session WHERE id_hash = ?").param(id.getBytes(StandardCharsets.UTF_8))
                .query(Long.class).single()).isZero();
    }

    @Test
    void thePopupFlagLeadsToThePopupDonePage() throws Exception {
        FakeKeycloak.User user = ENV.keycloak.user(newUser());
        Map<String, String> request = browser.startLogin("?popup=1&return=/app/reference");
        MockHttpServletResponse response = browser.callback(request, user, ENV.clock.instant());
        assertThat(response.getContentAsString()).contains("content=\"0;url=/core/auth/popup-done\"");
    }

    @Test
    void aMaliciousReturnTargetBecomesTheRoot() throws Exception {
        for (String target : new String[] {"//evil.example/app/x", "https://evil.example", "/app/../admin", "/app/x%252e%252e/y"}) {
            browser.clearCookies();
            Map<String, String> request = browser.startLogin("?return=" + java.net.URLEncoder.encode(target, StandardCharsets.UTF_8));
            String body = browser.callback(request, ENV.keycloak.user(newUser()), ENV.clock.instant()).getContentAsString();
            assertThat(body).as(target).contains("content=\"0;url=/\"").doesNotContain("evil.example");
        }
    }

    @Test
    void aReplayedCallbackFails() throws Exception {
        String sub = newUser();
        FakeKeycloak.User user = ENV.keycloak.user(sub);
        Map<String, String> request = browser.startLogin("");
        String loginCookie = browser.cookie("__Host-slg-login");
        String code = ENV.keycloak.issueCode(user, request.get("nonce"), request.get("code_challenge"), ENV.clock.instant());
        MockHttpServletResponse first = mvc.perform(browser.shell(get("/core/auth/callback").param("code", code)
                .param("state", request.get("state")))).andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);
        // An attacker who captured the URL and the pre-login cookie replays both.
        browser.setCookie("__Host-slg-login", loginCookie);
        String code2 = ENV.keycloak.issueCode(user, request.get("nonce"), request.get("code_challenge"), ENV.clock.instant());
        MockHttpServletResponse replay = mvc.perform(browser.shell(get("/core/auth/callback").param("code", code2)
                .param("state", request.get("state")))).andReturn().getResponse();
        assertThat(replay.getStatus()).isEqualTo(400);
        assertThat(replay.getContentAsString()).contains("role=\"alert\"");
        assertThat(audit("auth.login", sub)).isEqualTo(1);
    }

    @Test
    void theCallbackRejectsMissingForgedAndMismatchedState() throws Exception {
        FakeKeycloak.User user = ENV.keycloak.user(newUser());
        // no pre-login cookie at all
        Map<String, String> request = browser.startLogin("");
        browser.clearCookies();
        String code = ENV.keycloak.issueCode(user, request.get("nonce"), request.get("code_challenge"), ENV.clock.instant());
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback").param("code", code).param("state", request.get("state"))))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // state that does not match the cookie
        Map<String, String> second = browser.startLogin("");
        String code2 = ENV.keycloak.issueCode(user, second.get("nonce"), second.get("code_challenge"), ENV.clock.instant());
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback").param("code", code2).param("state", "other-state")))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // forged cookie
        browser.setCookie("__Host-slg-login", "a.b.c.d.00." + ENV.clock.instant().getEpochSecond() + ".AAAA");
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback").param("code", code2).param("state", "a")))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // an error from the provider, and missing parameters
        Map<String, String> third = browser.startLogin("");
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback").param("error", "access_denied")
                .param("state", third.get("state")))).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback"))).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aTokenForAnotherNonceOrCodeIsRefused() throws Exception {
        FakeKeycloak.User user = ENV.keycloak.user(newUser());
        Map<String, String> request = browser.startLogin("");
        String code = ENV.keycloak.issueCode(user, "someone-elses-nonce", request.get("code_challenge"), ENV.clock.instant());
        MockHttpServletResponse response = mvc.perform(browser.shell(get("/core/auth/callback").param("code", code)
                .param("state", request.get("state")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeaders("Set-Cookie").stream().anyMatch(c -> c.startsWith("__Host-slg-session="))).isFalse();
        // unknown code
        Map<String, String> next = browser.startLogin("");
        assertThat(mvc.perform(browser.shell(get("/core/auth/callback").param("code", "unknown")
                .param("state", next.get("state")))).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aMissingKeycloakShowsTheSignInUnavailablePageAndRecovers() throws Exception {
        Map<String, String> request = browser.startLogin("");
        FakeKeycloak.User user = ENV.keycloak.user(newUser());
        String code = ENV.keycloak.issueCode(user, request.get("nonce"), request.get("code_challenge"), ENV.clock.instant());
        ENV.keycloak.stop();
        try {
            MockHttpServletResponse callback = mvc.perform(browser.shell(get("/core/auth/callback").param("code", code)
                    .param("state", request.get("state")))).andReturn().getResponse();
            assertThat(callback.getStatus()).isEqualTo(503);
            assertThat(callback.getContentAsString()).contains("role=\"alert\"", "Anmeldung nicht verfügbar");
            assertThat(availability.isAvailable()).isFalse();
            MockHttpServletResponse login = mvc.perform(browser.shell(get("/core/auth/login")
                    .header("Accept-Language", "en"))).andReturn().getResponse();
            assertThat(login.getStatus()).isEqualTo(503);
            assertThat(login.getContentAsString()).contains("Sign-in unavailable", "<html lang=\"en\">");
        } finally {
            ENV.keycloak.restart();
            availability.report(true);
        }
        assertThat(browser.startLogin("")).containsKey("state");
    }

    @Test
    void stepUpRequiresAFreshAuthenticationAndIsAudited() throws Exception {
        String ok = newUser();
        Map<String, String> request = browser.startLogin("?stepup=1&return=/app/reference");
        assertThat(request).containsEntry("prompt", "login").containsEntry("max_age", "0");
        MockHttpServletResponse fresh = browser.callback(request, ENV.keycloak.user(ok), ENV.clock.instant().minusSeconds(30));
        assertThat(fresh.getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT outcome FROM audit_event WHERE event_type = 'auth.step_up' AND actor_id = ?").param(ok)
                .query(String.class).list()).containsExactly("success");

        String stale = newUser();
        browser.clearCookies();
        Map<String, String> second = browser.startLogin("?stepup=1");
        MockHttpServletResponse old = browser.callback(second, ENV.keycloak.user(stale), ENV.clock.instant().minus(Duration.ofMinutes(6)));
        assertThat(old.getStatus()).isEqualTo(400);
        assertThat(browser.cookie("__Host-slg-session")).isNull();
        assertThat(jdbc.sql("SELECT outcome FROM audit_event WHERE event_type = 'auth.step_up' AND actor_id = ?").param(stale)
                .query(String.class).list()).containsExactly("failure");
        assertThat(audit("auth.login", stale)).isZero();
    }

    @Test
    void aNewLoginReplacesTheSessionOfTheSameBrowser() throws Exception {
        String sub = newUser();
        browser.login(sub);
        String oldId = browser.cookie("__Host-slg-session");
        browser.login(sub);
        assertThat(browser.cookie("__Host-slg-session")).isNotEqualTo(oldId);
        assertThat(jdbc.sql("SELECT count(*) FROM session WHERE user_id = ?").param(sub).query(Long.class).single()).isEqualTo(1);
        assertThat(audit("auth.login", sub)).isEqualTo(2);
        assertThat(terminations(sub)).as("replacement is not a forced termination").isEmpty();
    }

    // ---- first request after login, session view

    @Test
    void theFirstRequestToTheAppAfterLoginCarriesTheSession() throws Exception {
        browser.login(newUser());
        MockHttpServletResponse app = mvc.perform(browser.shell(get("/app/reference/notes"))).andReturn().getResponse();
        assertThat(app.getStatus()).isEqualTo(200);
        assertThat(app.getContentAsString()).contains("<html lang=\"de\">", "SLG OS");
    }

    @Test
    void anUnauthenticatedAppRequestIsRedirectedToTheLogin() throws Exception {
        MockHttpServletResponse response = mvc.perform(browser.shell(get("/app/reference/notes"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("https://shell.slg.test/core/auth/login?return=%2Fapp%2Freference%2Fnotes");
    }

    @Test
    void theSessionViewHasProfilePermissionsAndTheCsrfTokenButNeverATokenOfAnyKind() throws Exception {
        String sub = newUser();
        Browser.Session session = browser.login(sub, "/slg/admins");
        String body = mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"sub\":\"" + sub + "\"", "\"displayName\":\"Name of " + sub + "\"", "\"locale\":\"de\"",
                "\"theme\":\"system\"", "\"permissions\":[\"platform-admin\"]", "\"csrfToken\":\"" + session.csrf() + "\"")
                .doesNotContainIgnoringCase("access_token").doesNotContain("eyJ", "refresh-", "access-", "id_token");
        String plain = newUser();
        browser.login(plain, "/slg/users");
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getContentAsString())
                .contains("\"permissions\":[]");
    }

    @Test
    void sessionEndpointsAnswer401WithAMachineReadableCodeAndTheCorrelationId() throws Exception {
        MockHttpServletResponse response = mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        String correlation = response.getHeader("X-Correlation-Id");
        assertThat(response.getContentAsString()).contains("\"code\":\"SESSION_EXPIRED\"", "\"correlationId\":\"" + correlation + "\"");
        browser.setCookie("__Host-slg-session", "AAAAAAAAAAAAAAAAAAAAAA");
        MockHttpServletResponse unknown = mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(unknown.getHeaders("Set-Cookie").get(0)).contains("Max-Age=0");
    }

    @Test
    void unknownHostsAreMisdirectedAndInternalPathsAreNotPublic() throws Exception {
        assertThat(mvc.perform(get("/core/api/v1/session").header("Host", "evil.example")).andReturn().getResponse().getStatus())
                .isEqualTo(421);
        assertThat(mvc.perform(post("/core/internal/backchannel-logout").header("Host", Browser.HOST)
                .contentType("application/x-www-form-urlencoded").content("logout_token=x")).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---- CSRF

    private MockHttpServletResponse touch(Browser.Session session, String origin, String token, String fetchSite) throws Exception {
        var request = browser.shell(post("/core/api/v1/session/touch"));
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (token != null) {
            request.header("X-Slg-Csrf", token);
        }
        if (fetchSite != null) {
            request.header("Sec-Fetch-Site", fetchSite);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void everyStateChangingEndpointNeedsTheTokenAndTheOrigin() throws Exception {
        Browser.Session me = browser.login(newUser());
        Browser other = new Browser(mvc, ENV.keycloak, ENV.clock);
        Browser.Session someoneElse = other.login(newUser());

        for (String path : new String[] {"/core/api/v1/session/touch", "/core/auth/logout", "/core/api/v1/anything",
                "/modules/reference/api/v1/notes"}) {
            String[][] cases = {
                    {null, null}, {Browser.ORIGIN, null}, {null, me.csrf()}, {Browser.ORIGIN, "wrong-token"},
                    {"https://evil.example", me.csrf()}, {Browser.ORIGIN, someoneElse.csrf()}, {"null", me.csrf()}};
            for (String[] c : cases) {
                var request = browser.shell(post(path));
                if (c[0] != null) {
                    request.header("Origin", c[0]);
                }
                if (c[1] != null) {
                    request.header("X-Slg-Csrf", c[1]);
                }
                MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
                assertThat(response.getStatus()).as(path + " " + java.util.Arrays.toString(c)).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("\"code\":\"CSRF\"");
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM session WHERE id_hash = ?").param(SessionIds.hash(me.cookie())).query(Long.class)
                .single()).as("a rejected logout does not end the session").isEqualTo(1);
    }

    @Test
    void crossSiteFetchMetadataIsRefusedAndAValidRequestPasses() throws Exception {
        Browser.Session me = browser.login(newUser());
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), "cross-site").getStatus()).isEqualTo(403);
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), "same-site").getStatus()).isEqualTo(403);
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), "same-origin").getStatus()).isEqualTo(204);
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), null).getStatus()).isEqualTo(204);
    }

    @Test
    void getRequestsNeverNeedAToken() throws Exception {
        browser.login(newUser());
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ---- timeouts

    @Test
    void theIdleTimeoutEndsTheSessionAndWritesOneAuditEntry() throws Exception {
        String sub = newUser();
        browser.login(sub);
        ENV.clock.advance(Duration.ofMinutes(29));
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
        ENV.clock.advance(Duration.ofMinutes(29));
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus())
                .as("29 minutes after the last activity").isEqualTo(200);
        ENV.clock.advance(Duration.ofMinutes(31));
        MockHttpServletResponse expired = mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse();
        assertThat(expired.getStatus()).isEqualTo(401);
        assertThat(expired.getContentAsString()).contains("SESSION_EXPIRED");
        mvc.perform(browser.shell(get("/core/api/v1/session")));
        assertThat(terminations(sub)).containsExactly("idle_timeout");
    }

    @Test
    void backgroundRequestsDoNotCountAsActivity() throws Exception {
        String sub = newUser();
        browser.login(sub);
        for (int i = 0; i < 5; i++) {
            ENV.clock.advance(Duration.ofMinutes(8));
            assertThat(mvc.perform(browser.shell(get("/core/api/v1/session")).header("X-Slg-Background", "1")).andReturn()
                    .getResponse().getStatus()).isLessThanOrEqualTo(401);
        }
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus())
                .as("40 minutes of background requests only").isEqualTo(401);
        assertThat(terminations(sub)).containsExactly("idle_timeout");
    }

    @Test
    void userActivityThroughTheTouchEndpointKeepsTheSessionAliveUntilTheAbsoluteTimeout() throws Exception {
        String sub = newUser();
        Browser.Session me = browser.login(sub);
        for (int i = 0; i < 29; i++) {
            ENV.clock.advance(Duration.ofMinutes(20));
            assertThat(touch(me, Browser.ORIGIN, me.csrf(), null).getStatus()).as("after %d minutes", (i + 1) * 20).isEqualTo(204);
        }
        ENV.clock.advance(Duration.ofMinutes(20));
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), null).getStatus()).as("absolute timeout of 10 hours passed").isEqualTo(401);
        assertThat(terminations(sub)).containsExactly("absolute_timeout");
    }

    @Test
    void touchRenewsTheTokensAndTakesTheGroupsFromTheRenewedIdToken() throws Exception {
        String sub = newUser();
        Browser.Session me = browser.login(sub, "/slg/users");
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.keycloak.setGroups(sub, "/slg/admins");
        ENV.clock.advance(Duration.ofMinutes(4).plusSeconds(10));
        assertThat(touch(me, Browser.ORIGIN, me.csrf(), null).getStatus()).isEqualTo(204);
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 1);
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getContentAsString())
                .contains("\"permissions\":[\"platform-admin\"]");
    }

    @Test
    void touchSucceedsWhileKeycloakIsDownAndTheSessionIsKept() throws Exception {
        Browser.Session me = browser.login(newUser());
        ENV.clock.advance(Duration.ofMinutes(4).plusSeconds(30));
        ENV.keycloak.stop();
        try {
            assertThat(touch(me, Browser.ORIGIN, me.csrf(), null).getStatus()).isEqualTo(204);
            assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
        } finally {
            ENV.keycloak.restart();
        }
    }

    // ---- logout

    @Test
    void logoutEndsTheSessionAndRedirectsToKeycloaksEndSession() throws Exception {
        String sub = newUser();
        Browser.Session me = browser.login(sub);
        MockHttpServletResponse response = mvc.perform(browser.shell(post("/core/auth/logout").header("Origin", Browser.ORIGIN)
                .header("X-Slg-Csrf", me.csrf()))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(303);
        URI location = URI.create(response.getHeader("Location"));
        assertThat(location.toString()).startsWith(ENV.keycloak.issuer() + "/protocol/openid-connect/logout?");
        Map<String, String> q = Browser.query(location.getRawQuery());
        assertThat(q.get("id_token_hint").split("\\.")).hasSize(3);
        assertThat(q).containsEntry("post_logout_redirect_uri", "https://shell.slg.test/core/auth/signed-out")
                .containsEntry("client_id", "slg-core");
        assertThat(response.getHeader("Set-Cookie")).contains("__Host-slg-session=;", "Max-Age=0");
        assertThat(audit("auth.logout", sub)).isEqualTo(1);

        browser.setCookie("__Host-slg-session", me.cookie());
        assertThat(mvc.perform(browser.shell(get("/core/api/v1/session"))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void fetchCallersGetTheLogoutTargetInTheBody() throws Exception {
        Browser.Session me = browser.login(newUser());
        MockHttpServletResponse response = mvc.perform(browser.shell(post("/core/auth/logout").header("Origin", Browser.ORIGIN)
                .header("X-Slg-Csrf", me.csrf()).header("Accept", "application/json"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"redirectUrl\":\"" + ENV.keycloak.issuer() + "/protocol/openid-connect/logout?");
    }

    @Test
    void signedOutAndPopupDonePagesNeedNoSessionAndFollowTheBrowserLanguage() throws Exception {
        MockHttpServletResponse signedOut = mvc.perform(browser.shell(get("/core/auth/signed-out").header("Accept-Language", "en")))
                .andReturn().getResponse();
        assertThat(signedOut.getStatus()).isEqualTo(200);
        assertThat(signedOut.getContentAsString()).contains("<html lang=\"en\">", "Signed out");
        MockHttpServletResponse popup = mvc.perform(browser.shell(get("/core/auth/popup-done"))).andReturn().getResponse();
        assertThat(popup.getContentAsString()).contains("popup-done.js", "Dieses Fenster schließen");
        assertThat(popup.getHeader("Content-Language")).isEqualTo("de");
    }

    @Test
    void staticAssetsOfThePagesAreServed() throws Exception {
        assertThat(mvc.perform(browser.shell(get("/core/auth/assets/pages.css"))).andReturn().getResponse().getContentAsString())
                .contains("prefers-color-scheme: dark", "forced-colors");
        assertThat(mvc.perform(browser.shell(get("/core/auth/assets/popup-done.js"))).andReturn().getResponse().getContentAsString())
                .contains("BroadcastChannel");
    }

    @Test
    void unknownApiPathsAnswerAProblemDetailAfterAuthentication() throws Exception {
        browser.login(newUser());
        MockHttpServletResponse response = mvc.perform(browser.shell(get("/core/api/v1/nothing"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"NOT_FOUND\"");
    }
}
