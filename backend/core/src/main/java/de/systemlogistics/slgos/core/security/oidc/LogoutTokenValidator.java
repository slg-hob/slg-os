package de.systemlogistics.slgos.core.security.oidc;

import com.nimbusds.jwt.JWTClaimsSet;
import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Validates back-channel logout tokens (OpenID Connect Back-Channel Logout 1.0, PRD-0003 SK-3 AC5): signature, issuer,
 * audience = this client, the logout event, no nonce, an issue-time window and a token id. Replay detection by
 * {@code jti} is done by the caller against the database.
 */
public class LogoutTokenValidator {

    static final String LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final Duration SKEW = Duration.ofSeconds(60);

    private final JwtVerifier verifier;
    private final String issuer;
    private final String clientId;
    private final Clock clock;

    public LogoutTokenValidator(JwtVerifier verifier, String issuer, String clientId, Clock clock) {
        this.verifier = verifier;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
    }

    /** The validated content of a logout token. */
    public record LogoutClaims(String jti, String sessionId, String subject) {}

    public LogoutClaims validate(String logoutToken) {
        JWTClaimsSet claims = verifier.verify(logoutToken);
        Instant now = clock.instant();
        try {
            require(issuer.equals(claims.getIssuer()), "issuer mismatch");
            List<String> audience = claims.getAudience();
            require(audience != null && audience.contains(clientId), "audience mismatch");
            Map<String, Object> events = claims.getJSONObjectClaim("events");
            require(events != null && events.containsKey(LOGOUT_EVENT), "logout event missing");
            require(claims.getClaim("nonce") == null, "logout token must not carry a nonce");
            Date iat = claims.getIssueTime();
            require(iat != null && iat.toInstant().plus(MAX_AGE).isAfter(now)
                    && !iat.toInstant().minus(SKEW).isAfter(now), "issue time outside the window");
            Date exp = claims.getExpirationTime();
            require(exp == null || exp.toInstant().plus(SKEW).isAfter(now), "token expired");
            String type = claims.getStringClaim("typ");
            require(type == null || "Logout".equals(type), "not a logout token");
            String jti = claims.getJWTID();
            require(jti != null && !jti.isBlank() && jti.length() <= 200, "jti missing");
            String sid = claims.getStringClaim("sid");
            String sub = claims.getSubject();
            require(sid != null && !sid.isBlank() || sub != null && !sub.isBlank(), "neither sid nor sub");
            return new LogoutClaims(jti, sid, sub);
        } catch (ParseException e) {
            throw new OidcException(Kind.INVALID_TOKEN, "claim has the wrong type");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new OidcException(Kind.INVALID_TOKEN, message);
        }
    }
}
