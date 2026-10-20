package de.systemlogistics.slgos.core.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access to the session table. All statements are explicit (PRD-0003 D6). */
@Repository
class SessionStore {

    private static final String COLUMNS = """
            id_hash, user_id, sid, display_name, locale, groups, auth_time, created_at, last_access, access_expires_at""";

    private static final RowMapper<SessionRecord> MAPPER = SessionStore::map;

    /** The encrypted token columns of one session. */
    record StoredTokens(String keyId, byte[] refreshToken, byte[] accessToken, byte[] idToken, Instant accessExpiresAt) {}

    /** Data of a session to insert. */
    record NewRow(byte[] idHash, String userId, String sid, String displayName, String locale, List<String> groups,
            Instant authTime, Instant now, StoredTokens tokens) {}

    private final JdbcClient jdbc;

    SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(NewRow row) {
        jdbc.sql("""
                INSERT INTO session (id_hash, user_id, sid, display_name, locale, groups, auth_time, created_at, last_access,
                                     access_expires_at, vault_key_id, refresh_token_ct, access_token_ct, id_token_ct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(row.idHash(), row.userId(), row.sid(), row.displayName(), row.locale(),
                        row.groups().toArray(String[]::new), time(row.authTime()), time(row.now()), time(row.now()),
                        time(row.tokens().accessExpiresAt()), row.tokens().keyId(), row.tokens().refreshToken(),
                        row.tokens().accessToken(), row.tokens().idToken())
                .update();
    }

    Optional<SessionRecord> find(byte[] idHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session WHERE id_hash = ?").param(idHash).query(MAPPER).optional();
    }

    void touch(byte[] idHash, Instant now) {
        jdbc.sql("UPDATE session SET last_access = ? WHERE id_hash = ?").params(time(now), idHash).update();
    }

    /** Deletes the session and returns it (so exactly one caller writes the audit entry). */
    Optional<SessionRecord> delete(byte[] idHash) {
        return jdbc.sql("DELETE FROM session WHERE id_hash = ? RETURNING " + COLUMNS).param(idHash).query(MAPPER).optional();
    }

    List<SessionRecord> deleteBySid(String sid) {
        return jdbc.sql("DELETE FROM session WHERE sid = ? RETURNING " + COLUMNS).param(sid).query(MAPPER).list();
    }

    List<SessionRecord> deleteByUser(String userId) {
        return jdbc.sql("DELETE FROM session WHERE user_id = ? RETURNING " + COLUMNS).param(userId).query(MAPPER).list();
    }

    /** Deletes the oldest sessions of a user so that, together with {@code current}, at most {@code keep} remain. */
    List<SessionRecord> deleteBeyond(String userId, int keep, byte[] current) {
        return jdbc.sql("""
                DELETE FROM session WHERE id_hash IN (
                    SELECT id_hash FROM session WHERE user_id = ? AND id_hash <> ? ORDER BY created_at DESC OFFSET ?)
                """ + " RETURNING " + COLUMNS).params(userId, current, Math.max(keep - 1, 0)).query(MAPPER).list();
    }

    List<SessionRecord> deleteExpired(Instant idleCutoff, Instant absoluteCutoff) {
        return jdbc.sql("DELETE FROM session WHERE last_access < ? OR created_at < ? RETURNING " + COLUMNS)
                .params(time(idleCutoff), time(absoluteCutoff)).query(MAPPER).list();
    }

    long count() {
        return jdbc.sql("SELECT count(*) FROM session").query(Long.class).single();
    }

    /** Access token expiry, without locking. */
    Optional<Instant> accessExpiry(byte[] idHash) {
        return jdbc.sql("SELECT access_expires_at FROM session WHERE id_hash = ?").param(idHash)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).optional();
    }

    boolean exists(byte[] idHash) {
        return jdbc.sql("SELECT count(*) FROM session WHERE id_hash = ?").param(idHash).query(Long.class).single() > 0;
    }

    /**
     * Locks the session row for a token refresh, or returns empty if it is gone or locked by another transaction (also of
     * another core container). Must run inside a transaction.
     */
    Optional<StoredTokens> lockTokens(byte[] idHash) {
        return jdbc.sql("""
                SELECT vault_key_id, refresh_token_ct, access_token_ct, id_token_ct, access_expires_at
                FROM session WHERE id_hash = ? FOR UPDATE SKIP LOCKED""").param(idHash)
                .query((rs, n) -> new StoredTokens(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .optional();
    }

    Optional<StoredTokens> readTokens(byte[] idHash) {
        return jdbc.sql("""
                SELECT vault_key_id, refresh_token_ct, access_token_ct, id_token_ct, access_expires_at
                FROM session WHERE id_hash = ?""").param(idHash)
                .query((rs, n) -> new StoredTokens(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .optional();
    }

    void updateTokens(byte[] idHash, StoredTokens tokens, List<String> groups, String displayName, String locale,
            Instant authTime) {
        jdbc.sql("""
                UPDATE session SET vault_key_id = ?, refresh_token_ct = ?, access_token_ct = ?, id_token_ct = ?,
                       access_expires_at = ?, groups = ?, display_name = COALESCE(?, display_name),
                       locale = COALESCE(?, locale), auth_time = COALESCE(?, auth_time)
                WHERE id_hash = ?""")
                .params(tokens.keyId(), tokens.refreshToken(), tokens.accessToken(), tokens.idToken(),
                        time(tokens.accessExpiresAt()), groups.toArray(String[]::new), displayName, locale, time(authTime),
                        idHash)
                .update();
    }

    /** The latest live session of a user (by last access), for permission lookups of background callers. */
    Optional<SessionRecord> latestOfUser(String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session WHERE user_id = ? ORDER BY last_access DESC LIMIT 1")
                .param(userId).query(MAPPER).optional();
    }

    boolean markJtiSeen(String jti, Instant now) {
        return jdbc.sql("INSERT INTO logout_jti (jti, seen_at) VALUES (?, ?) ON CONFLICT (jti) DO NOTHING")
                .params(jti, time(now)).update() == 1;
    }

    int purgeJti(Instant olderThan) {
        return jdbc.sql("DELETE FROM logout_jti WHERE seen_at < ?").param(time(olderThan)).update();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static SessionRecord map(ResultSet rs, int row) throws SQLException {
        String[] groups = (String[]) rs.getArray("groups").getArray();
        OffsetDateTime authTime = rs.getObject("auth_time", OffsetDateTime.class);
        return new SessionRecord(rs.getBytes("id_hash"), rs.getString("user_id"), rs.getString("sid"),
                rs.getString("display_name"), rs.getString("locale"), List.of(groups),
                authTime == null ? null : authTime.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_access", OffsetDateTime.class).toInstant(),
                rs.getObject("access_expires_at", OffsetDateTime.class).toInstant());
    }
}
