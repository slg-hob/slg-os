package de.systemlogistics.slgos.core.security.oidc;

import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to Keycloak: authorization URL, code exchange, refresh grant, end-session URL and a reachability probe.
 * The issuer is the public URL (PRD-0003 SK-15 AC1). Every call has a short timeout (3 s by default). Failures are
 * classified so callers know whether a refresh token may have been consumed (see {@link OidcException.Kind}).
 */
public class OidcClient {

    private static final Logger LOG = LoggerFactory.getLogger(OidcClient.class);

    private final java.util.concurrent.atomic.AtomicBoolean probeFailing = new java.util.concurrent.atomic.AtomicBoolean();

    private final OidcEndpoints endpoints;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final JsonMapper mapper;

    public OidcClient(OidcEndpoints endpoints, String clientId, String clientSecret, Duration timeout, JsonMapper mapper) {
        this.endpoints = endpoints;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
        this.mapper = mapper;
    }

    public OidcEndpoints endpoints() {
        return endpoints;
    }

    /** The browser URL that starts authorization code + PKCE (S256). */
    public URI authorizationUri(String redirectUri, String state, String nonce, String codeChallenge, String uiLocale,
            boolean forceLogin) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("response_type", "code");
        query.put("scope", "openid profile email");
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.put("ui_locales", uiLocale);
        if (forceLogin) {
            query.put("prompt", "login");
            query.put("max_age", "0");
        }
        return URI.create(endpoints.authorization() + "?" + form(query));
    }

    /** RP-initiated logout URL; the ID token hint avoids Keycloak's confirmation page. */
    public URI endSessionUri(String idTokenHint, String postLogoutRedirectUri) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("id_token_hint", idTokenHint);
        query.put("post_logout_redirect_uri", postLogoutRedirectUri);
        return URI.create(endpoints.logout() + "?" + form(query));
    }

    public OidcTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("code_verifier", codeVerifier);
        body.put("redirect_uri", redirectUri);
        return tokenRequest(body);
    }

    public OidcTokens refresh(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        return tokenRequest(body);
    }

    /** Fetches the JSON Web Key Set. */
    public String fetchJwks() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoints.jwks())).GET()
                .timeout(timeout).build());
        if (response.statusCode() != 200) {
            throw new OidcException(Kind.UNAVAILABLE, "JWKS endpoint answered " + response.statusCode());
        }
        return response.body();
    }

    /** True when Keycloak answers its key endpoint. */
    public boolean reachable() {
        try {
            fetchJwks();
            if (probeFailing.getAndSet(false)) {
                LOG.info("Identity provider reachable again");
            }
            return true;
        } catch (OidcException e) {
            // Logged once per outage, not on every probe. The cause names the failing layer (DNS, TLS trust, HTTP status);
            // no secret and no response body is part of it.
            if (!probeFailing.getAndSet(true)) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LOG.warn("Identity provider probe failed: {} ({})", e.getMessage(), cause.getClass().getName());
            }
            return false;
        }
    }

    private OidcTokens tokenRequest(Map<String, String> body) {
        String basic = Base64.getEncoder().encodeToString((urlEncode(clientId) + ":" + urlEncode(clientSecret))
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.token()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
        HttpResponse<String> response = send(request);
        int status = response.statusCode();
        if (status == 400 || status == 401) {
            // Only invalid_grant means the code or refresh token is spent or unknown. Everything else (invalid_client after a
            // secret rotation, a misconfigured realm) is an operator problem and must not end the sessions of all users.
            if ("invalid_grant".equals(errorOf(response.body()))) {
                throw new OidcException(Kind.REJECTED, "token endpoint refused the grant (" + status + ")");
            }
            throw new OidcException(Kind.UNAVAILABLE, "token endpoint refused the client (" + status + ")");
        }
        if (status != 200) {
            throw new OidcException(Kind.UNAVAILABLE, "token endpoint answered " + status);
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            String access = text(json, "access_token");
            String refresh = text(json, "refresh_token");
            String id = text(json, "id_token");
            long expiresIn = json.path("expires_in").asLong(0);
            if (access == null || refresh == null || id == null || expiresIn <= 0) {
                throw new OidcException(Kind.REJECTED, "token response is incomplete");
            }
            return new OidcTokens(access, refresh, id, expiresIn);
        } catch (tools.jackson.core.JacksonException e) {
            throw new OidcException(Kind.REJECTED, "token response is not valid JSON", e);
        }
    }

    private String errorOf(String body) {
        try {
            return text(mapper.readTree(body), "error");
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }

    /**
     * Sends on a fresh connection: a pooled connection that Keycloak closed meanwhile would fail after the request was
     * written and look like a lost response, which ends the session.
     */
    private HttpResponse<String> send(HttpRequest request) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException | SSLException e) {
            throw new OidcException(Kind.UNAVAILABLE, "identity provider not reachable", e);
        } catch (HttpTimeoutException e) {
            throw new OidcException(Kind.LOST_RESPONSE, "identity provider did not answer in time", e);
        } catch (IOException e) {
            throw new OidcException(Kind.LOST_RESPONSE, "connection to the identity provider broke", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException(Kind.LOST_RESPONSE, "interrupted while waiting for the identity provider", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() || value.asString().isEmpty() ? null : value.asString();
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
