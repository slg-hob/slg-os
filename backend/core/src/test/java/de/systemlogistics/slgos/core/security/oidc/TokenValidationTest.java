package de.systemlogistics.slgos.core.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import de.systemlogistics.slgos.core.security.oidc.LogoutTokenValidator.LogoutClaims;
import de.systemlogistics.slgos.core.security.oidc.OidcException.Kind;
import de.systemlogistics.slgos.core.testsupport.FakeKeycloak;
import de.systemlogistics.slgos.core.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TokenValidationTest {

    private MutableClock clock;
    private FakeKeycloak keycloak;
    private OidcClient client;
    private JwtVerifier verifier;
    private IdTokenValidator idTokens;
    private LogoutTokenValidator logoutTokens;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atNoon();
        keycloak = new FakeKeycloak(clock::instant);
        client = new OidcClient(new OidcEndpoints(keycloak.issuer()), FakeKeycloak.CLIENT_ID, FakeKeycloak.CLIENT_SECRET,
                Duration.ofSeconds(3), JsonMapper.builder().build());
        verifier = new JwtVerifier(client::fetchJwks, clock);
        idTokens = new IdTokenValidator(verifier, keycloak.issuer(), FakeKeycloak.CLIENT_ID, clock);
        logoutTokens = new LogoutTokenValidator(verifier, keycloak.issuer(), FakeKeycloak.CLIENT_ID, clock);
    }

    @AfterEach
    void tearDown() {
        keycloak.close();
    }

    private void assertInvalid(Runnable action, String messagePart) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OidcException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.INVALID_TOKEN);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    private JWTClaimsSet.Builder claims() {
        return keycloak.idClaims("user-1", "nonce-1");
    }

    // ---- ID tokens

    @Test
    void acceptsAValidIdTokenAndReadsTheClaims() {
        String token = keycloak.sign(claims().claim("groups", List.of("/slg/admins", "/slg/users")).claim("locale", "de")
                .claim("auth_time", clock.instant().getEpochSecond() - 5).claim("sid", "kc-session-1").build());
        IdTokenClaims result = idTokens.validate(token, "nonce-1");
        assertThat(result.subject()).isEqualTo("user-1");
        assertThat(result.sessionId()).isEqualTo("kc-session-1");
        assertThat(result.displayName()).isEqualTo("Name of user-1");
        assertThat(result.locale()).isEqualTo("de");
        assertThat(result.groups()).containsExactly("/slg/admins", "/slg/users");
        assertThat(result.authTime()).isEqualTo(clock.instant().minusSeconds(5));
    }

    @Test
    void fallsBackToThePreferredUsernameAndToNoGroups() {
        String token = keycloak.sign(new JWTClaimsSet.Builder(claims().build()).claim("name", null).claim("groups", null)
                .claim("preferred_username", "alice").build());
        IdTokenClaims result = idTokens.validate(token, null);
        assertThat(result.displayName()).isEqualTo("alice");
        assertThat(result.groups()).isEmpty();
        assertThat(result.authTime()).isNull();
    }

    @Test
    void refusesAWrongNonceOnlyWhenOneIsExpected() {
        String token = keycloak.sign(claims().build());
        assertInvalid(() -> idTokens.validate(token, "other"), "nonce");
        assertThat(idTokens.validate(token, null).subject()).as("refresh: no nonce check").isEqualTo("user-1");
    }

    @Test
    void refusesWrongIssuerAudienceAndAuthorizedParty() {
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().issuer("https://evil.example/realms/slg").build()), null), "issuer");
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().audience("other-client").build()), null), "audience");
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().audience(List.of(FakeKeycloak.CLIENT_ID, "x")).build()), null),
                "authorized party");
        assertThat(idTokens.validate(keycloak.sign(claims().audience(List.of(FakeKeycloak.CLIENT_ID, "x"))
                .claim("azp", FakeKeycloak.CLIENT_ID).build()), null).subject()).isEqualTo("user-1");
    }

    @Test
    void refusesExpiredFutureAndNotYetValidTokens() {
        Instant now = clock.instant();
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().expirationTime(Date.from(now.minusSeconds(61))).build()), null),
                "expired");
        assertThat(idTokens.validate(keycloak.sign(claims().expirationTime(Date.from(now.minusSeconds(30))).build()), null))
                .as("within the clock skew").isNotNull();
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().issueTime(Date.from(now.plusSeconds(120))).build()), null),
                "future");
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().notBeforeTime(Date.from(now.plusSeconds(120))).build()), null),
                "not yet valid");
        assertInvalid(() -> idTokens.validate(keycloak.sign(new JWTClaimsSet.Builder(claims().build()).expirationTime(null).build()),
                null), "expired");
    }

    @Test
    void refusesTokensOfAnotherTypeOrWithoutSubject() {
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().claim("typ", "Bearer").build()), null), "ID token");
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().subject(" ").build()), null), "subject");
    }

    @Test
    void refusesWronglyTypedClaims() {
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().claim("groups", "not-a-list").build()), null), "groups");
        assertInvalid(() -> idTokens.validate(keycloak.sign(claims().claim("name", 5).build()), null), "wrong type");
    }

    // ---- signature handling

    @Test
    void refusesTokensSignedByAnotherKey() {
        RSAKey attacker = FakeKeycloak.generateKey("test-key");
        assertInvalid(() -> idTokens.validate(FakeKeycloak.sign(claims().build(), attacker), null), "signature");
    }

    @Test
    void refusesUnknownKeyIdsAndUnsignedOrHmacTokens() throws Exception {
        RSAKey unknown = FakeKeycloak.generateKey("unknown-kid");
        assertInvalid(() -> idTokens.validate(FakeKeycloak.sign(claims().build(), unknown), null), "unknown signing key");
        assertInvalid(() -> idTokens.validate(new PlainJWT(claims().build()).serialize(), null), "signed JWT");
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(), claims().build());
        hmac.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
        assertInvalid(() -> idTokens.validate(hmac.serialize(), null), "algorithm");
        assertInvalid(() -> idTokens.validate("not.a.jwt", null), "signed JWT");
        SignedJWT noKid = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims().build());
        noKid.sign(new com.nimbusds.jose.crypto.RSASSASigner(keycloak.key()));
        assertInvalid(() -> idTokens.validate(noKid.serialize(), null), "key id");
    }

    @Test
    void tamperedPayloadsFailTheSignature() {
        String token = keycloak.sign(claims().build());
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims().subject("admin").build().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertInvalid(() -> idTokens.validate(parts[0] + "." + forgedPayload + "." + parts[2], null), "");
    }

    @Test
    void keysAreCachedAndRefetchedOnlyForUnknownKeyIdsAtMostEvery30Seconds() {
        String token = keycloak.sign(claims().build());
        idTokens.validate(token, null);
        idTokens.validate(token, null);
        assertThat(keycloak.jwksFetches.get()).isEqualTo(1);

        RSAKey unknown = FakeKeycloak.generateKey("rotated");
        String rotated = FakeKeycloak.sign(claims().build(), unknown);
        assertInvalid(() -> idTokens.validate(rotated, null), "unknown");
        assertThat(keycloak.jwksFetches.get()).as("too soon to refetch").isEqualTo(1);
        clock.advance(Duration.ofSeconds(31));
        assertInvalid(() -> idTokens.validate(rotated, null), "unknown");
        assertThat(keycloak.jwksFetches.get()).as("refetched once for the unknown id").isEqualTo(2);
    }

    @Test
    void knownKeysSurviveAKeycloakOutageAfterTheCacheExpired() {
        String token = keycloak.sign(claims().build());
        idTokens.validate(token, null);
        keycloak.stop();
        clock.advance(JwtVerifier.CACHE_TTL.plusMinutes(1));
        String fresh = keycloak.sign(claims().build());
        assertThat(idTokens.validate(fresh, null).subject()).isEqualTo("user-1");
    }

    @Test
    void anUnreachableKeySetIsReportedAsUnavailableNotInvalid() {
        keycloak.stop();
        assertThatThrownBy(() -> idTokens.validate(keycloak.sign(claims().build()), null))
                .isInstanceOfSatisfying(OidcException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    // ---- logout tokens

    @Test
    void acceptsAValidLogoutTokenWithSidOrSubject() {
        LogoutClaims bySid = logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("kc-1", null).build()));
        assertThat(bySid.sessionId()).isEqualTo("kc-1");
        assertThat(bySid.jti()).isNotBlank();
        LogoutClaims bySub = logoutTokens.validate(keycloak.sign(keycloak.logoutClaims(null, "user-1").build()));
        assertThat(bySub.subject()).isEqualTo("user-1");
    }

    @Test
    void refusesInvalidLogoutTokens() {
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).issuer("https://evil.example").build())),
                "issuer");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).audience("other").build())), "audience");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).claim("events", Map.of()).build())),
                "event");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(new JWTClaimsSet.Builder(keycloak.logoutClaims("s", null).build())
                .claim("events", null).build())), "event");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).claim("nonce", "n").build())), "nonce");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).claim("typ", "ID").build())), "logout token");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims(null, null).build())), "neither sid nor sub");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).jwtID(null).build())), "jti");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).jwtID("j".repeat(201)).build())), "jti");
        assertInvalid(() -> logoutTokens.validate(FakeKeycloak.sign(keycloak.logoutClaims("s", null).build(),
                FakeKeycloak.generateKey("test-key"))), "signature");
    }

    @Test
    void logoutTokensMustBeRecent() {
        Instant now = clock.instant();
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(6)))).build())), "window");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null)
                .issueTime(Date.from(now.plusSeconds(120))).build())), "window");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(new JWTClaimsSet.Builder(keycloak.logoutClaims("s", null).build())
                .issueTime(null).build())), "window");
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null)
                .expirationTime(Date.from(now.minusSeconds(120))).build())), "expired");
    }

    @Test
    void logoutTokenWithWronglyTypedEventsIsRejected() {
        assertInvalid(() -> logoutTokens.validate(keycloak.sign(keycloak.logoutClaims("s", null).claim("events", "x").build())), "wrong type");
    }
}
