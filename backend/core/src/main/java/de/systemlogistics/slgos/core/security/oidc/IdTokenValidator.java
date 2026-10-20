package de.systemlogistics.slgos.core.security.oidc;

import com.nimbusds.jwt.JWTClaimsSet;
import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Validates ID tokens: signature, issuer, audience, authorized party, time window and (at login) the nonce. */
public class IdTokenValidator {

    static final Duration SKEW = Duration.ofSeconds(60);

    private final JwtVerifier verifier;
    private final String issuer;
    private final String clientId;
    private final Clock clock;

    public IdTokenValidator(JwtVerifier verifier, String issuer, String clientId, Clock clock) {
        this.verifier = verifier;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
    }

    /**
     * @param expectedNonce the nonce of the login request; null when the token comes from a refresh (Keycloak keeps the
     *                      original nonce there, which is not checked again)
     */
    public IdTokenClaims validate(String idToken, String expectedNonce) {
        JWTClaimsSet claims = verifier.verify(idToken);
        Instant now = clock.instant();
        try {
            require(issuer.equals(claims.getIssuer()), "issuer mismatch");
            List<String> audience = claims.getAudience();
            require(audience != null && audience.contains(clientId), "audience mismatch");
            if (audience.size() > 1) {
                require(clientId.equals(claims.getStringClaim("azp")), "authorized party mismatch");
            }
            Date exp = claims.getExpirationTime();
            require(exp != null && exp.toInstant().plus(SKEW).isAfter(now), "token expired");
            Date iat = claims.getIssueTime();
            require(iat != null && !iat.toInstant().minus(SKEW).isAfter(now), "token issued in the future");
            Date nbf = claims.getNotBeforeTime();
            require(nbf == null || !nbf.toInstant().minus(SKEW).isAfter(now), "token not yet valid");
            String type = claims.getStringClaim("typ");
            require(type == null || "ID".equals(type), "not an ID token");
            if (expectedNonce != null) {
                require(expectedNonce.equals(claims.getStringClaim("nonce")), "nonce mismatch");
            }
            String subject = claims.getSubject();
            require(subject != null && !subject.isBlank(), "subject missing");
            String name = claims.getStringClaim("name");
            if (name == null || name.isBlank()) {
                name = claims.getStringClaim("preferred_username");
            }
            Object authTime = claims.getClaim("auth_time");
            return new IdTokenClaims(subject, claims.getStringClaim("sid"), name, claims.getStringClaim("locale"),
                    JwtVerifier.stringList(claims, "groups"),
                    authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null);
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
