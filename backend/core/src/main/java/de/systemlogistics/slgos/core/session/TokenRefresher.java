package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.audit.AuditTypes;
import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.security.oidc.IdTokenClaims;
import de.systemlogistics.slgos.core.security.oidc.IdTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import de.systemlogistics.slgos.core.security.oidc.OidcException;
import de.systemlogistics.slgos.core.security.oidc.OidcTokens;
import de.systemlogistics.slgos.core.session.SessionStore.StoredTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the user's access token fresh (PRD-0003 SK-3 AC6, SK-20 AC1). The token is renewed {@code renewMargin} before expiry
 * under a row lock ({@code FOR UPDATE SKIP LOCKED}), so exactly one grant runs per session even across several core
 * containers: a request that does not get the lock holds no database connection while it waits for the winner's result.
 * Inside one container, waiters reuse the winner's result through a shared future.
 *
 * <p>Failure handling: Keycloak unreachable keeps the session; a refused or lost refresh (the rotating refresh token may be
 * consumed) ends the session with an audit entry.
 */
@Service
public class TokenRefresher {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 50;

    private enum Outcome { FRESH, GONE, UNAVAILABLE, LOCKED_ELSEWHERE }

    private final SessionStore store;
    private final TokenVault vault;
    private final OidcClient oidc;
    private final IdTokenValidator idTokens;
    private final SessionService sessions;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration margin;
    private final Counter failures;
    /** Caps the renewals that hold a database connection while waiting for Keycloak to half of the pool. */
    private final Semaphore renewals;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    TokenRefresher(SessionStore store, TokenVault vault, OidcClient oidc, IdTokenValidator idTokens,
            SessionService sessions, TransactionTemplate tx, Clock clock, SlgProperties properties, MeterRegistry meters) {
        this.store = store;
        this.vault = vault;
        this.oidc = oidc;
        this.idTokens = idTokens;
        this.sessions = sessions;
        this.tx = tx;
        this.clock = clock;
        this.margin = properties.session().renewMargin();
        this.failures = Counter.builder("slg_token_refresh_failures_total").register(meters);
        this.renewals = new Semaphore(Math.max(1, properties.db().poolSize() / 2));
    }

    /**
     * Returns a valid access token of the session, renewing it first if it expires within the margin.
     *
     * @throws SessionGoneException       the session does not exist (any more)
     * @throws AuthUnavailableException   Keycloak cannot be reached and the cached token is no longer valid
     */
    public String freshAccessToken(byte[] idHash) {
        String key = HexFormat.of().formatHex(idHash);
        for (int attempt = 0; attempt < 3; attempt++) {
            StoredTokens tokens = store.readTokens(idHash).orElseThrow(() -> new SessionGoneException("no session"));
            if (isFresh(tokens)) {
                return openOrEnd(idHash, tokens);
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> running = inFlight.putIfAbsent(key, mine);
            if (running == null) {
                try {
                    renew(idHash);
                    mine.complete(null);
                } catch (RuntimeException e) {
                    mine.completeExceptionally(e);
                    throw e;
                } finally {
                    inFlight.remove(key, mine);
                }
            } else {
                awaitOther(running);
            }
        }
        throw new AuthUnavailableException("token could not be renewed");
    }

    private boolean isFresh(StoredTokens tokens) {
        return tokens.accessExpiresAt().minus(margin).isAfter(clock.instant());
    }

    private void awaitOther(CompletableFuture<Void> running) {
        try {
            running.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new AuthUnavailableException("renewal failed", e);
        } catch (TimeoutException e) {
            throw new AuthUnavailableException("renewal took too long", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthUnavailableException("interrupted", e);
        }
    }

    /** Decrypts the cached access token; an unreadable vault entry (retired key, damaged data) ends the session. */
    private String openOrEnd(byte[] idHash, StoredTokens tokens) {
        try {
            return vault.open(tokens.keyId(), tokens.accessToken(), SessionService.PURPOSE_ACCESS, idHash);
        } catch (IllegalStateException e) {
            store.find(idHash).ifPresent(s -> sessions.terminate(s, AuditTypes.VAULT_UNREADABLE));
            throw new SessionGoneException("stored tokens are unreadable");
        }
    }

    private void renew(byte[] idHash) {
        boolean permit;
        try {
            permit = renewals.tryAcquire(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthUnavailableException("interrupted", e);
        }
        if (!permit) {
            throw new AuthUnavailableException("too many renewals are waiting for the identity provider");
        }
        Outcome outcome;
        try {
            outcome = tx.execute(status -> renewLocked(idHash));
        } finally {
            renewals.release();
        }
        switch (outcome == null ? Outcome.UNAVAILABLE : outcome) {
            case FRESH -> { }
            case GONE -> throw new SessionGoneException("session ended during renewal");
            case UNAVAILABLE -> {
                failures.increment();
                throw new AuthUnavailableException("identity provider unavailable");
            }
            case LOCKED_ELSEWHERE -> pollUntilFresh(idHash);
        }
    }

    /** Runs inside the transaction that holds the row lock. */
    private Outcome renewLocked(byte[] idHash) {
        Optional<StoredTokens> locked = store.lockTokens(idHash);
        if (locked.isEmpty()) {
            return store.exists(idHash) ? Outcome.LOCKED_ELSEWHERE : Outcome.GONE;
        }
        StoredTokens current = locked.get();
        if (isFresh(current)) {
            return Outcome.FRESH;
        }
        SessionRecord session = store.find(idHash).orElse(null);
        if (session == null) {
            return Outcome.GONE;
        }
        String refreshToken;
        try {
            refreshToken = vault.open(current.keyId(), current.refreshToken(), SessionService.PURPOSE_REFRESH, idHash);
        } catch (IllegalStateException e) {
            return end(session, AuditTypes.VAULT_UNREADABLE);
        }
        OidcTokens renewed;
        IdTokenClaims claims;
        try {
            renewed = oidc.refresh(refreshToken);
            claims = idTokens.validate(renewed.idToken(), null);
            if (!session.userId().equals(claims.subject())) {
                throw new OidcException(OidcException.Kind.INVALID_TOKEN, "subject changed");
            }
        } catch (OidcException e) {
            return switch (e.kind()) {
                case UNAVAILABLE -> Outcome.UNAVAILABLE;
                case LOST_RESPONSE -> end(session, AuditTypes.REFRESH_LOST);
                case REJECTED, INVALID_TOKEN -> end(session, AuditTypes.REFRESH_REJECTED);
            };
        }
        Instant now = clock.instant();
        StoredTokens next = new StoredTokens(vault.activeKeyId(),
                vault.seal(renewed.refreshToken(), SessionService.PURPOSE_REFRESH, idHash),
                vault.seal(renewed.accessToken(), SessionService.PURPOSE_ACCESS, idHash),
                vault.seal(renewed.idToken(), SessionService.PURPOSE_ID, idHash),
                now.plusSeconds(renewed.expiresInSeconds()));
        int changed = store.updateTokens(idHash, next, claims.groups(), claims.displayName(), claims.locale(), null);
        return changed == 0 ? Outcome.GONE : Outcome.FRESH;
    }

    private Outcome end(SessionRecord session, String reason) {
        failures.increment();
        store.delete(session.idHash()).ifPresent(gone -> sessions.auditTermination(gone, reason));
        return Outcome.GONE;
    }

    /** Another transaction (here or in another container) holds the lock: wait for its result without a connection. */
    private void pollUntilFresh(byte[] idHash) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<StoredTokens> tokens = store.readTokens(idHash);
            if (tokens.isEmpty()) {
                throw new SessionGoneException("session ended during renewal");
            }
            if (isFresh(tokens.get())) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthUnavailableException("interrupted", e);
            }
        }
        throw new AuthUnavailableException("renewal by another instance took too long");
    }
}
