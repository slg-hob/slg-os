package de.systemlogistics.slgos.core.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Plays the browser side of the sign-in against the core with MockMvc, and keeps the cookies it receives. */
public final class Browser {

    public static final String HOST = "shell.slg.test";
    public static final String ORIGIN = "https://shell.slg.test";

    /** A signed-in user: the session cookie value and the CSRF token from {@code GET /core/api/v1/session}. */
    public record Session(String sub, String cookie, String csrf, String keycloakSid) {}

    private final MockMvc mvc;
    private final FakeKeycloak keycloak;
    private final MutableClock clock;
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    private final Map<String, String> cookies = new LinkedHashMap<>();
    /** Every browser has its own client address, so the login rate limit of one test never affects another. */
    private final String address = "198.51.100." + (NEXT_ADDRESS.incrementAndGet() % 250 + 1) + "";

    public Browser(MockMvc mvc, FakeKeycloak keycloak, MutableClock clock) {
        this.mvc = mvc;
        this.keycloak = keycloak;
        this.clock = clock;
    }

    /** A request to the shell host with the cookies of this browser. */
    public MockHttpServletRequestBuilder shell(MockHttpServletRequestBuilder builder) {
        builder.header("Host", HOST).with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
        cookies.forEach((name, value) -> builder.cookie(new Cookie(name, value)));
        return builder;
    }

    public static Map<String, String> query(String uriOrQuery) {
        String query = uriOrQuery.contains("?") ? uriOrQuery.substring(uriOrQuery.indexOf('?') + 1) : uriOrQuery;
        Map<String, String> result = new LinkedHashMap<>();
        Arrays.stream(query.split("&")).map(p -> p.split("=", 2)).forEach(p -> result.put(
                URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : ""));
        return result;
    }

    /** Stores the cookies of a response like a browser would (a Max-Age=0 cookie is removed). */
    public void remember(MockHttpServletResponse response) {
        for (String header : response.getHeaders("Set-Cookie")) {
            String pair = header.substring(0, header.indexOf(';') < 0 ? header.length() : header.indexOf(';'));
            String name = pair.substring(0, pair.indexOf('='));
            String value = pair.substring(pair.indexOf('=') + 1);
            if (header.contains("Max-Age=0") || value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void clearCookies() {
        cookies.clear();
    }

    /** Starts the login and returns the authorization request parameters Keycloak would receive. */
    public Map<String, String> startLogin(String loginQuery) throws Exception {
        MockHttpServletResponse response = mvc.perform(shell(get("/core/auth/login" + loginQuery))).andReturn().getResponse();
        if (response.getStatus() != 302) {
            throw new IllegalStateException("login answered " + response.getStatus());
        }
        remember(response);
        URI location = URI.create(response.getHeader("Location"));
        return query(location.getRawQuery());
    }

    /** Completes the callback with a code of the fake Keycloak for the user. */
    public MockHttpServletResponse callback(Map<String, String> authRequest, FakeKeycloak.User user, Instant authTime)
            throws Exception {
        String code = keycloak.issueCode(user, authRequest.get("nonce"), authRequest.get("code_challenge"), authTime);
        MockHttpServletResponse response = mvc.perform(shell(get("/core/auth/callback")
                .param("code", code).param("state", authRequest.get("state")))).andReturn().getResponse();
        remember(response);
        return response;
    }

    /** The whole sign-in as a user with the given groups. */
    public Session login(String sub, String... groups) throws Exception {
        FakeKeycloak.User user = keycloak.user(sub, groups);
        Map<String, String> request = startLogin("?return=/app/reference");
        String code = keycloak.issueCode(user, request.get("nonce"), request.get("code_challenge"), clock.instant());
        MockHttpServletResponse response = mvc.perform(shell(get("/core/auth/callback")
                .param("code", code).param("state", request.get("state")))).andReturn().getResponse();
        if (response.getStatus() != 200) {
            throw new IllegalStateException("callback answered " + response.getStatus());
        }
        remember(response);
        String csrf = readCsrf();
        return new Session(sub, cookies.get("__Host-slg-session"), csrf, keycloak.lastSid());
    }

    private String readCsrf() throws Exception {
        String body = mvc.perform(shell(get("/core/api/v1/session"))).andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"csrfToken\":\"") + 13;
        return body.substring(start, body.indexOf('"', start));
    }

    public List<String> cookieNames() {
        return List.copyOf(cookies.keySet());
    }
}
