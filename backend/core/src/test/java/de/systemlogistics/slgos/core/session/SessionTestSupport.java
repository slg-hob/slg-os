package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.security.oidc.IdTokenClaims;
import de.systemlogistics.slgos.core.security.oidc.IdTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import de.systemlogistics.slgos.core.security.oidc.OidcTokens;
import de.systemlogistics.slgos.core.security.oidc.Pkce;
import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Base of the session integration tests: creates sessions from real code exchanges against the fake Keycloak. */
abstract class SessionTestSupport extends IntegrationTestBase {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    SessionService sessions;

    @Autowired
    SessionStore store;

    @Autowired
    TokenRefresher refresher;

    @Autowired
    OidcClient oidc;

    @Autowired
    IdTokenValidator idTokens;

    @Autowired
    JdbcClient db;

    /** A tuple of a created session and the tokens that Keycloak issued for it. */
    record Created(SessionService.Created session, OidcTokens tokens, IdTokenClaims claims, String sub) {
        byte[] hash() {
            return session.idHash();
        }
    }

    static String newSub() {
        return "sub-" + COUNTER.incrementAndGet() + "-" + System.nanoTime();
    }

    Created createSession(String sub, String... groups) {
        String verifier = Pkce.randomValue();
        String code = ENV.keycloak.issueCode(ENV.keycloak.user(sub, groups), "nonce-" + sub, Pkce.challenge(verifier),
                ENV.clock.instant());
        OidcTokens tokens = oidc.exchangeCode(code, verifier, "https://shell.slg.test/core/auth/callback");
        IdTokenClaims claims = idTokens.validate(tokens.idToken(), "nonce-" + sub);
        SessionService.Created created = sessions.create(new SessionService.Login(claims.subject(), claims.sessionId(),
                claims.displayName(), claims.locale(), claims.groups(), claims.authTime(), tokens.accessToken(),
                tokens.refreshToken(), tokens.idToken(), tokens.expiresInSeconds()), "10.0.0.1");
        return new Created(created, tokens, claims, sub);
    }

    long auditCount(String type, String actor) {
        return db.sql("SELECT count(*) FROM audit_event WHERE event_type = ? AND actor_id = ?").params(type, actor)
                .query(Long.class).single();
    }

    java.util.List<String> reasons(String actor) {
        return db.sql("SELECT reason FROM audit_event WHERE event_type = 'session.terminated' AND actor_id = ? ORDER BY id")
                .param(actor).query(String.class).list();
    }

    long sessionRows(String sub) {
        return db.sql("SELECT count(*) FROM session WHERE user_id = ?").param(sub).query(Long.class).single();
    }
}
