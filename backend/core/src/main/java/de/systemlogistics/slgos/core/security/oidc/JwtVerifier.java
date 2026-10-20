package de.systemlogistics.slgos.core.security.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Verifies the signature of a JWT against the issuer's key set and returns its claims. Only asymmetric algorithms are
 * accepted (never {@code none} or HMAC). The key set is cached and refetched when an unknown key id appears, at most once
 * per {@link #REFETCH_INTERVAL} (PRD-0003 SK-14 AC1 uses the same rule for module tokens).
 */
public class JwtVerifier {

    static final Duration REFETCH_INTERVAL = Duration.ofSeconds(30);
    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Set<JWSAlgorithm> ALLOWED = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256);

    private final Supplier<String> jwksSource;
    private final Clock clock;
    private JWKSet keys;
    private Instant fetchedAt = Instant.MIN;

    public JwtVerifier(Supplier<String> jwksSource, Clock clock) {
        this.jwksSource = jwksSource;
        this.clock = clock;
    }

    /** Parses and verifies the token; throws {@link OidcException} (INVALID_TOKEN or UNAVAILABLE). */
    public JWTClaimsSet verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new OidcException(Kind.INVALID_TOKEN, "token is not a signed JWT");
        }
        JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
        String kid = jwt.getHeader().getKeyID();
        if (!ALLOWED.contains(alg) || kid == null) {
            throw new OidcException(Kind.INVALID_TOKEN, "token algorithm or key id not accepted");
        }
        JWK key = find(kid);
        if (key == null) {
            throw new OidcException(Kind.INVALID_TOKEN, "unknown signing key");
        }
        try {
            JWSVerifier verifier = new DefaultJWSVerifierFactory().createJWSVerifier(jwt.getHeader(), publicKey(key));
            if (!jwt.verify(verifier)) {
                throw new OidcException(Kind.INVALID_TOKEN, "signature does not match");
            }
            return jwt.getJWTClaimsSet();
        } catch (JOSEException | ParseException e) {
            throw new OidcException(Kind.INVALID_TOKEN, "token cannot be verified", e);
        }
    }

    private static java.security.Key publicKey(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsa) {
            return rsa.toPublicKey();
        }
        if (key instanceof ECKey ec) {
            return ec.toPublicKey();
        }
        throw new JOSEException("unsupported key type");
    }

    private synchronized JWK find(String kid) {
        Instant now = clock.instant();
        if (keys == null) {
            load(now);
        } else if (fetchedAt.plus(CACHE_TTL).isBefore(now)) {
            try {
                load(now);
            } catch (OidcException e) {
                // Keycloak is down: keep verifying with the known keys until it returns.
                fetchedAt = now.minus(CACHE_TTL).plus(REFETCH_INTERVAL);
            }
        }
        JWK found = keys.getKeyByKeyId(kid);
        if (found == null && fetchedAt.plus(REFETCH_INTERVAL).isBefore(now)) {
            load(now);
            found = keys.getKeyByKeyId(kid);
        }
        return found;
    }

    private void load(Instant now) {
        String json = jwksSource.get();
        try {
            keys = JWKSet.parse(json);
            fetchedAt = now;
        } catch (ParseException e) {
            throw new OidcException(Kind.UNAVAILABLE, "key set is not valid", e);
        }
    }

    /** Reads a string-list claim that may be absent. */
    static List<String> stringList(JWTClaimsSet claims, String name) {
        try {
            List<String> list = claims.getStringListClaim(name);
            return list == null ? List.of() : list;
        } catch (ParseException e) {
            throw new OidcException(Kind.INVALID_TOKEN, "claim " + name + " has the wrong type");
        }
    }
}
