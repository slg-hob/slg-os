package de.systemlogistics.slgos.core.session;

import java.time.Instant;
import java.util.List;

/**
 * A server-side session without its tokens. The id hash identifies it; the cookie value is never stored.
 *
 * @param sid      Keycloak session id, used to match back-channel logout
 * @param authTime time of the user's last authentication at Keycloak (for step-up checks), may be null
 */
public record SessionRecord(byte[] idHash, String userId, String sid, String displayName, String locale,
        List<String> groups, Instant authTime, Instant createdAt, Instant lastAccess, Instant accessExpiresAt) {}
