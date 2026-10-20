package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.audit.AuditEvent;
import de.systemlogistics.slgos.core.audit.AuditService;
import de.systemlogistics.slgos.core.audit.AuditTypes;
import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.session.SessionStore.NewRow;
import de.systemlogistics.slgos.core.session.SessionStore.StoredTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, resolves and ends server-side sessions (PRD-0003 SK-3). Every state change and its audit entry commit together,
 * and a session is deleted by exactly one caller (DELETE ... RETURNING), so each ending writes exactly one audit row.
 */
@Service
public class SessionService {

    /** Last access is written at most this often by ordinary requests; an explicit touch always writes. */
    static final Duration ACCESS_WRITE_INTERVAL = Duration.ofSeconds(30);

    static final String PURPOSE_REFRESH = "refresh";
    static final String PURPOSE_ACCESS = "access";
    static final String PURPOSE_ID = "id";

    private final SessionStore store;
    private final TokenVault vault;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SlgProperties.Session settings;

    SessionService(SessionStore store, TokenVault vault, AuditService audit, TransactionTemplate tx, Clock clock,
            SlgProperties properties) {
        this.store = store;
        this.vault = vault;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.settings = properties.session();
    }

    /** What a login delivers to a new session. */
    public record Login(String userId, String sid, String displayName, String locale, List<String> groups,
            Instant authTime, String accessToken, String refreshToken, String idToken, long accessExpiresInSeconds) {}

    /** The new session: the cookie value goes to the browser, the hash identifies it on the server. */
    public record Created(String cookieValue, byte[] idHash) {}

    /** Creates a session with a fresh id, applies the per-user cap and writes the login audit entry. */
    public Created create(Login login, String sourceIp) {
        String id = SessionIds.newId();
        byte[] hash = SessionIds.hash(id);
        Instant now = clock.instant();
        StoredTokens tokens = new StoredTokens(vault.activeKeyId(),
                vault.seal(login.refreshToken(), PURPOSE_REFRESH, hash),
                vault.seal(login.accessToken(), PURPOSE_ACCESS, hash),
                vault.seal(login.idToken(), PURPOSE_ID, hash),
                now.plusSeconds(login.accessExpiresInSeconds()));
        tx.executeWithoutResult(status -> {
            store.insert(new NewRow(hash, login.userId(), login.sid(), login.displayName(), login.locale(),
                    login.groups(), login.authTime(), now, tokens));
            audit.record(AuditEvent.success(AuditTypes.LOGIN, login.userId(), sourceIp));
            for (SessionRecord dropped : store.deleteBeyond(login.userId(), settings.maxPerUser(), hash)) {
                auditTermination(dropped, AuditTypes.SESSION_CAP);
            }
        });
        return new Created(id, hash);
    }

    /**
     * Finds the live session for a cookie value. A session past its idle or absolute timeout is ended here and reported as
     * absent, so the caller answers {@code SESSION_EXPIRED}.
     */
    public Optional<SessionRecord> resolve(String cookieValue) {
        if (!SessionIds.isWellFormed(cookieValue)) {
            return Optional.empty();
        }
        Optional<SessionRecord> found = store.find(SessionIds.hash(cookieValue));
        if (found.isEmpty()) {
            return found;
        }
        SessionRecord session = found.get();
        Instant now = clock.instant();
        if (!session.createdAt().plus(settings.absoluteTimeout()).isAfter(now)) {
            terminate(session, AuditTypes.ABSOLUTE_TIMEOUT);
            return Optional.empty();
        }
        if (!session.lastAccess().plus(settings.idleTimeout()).isAfter(now)) {
            terminate(session, AuditTypes.IDLE_TIMEOUT);
            return Optional.empty();
        }
        return found;
    }

    /**
     * Records user activity. Background requests never call this; ordinary requests write at most every
     * {@link #ACCESS_WRITE_INTERVAL}; an explicit touch ({@code force}) always writes.
     */
    public void touch(SessionRecord session, boolean force) {
        Instant now = clock.instant();
        if (force || !session.lastAccess().plus(ACCESS_WRITE_INTERVAL).isAfter(now)) {
            store.touch(session.idHash(), now);
        }
    }

    /** The ID token for the logout hint; null if the session is already gone. */
    public String idToken(SessionRecord session) {
        return store.readTokens(session.idHash())
                .map(t -> vault.open(t.keyId(), t.idToken(), PURPOSE_ID, session.idHash()))
                .orElse(null);
    }

    /** Ends the session at the user's request and writes the logout entry. */
    public void logout(SessionRecord session, String sourceIp) {
        tx.executeWithoutResult(status -> store.delete(session.idHash())
                .ifPresent(gone -> audit.record(AuditEvent.success(AuditTypes.LOGOUT, gone.userId(), sourceIp))));
    }

    /** Removes a session that a new login of the same browser replaces; no audit entry (the login is audited). */
    public void discard(SessionRecord session) {
        store.delete(session.idHash());
    }

    /** Ends the session on the server's decision and writes the termination entry with the reason. */
    public void terminate(SessionRecord session, String reason) {
        tx.executeWithoutResult(status -> store.delete(session.idHash()).ifPresent(gone -> auditTermination(gone, reason)));
    }

    /** Deletes every session past a timeout and the old replay markers. Run hourly (PRD-0001 8.9). */
    public int purgeExpired() {
        Instant now = clock.instant();
        Integer ended = tx.execute(status -> {
            List<SessionRecord> gone = store.deleteExpired(now.minus(settings.idleTimeout()),
                    now.minus(settings.absoluteTimeout()));
            for (SessionRecord s : gone) {
                boolean absolute = !s.createdAt().plus(settings.absoluteTimeout()).isAfter(now);
                auditTermination(s, absolute ? AuditTypes.ABSOLUTE_TIMEOUT : AuditTypes.IDLE_TIMEOUT);
            }
            store.purgeJti(now.minus(Duration.ofHours(1)));
            return gone.size();
        });
        return ended == null ? 0 : ended;
    }

    /** Number of live session rows (metric). */
    public long count() {
        return store.count();
    }

    /** The most recently used live session of a user, if any (permission lookups, PRD-0003 SK-3 AC7). */
    public Optional<SessionRecord> latestOfUser(String userId) {
        Instant now = clock.instant();
        return store.latestOfUser(userId, now.minus(settings.idleTimeout()), now.minus(settings.absoluteTimeout()));
    }

    /** Marks the state of a finished login as used; false if it was used before (replay across containers). */
    public boolean markLoginStateUsed(String state) {
        return store.markJtiSeen("login-state:" + state, clock.instant());
    }

    /**
     * Applies a validated back-channel logout token: records its {@code jti} and ends the matching sessions in one
     * transaction, so a failure does not burn the id and Keycloak's retry still works.
     *
     * @return the number of sessions ended (0 for an unknown session, which is not an error), or empty for a replayed token
     */
    public Optional<Integer> applyLogoutToken(String jti, String sid, String subject) {
        return Optional.ofNullable(tx.execute(status -> {
            if (!store.markJtiSeen(jti, clock.instant())) {
                return null;
            }
            boolean hasSid = sid != null && !sid.isBlank();
            List<SessionRecord> gone = hasSid ? store.deleteBySid(sid, subject == null ? "" : subject) : store.deleteByUser(subject);
            gone.forEach(s -> auditTermination(s, AuditTypes.BACKCHANNEL_LOGOUT));
            return gone.size();
        }));
    }

    /** Called inside a transaction. */
    void auditTermination(SessionRecord session, String reason) {
        audit.record(new AuditEvent(AuditTypes.SESSION_TERMINATED, session.userId(), null, session.userId(),
                AuditEvent.Outcome.SUCCESS, reason));
    }
}
