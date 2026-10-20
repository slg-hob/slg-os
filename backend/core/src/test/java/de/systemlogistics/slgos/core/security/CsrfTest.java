package de.systemlogistics.slgos.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CsrfTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SESSION_A = new byte[32];
    private static final byte[] SESSION_B = new byte[32];

    static {
        Arrays.fill(SESSION_A, (byte) 1);
        Arrays.fill(SESSION_B, (byte) 2);
    }

    private final CsrfTokens tokens = new CsrfTokens(KEY);
    private final CsrfPolicy policy = new CsrfPolicy("https://shell.slg.test");

    private MockHttpServletRequest post(String origin, String token, String fetchSite) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/core/api/v1/session/touch");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (token != null) {
            request.addHeader(CsrfPolicy.HEADER, token);
        }
        if (fetchSite != null) {
            request.addHeader("Sec-Fetch-Site", fetchSite);
        }
        return request;
    }

    @Test
    void tokenIsStablePerSessionAndDiffersBetweenSessions() {
        assertThat(tokens.derive(SESSION_A)).isEqualTo(tokens.derive(SESSION_A)).isNotEqualTo(tokens.derive(SESSION_B));
        assertThat(tokens.derive(SESSION_A)).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void tokenDependsOnTheServerKey() {
        CsrfTokens other = new CsrfTokens("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        assertThat(other.derive(SESSION_A)).isNotEqualTo(tokens.derive(SESSION_A));
    }

    @Test
    void signaturesOfDifferentDomainsDiffer() {
        assertThat(tokens.sign("login", SESSION_A)).isNotEqualTo(tokens.sign("csrf", SESSION_A));
    }

    @Test
    void shortKeysAreRefused() {
        assertThatThrownBy(() -> new CsrfTokens(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesOnlyTheOwnToken() {
        assertThat(tokens.matches(SESSION_A, tokens.derive(SESSION_A))).isTrue();
        assertThat(tokens.matches(SESSION_A, tokens.derive(SESSION_B))).isFalse();
        assertThat(tokens.matches(SESSION_A, "")).isFalse();
        assertThat(tokens.matches(SESSION_A, null)).isFalse();
    }

    @Test
    void acceptsTheRightTokenFromTheShellOrigin() {
        assertThat(policy.accepts(post("https://shell.slg.test", tokens.derive(SESSION_A), null), tokens, SESSION_A)).isTrue();
        assertThat(policy.accepts(post("https://shell.slg.test", tokens.derive(SESSION_A), "same-origin"), tokens, SESSION_A))
                .isTrue();
        assertThat(policy.accepts(post("HTTPS://SHELL.SLG.TEST", tokens.derive(SESSION_A), null), tokens, SESSION_A)).isTrue();
    }

    @Test
    void rejectsMissingOrWrongOriginEvenWithAValidToken() {
        String token = tokens.derive(SESSION_A);
        assertThat(policy.accepts(post(null, token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://evil.example", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test:8443", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("http://shell.slg.test", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("null", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test.evil.example", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://evilshell.slg.test", token, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test/", token, null), tokens, SESSION_A)).isFalse();
    }

    @Test
    void rejectsMissingWrongOrForeignTokens() {
        assertThat(policy.accepts(post("https://shell.slg.test", null, null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test", "wrong", null), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test", tokens.derive(SESSION_B), null), tokens, SESSION_A)).isFalse();
    }

    @Test
    void rejectsCrossSiteFetchMetadata() {
        String token = tokens.derive(SESSION_A);
        assertThat(policy.accepts(post("https://shell.slg.test", token, "cross-site"), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test", token, "same-site"), tokens, SESSION_A)).isFalse();
        assertThat(policy.accepts(post("https://shell.slg.test", token, "none"), tokens, SESSION_A)).isFalse();
    }

    @Test
    void policyKnowsItsOriginIncludingAPort() {
        assertThat(policy.shellOrigin()).isEqualTo("https://shell.slg.test");
        assertThat(new CsrfPolicy("https://Shell.slg.test:8443").shellOrigin()).isEqualTo("https://shell.slg.test:8443");
        assertThat(new CsrfPolicy("https://shell.slg.test:443").shellOrigin()).as("browsers omit the default port")
                .isEqualTo("https://shell.slg.test");
        assertThat(new CsrfPolicy("http://localhost:80").shellOrigin()).isEqualTo("http://localhost");
    }

    @Test
    void onlyGetAndHeadAreSafe() {
        assertThat(CsrfPolicy.isSafe("GET")).isTrue();
        assertThat(CsrfPolicy.isSafe("HEAD")).isTrue();
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "get", "head", "Get"}) {
            assertThat(CsrfPolicy.isSafe(method)).as(method).isFalse();
        }
    }
}
