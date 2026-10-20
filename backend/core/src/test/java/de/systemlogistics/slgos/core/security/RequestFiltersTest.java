package de.systemlogistics.slgos.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.config.SlgProperties;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestFiltersTest {

    private final ProblemWriter problems = new ProblemWriter(JsonMapper.builder().build());

    private SlgProperties properties(List<String> proxies) {
        return new SlgProperties("test", "https://shell.slg.test", List.of("core-backend", "localhost"), proxies, null,
                new SlgProperties.Oidc(null, "slg-core", null, Duration.ofSeconds(3)),
                new SlgProperties.Session(null, null, 10, null, null, null, null, null),
                new SlgProperties.Db(null, null, null, null, null, 10, "classpath:db/migration"));
    }

    private MockHttpServletRequest request(String method, String uri, String host, String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (host != null) {
            request.addHeader("Host", host);
        }
        request.setRemoteAddr(remote);
        return request;
    }

    // ---- host filter

    private MockHttpServletResponse host(String uri, String host) throws Exception {
        HostFilter filter = new HostFilter(properties(List.of()), problems);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", uri, host, "10.0.0.1"), response, (req, res) -> res.getWriter().write("reached"));
        return response;
    }

    @Test
    void shellHostReachesPublicPaths() throws Exception {
        assertThat(host("/app/x", "shell.slg.test").getContentAsString()).isEqualTo("reached");
        assertThat(host("/core/auth/login", "SHELL.slg.test:443").getContentAsString()).isEqualTo("reached");
    }

    @Test
    void encodedAndParameterisedInternalPathsAreStillInternal() throws Exception {
        for (String path : new String[] {"/core/%69nternal/backchannel-logout", "/core/internal;x=1/backchannel-logout",
                "/core//internal/backchannel-logout", "/core/./internal/x", "/core/auth/../internal/x", "/core/internal"}) {
            MockHttpServletResponse response = host(path, "shell.slg.test");
            assertThat(response.getStatus()).as(path).isEqualTo(404);
        }
        assertThat(host("/core/%69nternal/backchannel-logout", "core-backend").getContentAsString()).isEqualTo("reached");
    }

    @Test
    void unknownOrMissingHostsAreMisdirected() throws Exception {
        for (String host : new String[] {"evil.example", "shell.slg.test.evil.example", "", null, "[::1]", "[broken"}) {
            MockHttpServletResponse response = host("/app/x", host);
            assertThat(response.getStatus()).as(String.valueOf(host)).isEqualTo(421);
            assertThat(response.getContentAsString()).contains("\"code\":\"MISDIRECTED_HOST\"");
        }
    }

    @Test
    void internalPathsAnswerOnlyForInternalHosts() throws Exception {
        assertThat(host("/core/internal/backchannel-logout", "core-backend:8080").getContentAsString()).isEqualTo("reached");
        MockHttpServletResponse publicHost = host("/core/internal/backchannel-logout", "shell.slg.test");
        assertThat(publicHost.getStatus()).isEqualTo(404);
        assertThat(publicHost.getContentAsString()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(host("/core/internal/api/v1/permissions", "evil.example").getStatus()).isEqualTo(421);
    }

    // ---- correlation id

    private String correlate(List<String> proxies, String remote, String inbound) throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(new ClientIp(properties(proxies)));
        MockHttpServletRequest request = request("GET", "/core/api/v1/session", "shell.slg.test", remote);
        if (inbound != null) {
            request.addHeader(CorrelationId.HEADER, inbound);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(CorrelationId.current()));
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(seen.get());
        assertThat(CorrelationId.current()).as("context is cleaned up").isNull();
        return seen.get();
    }

    @Test
    void anInboundCorrelationIdIsAcceptedOnlyFromTheProxyNetwork() throws Exception {
        assertThat(correlate(List.of("172.20.0.0/16"), "172.20.0.5", "abcd1234-ef56")).isEqualTo("abcd1234-ef56");
        assertThat(correlate(List.of("172.20.0.0/16"), "203.0.113.9", "abcd1234-ef56")).isNotEqualTo("abcd1234-ef56");
        assertThat(correlate(List.of(), "172.20.0.5", "abcd1234-ef56")).isNotEqualTo("abcd1234-ef56");
    }

    @Test
    void malformedInboundIdsAreReplacedEvenFromTheProxy() throws Exception {
        for (String bad : new String[] {"short", "has space in it 123", "line\r\nbreak-1234", "x".repeat(65), "bad;char-123456"}) {
            assertThat(correlate(List.of("172.20.0.0/16"), "172.20.0.5", bad)).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void everyRequestWithoutAnIdGetsANewOne() throws Exception {
        assertThat(correlate(List.of(), "10.0.0.1", null)).isNotEqualTo(correlate(List.of(), "10.0.0.1", null));
    }

    // ---- client address

    @Test
    void forwardedAddressCountsOnlyFromTheProxy() {
        ClientIp clientIp = new ClientIp(properties(List.of("172.20.0.0/16")));
        MockHttpServletRequest viaProxy = request("GET", "/", "shell.slg.test", "172.20.0.5");
        viaProxy.addHeader("X-Forwarded-For", "198.51.100.7, 172.20.0.5");
        assertThat(clientIp.of(viaProxy)).isEqualTo("198.51.100.7");
        MockHttpServletRequest direct = request("GET", "/", "shell.slg.test", "203.0.113.9");
        direct.addHeader("X-Forwarded-For", "198.51.100.7");
        assertThat(clientIp.of(direct)).isEqualTo("203.0.113.9");
        MockHttpServletRequest spoofed = request("GET", "/", "shell.slg.test", "172.20.0.5");
        spoofed.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7");
        assertThat(clientIp.of(spoofed)).as("the proxy appended the real client; the client-supplied left side is ignored")
                .isEqualTo("198.51.100.7");
        MockHttpServletRequest chain = request("GET", "/", "shell.slg.test", "172.20.0.5");
        chain.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7, 172.20.0.9");
        assertThat(clientIp.of(chain)).isEqualTo("198.51.100.7");
        MockHttpServletRequest junk = request("GET", "/", "shell.slg.test", "172.20.0.5");
        junk.addHeader("X-Forwarded-For", "not-an-ip");
        assertThat(clientIp.of(junk)).isEqualTo("172.20.0.5");
        MockHttpServletRequest none = request("GET", "/", "shell.slg.test", "172.20.0.5");
        assertThat(clientIp.of(none)).isEqualTo("172.20.0.5");
    }

    // ---- security headers and problem details

    @Test
    void securityHeadersAreSetAndCoreResponsesAreNotCached() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletResponse api = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/core/api/v1/session", "h", "10.0.0.1"), api, (FilterChain) (req, res) -> { });
        assertThat(api.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(api.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(api.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
        MockHttpServletResponse app = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/app", "h", "10.0.0.1"), app, (FilterChain) (req, res) -> { });
        assertThat(app.getHeader("Cache-Control")).isEqualTo("no-store");
        MockHttpServletResponse asset = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/assets/x.js", "h", "10.0.0.1"), asset, (FilterChain) (req, res) -> { });
        assertThat(asset.getHeader("Cache-Control")).isNull();
        MockHttpServletResponse pageAsset = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/core/auth/assets/pages.css", "h", "10.0.0.1"), pageAsset, (FilterChain) (req, res) -> { });
        assertThat(pageAsset.getHeader("Cache-Control")).isEqualTo("public, max-age=3600");
        MockHttpServletResponse encoded = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/%61pp/x", "h", "10.0.0.1"), encoded, (FilterChain) (req, res) -> { });
        assertThat(encoded.getHeader("Cache-Control")).as("encoded paths are classified by their decoded form").isEqualTo("no-store");
    }

    @Test
    void problemDetailsCarryCodeStatusAndCorrelationIdButNoDetail() throws Exception {
        MDC.put(CorrelationId.MDC_KEY, "corr-1234abcd");
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            problems.write(response, ErrorCode.CSRF);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"code\":\"CSRF\"", "\"status\":403",
                    "\"correlationId\":\"corr-1234abcd\"").doesNotContain("detail");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    @Test
    void everyErrorCodeHasAStatusAndTitle() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).isBetween(400, 599);
            assertThat(code.title()).isNotBlank();
        }
    }

    // ---- rate limiter

    @Test
    void rateLimiterAllowsTheLimitPerWindowAndPerKey() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        RateLimiter limiter = new RateLimiter(clock, 3, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).as("other key").isTrue();
        now.set(now.get().plusSeconds(59));
        assertThat(limiter.tryAcquire("a")).as("still the same window").isFalse();
        now.set(now.get().plusSeconds(2));
        assertThat(limiter.tryAcquire("a")).as("new window").isTrue();
    }
}
