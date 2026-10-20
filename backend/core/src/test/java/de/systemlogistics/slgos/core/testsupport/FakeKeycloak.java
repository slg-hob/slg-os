package de.systemlogistics.slgos.core.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A stand-in for Keycloak's OpenID endpoints (token, keys) with the behaviours the tests need: single-use authorization
 * codes with PKCE, rotating single-use refresh tokens, dropped or delayed responses, errors, and a stopped server.
 * The real production-mode Keycloak is exercised on CI (E2E, import smoke test); this stub lets the unit and integration
 * tests run everywhere and inject faults a real Keycloak cannot be made to produce.
 */
public class FakeKeycloak implements AutoCloseable {

    public static final String CLIENT_ID = "slg-core";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String REALM = "slg";

    /** A user of the directory. */
    public record User(String sub, String name, String locale, List<String> groups) {}

    private record Code(User user, String sid, String nonce, String challenge, Instant authTime) {}

    private record Refresh(String sub, String sid, String nonce, Instant authTime) {}

    private final RSAKey key;
    private final Supplier<Instant> clock;
    private final int port;
    private HttpServer server;

    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Refresh> liveRefreshTokens = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public final AtomicInteger codeGrants = new AtomicInteger();
    public final AtomicInteger refreshGrants = new AtomicInteger();
    public final AtomicInteger refreshAttempts = new AtomicInteger();
    public final AtomicInteger jwksFetches = new AtomicInteger();

    private volatile String lastSid;
    private volatile int refreshStatus;
    private volatile boolean dropNextRefresh;
    private volatile Duration refreshDelay = Duration.ZERO;
    private volatile Duration accessLifetime = Duration.ofMinutes(5);

    public FakeKeycloak(Supplier<Instant> clock) {
        try {
            this.key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        this.clock = clock;
        this.port = start(0);
    }

    private int start(int requestedPort) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
            String base = "/realms/" + REALM + "/protocol/openid-connect";
            server.createContext(base + "/token", this::token);
            server.createContext(base + "/certs", this::certs);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stops the server; connections to its port are refused afterwards. */
    public void stop() {
        server.stop(0);
    }

    /** Starts the server again on the same port. */
    public void restart() {
        start(port);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public String issuer() {
        return "http://localhost:" + port + "/realms/" + REALM;
    }

    public RSAKey key() {
        return key;
    }

    public User user(String sub, String... groups) {
        User user = new User(sub, "Name of " + sub, "de", List.of(groups));
        users.put(sub, user);
        return user;
    }

    public void setGroups(String sub, String... groups) {
        User old = users.get(sub);
        users.put(sub, new User(sub, old.name(), old.locale(), List.of(groups)));
    }

    /** Registers an authorization code for a user, as Keycloak would after a successful login. */
    public String issueCode(User user, String nonce, String challenge, Instant authTime) {
        users.putIfAbsent(user.sub(), user);
        String code = "code-" + UUID.randomUUID();
        String sid = "sid-" + UUID.randomUUID();
        lastSid = sid;
        codes.put(code, new Code(user, sid, nonce, challenge, authTime));
        return code;
    }

    /** The Keycloak session id of the most recently issued code. */
    public String lastSid() {
        return lastSid;
    }

    /** The Keycloak session id that a code will produce, for back-channel logout tests. */
    public String sidOf(String code) {
        Code entry = codes.get(code);
        return entry == null ? null : entry.sid();
    }

    public void failRefreshWith(int status) {
        this.refreshStatus = status;
    }

    public void dropNextRefreshResponse() {
        this.dropNextRefresh = true;
    }

    public void delayRefresh(Duration delay) {
        this.refreshDelay = delay;
    }

    public void accessTokenLifetime(Duration lifetime) {
        this.accessLifetime = lifetime;
    }

    /** Revokes every refresh token, as Keycloak does when the SSO session ended. */
    public void revokeAllRefreshTokens() {
        liveRefreshTokens.clear();
    }

    public int liveRefreshTokenCount() {
        return liveRefreshTokens.size();
    }

    /** Claims of a valid ID token for the client; tests change single claims to build invalid ones. */
    public JWTClaimsSet.Builder idClaims(String sub, String nonce) {
        Instant now = clock.get();
        return new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(sub)
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("typ", "ID")
                .claim("sid", "sid-" + sub)
                .claim("name", "Name of " + sub)
                .claim("groups", List.of());
    }

    /** Claims of a valid back-channel logout token. */
    public JWTClaimsSet.Builder logoutClaims(String sid, String sub) {
        Instant now = clock.get();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .jwtID(UUID.randomUUID().toString())
                .claim("typ", "Logout")
                .claim("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
        if (sid != null) {
            builder.claim("sid", sid);
        }
        if (sub != null) {
            builder.subject(sub);
        }
        return builder;
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    public static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAKey generateKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- HTTP handlers

    private void certs(HttpExchange exchange) throws IOException {
        jwksFetches.incrementAndGet();
        respond(exchange, 200, new JWKSet(key.toPublicJWK()).toString());
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        switch (form.getOrDefault("grant_type", "")) {
            case "authorization_code" -> codeGrant(exchange, form);
            case "refresh_token" -> refreshGrant(exchange, form);
            default -> respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private void codeGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        Code code = codes.remove(form.getOrDefault("code", ""));
        if (code == null || !pkceOk(code.challenge(), form.get("code_verifier"))) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        codeGrants.incrementAndGet();
        respond(exchange, 200, tokenResponse(code.user(), code.sid(), code.nonce(), code.authTime()));
    }

    private void refreshGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        refreshAttempts.incrementAndGet();
        sleep(refreshDelay);
        if (refreshStatus != 0) {
            respond(exchange, refreshStatus, "{\"error\":\"server_error\"}");
            return;
        }
        Refresh refresh = liveRefreshTokens.remove(form.getOrDefault("refresh_token", ""));
        if (refresh == null) {
            // Like Keycloak: reuse of a rotated token is refused.
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        refreshGrants.incrementAndGet();
        User user = users.get(refresh.sub());
        String body = tokenResponse(user, refresh.sid(), refresh.nonce(), refresh.authTime());
        if (dropNextRefresh) {
            dropNextRefresh = false;
            exchange.close();
            return;
        }
        respond(exchange, 200, body);
    }

    private String tokenResponse(User user, String sid, String nonce, Instant authTime) {
        Instant now = clock.get();
        String refresh = "refresh-" + UUID.randomUUID();
        liveRefreshTokens.put(refresh, new Refresh(user.sub(), sid, nonce, authTime));
        JWTClaimsSet.Builder claims = idClaims(user.sub(), nonce)
                .claim("sid", sid)
                .claim("name", user.name())
                .claim("locale", user.locale())
                .claim("groups", user.groups())
                .claim("auth_time", authTime.getEpochSecond())
                .expirationTime(Date.from(now.plus(accessLifetime)));
        return "{\"access_token\":\"access-" + UUID.randomUUID() + "\",\"refresh_token\":\"" + refresh
                + "\",\"id_token\":\"" + sign(claims.build()) + "\",\"expires_in\":" + accessLifetime.toSeconds()
                + ",\"token_type\":\"Bearer\"}";
    }

    private static boolean pkceOk(String challenge, String verifier) {
        if (verifier == null) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return challenge.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
