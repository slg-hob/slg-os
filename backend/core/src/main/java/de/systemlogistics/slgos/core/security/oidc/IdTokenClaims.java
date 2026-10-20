package de.systemlogistics.slgos.core.security.oidc;

import java.time.Instant;
import java.util.List;

/**
 * The validated claims of an ID token that the session needs.
 *
 * @param groups   full group paths from the group claim, e.g. {@code /slg/admins}
 * @param authTime time of the user's last authentication, null if the token has none
 */
public record IdTokenClaims(String subject, String sessionId, String displayName, String locale, List<String> groups,
        Instant authTime) {}
