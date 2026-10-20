package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.security.CsrfTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * Data that survives the round trip to Keycloak: state, nonce, PKCE verifier, return target and flags. It travels in the
 * pre-login cookie ({@code SameSite=Lax}, 10 minutes, signed with the server key), so any core container can complete the
 * callback (PRD-0003 SK-3 AC1).
 */
record LoginState(String state, String nonce, String verifier, String target, boolean popup, boolean stepUp,
        Instant issuedAt) {

    static final String COOKIE = "__Host-slg-login";
    static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** The Set-Cookie header value that carries this state to the callback. */
    String toSetCookie(CsrfTokens signer) {
        String payload = String.join(".", state, nonce, verifier, B64.encodeToString(target.getBytes(StandardCharsets.UTF_8)),
                (popup ? "1" : "0") + (stepUp ? "1" : "0"), Long.toString(issuedAt.getEpochSecond()));
        String signature = B64.encodeToString(signer.sign("login", payload.getBytes(StandardCharsets.UTF_8)));
        return ResponseCookie.from(COOKIE, payload + "." + signature).httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(LIFETIME).build().toString();
    }

    static String clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(0).build()
                .toString();
    }

    /** Reads and verifies the cookie; empty if it is absent, forged, malformed or older than ten minutes. */
    static Optional<LoginState> read(HttpServletRequest request, CsrfTokens signer, Instant now) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return parse(cookie.getValue(), signer, now);
            }
        }
        return Optional.empty();
    }

    static Optional<LoginState> parse(String value, CsrfTokens signer, Instant now) {
        try {
            int cut = value.lastIndexOf('.');
            if (cut < 0) {
                return Optional.empty();
            }
            String payload = value.substring(0, cut);
            byte[] given = B64D.decode(value.substring(cut + 1));
            byte[] expected = signer.sign("login", payload.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(given, expected)) {
                return Optional.empty();
            }
            String[] parts = payload.split("\\.", -1);
            if (parts.length != 6 || parts[4].length() != 2) {
                return Optional.empty();
            }
            Instant issued = Instant.ofEpochSecond(Long.parseLong(parts[5]));
            if (issued.plus(LIFETIME).isBefore(now) || issued.isAfter(now.plusSeconds(60))) {
                return Optional.empty();
            }
            return Optional.of(new LoginState(parts[0], parts[1], parts[2],
                    new String(B64D.decode(parts[3]), StandardCharsets.UTF_8), parts[4].charAt(0) == '1',
                    parts[4].charAt(1) == '1', issued));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
