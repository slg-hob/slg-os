package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.session.AuthUnavailableException;
import de.systemlogistics.slgos.core.session.SessionContext;
import de.systemlogistics.slgos.core.session.SessionRecord;
import de.systemlogistics.slgos.core.session.SessionService;
import de.systemlogistics.slgos.core.session.TokenRefresher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session profile and activity touch (PRD-0003 SK-3 AC4, AC8). Never returns a token of any kind. */
@RestController
class SessionController {

    private final PermissionService permissions;
    private final SessionService sessions;
    private final TokenRefresher refresher;
    private final SlgProperties.Session settings;
    private final Clock clock;

    SessionController(PermissionService permissions, SessionService sessions, TokenRefresher refresher,
            SlgProperties properties, Clock clock) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.refresher = refresher;
        this.settings = properties.session();
        this.clock = clock;
    }

    /** The current user: pseudonymous id, profile, effective permissions and the CSRF token of this session. */
    record SessionView(String sub, Profile profile, List<String> permissions, String csrfToken, Timing timing) {}

    record Profile(String displayName, String locale, String theme) {}

    /** Deadlines for the idle warning (UI-13) and the server time to correct clock differences. */
    record Timing(Instant serverTime, Instant idleExpiresAt, Instant absoluteExpiresAt) {}

    @GetMapping("/core/api/v1/session")
    SessionView session(HttpServletRequest request) {
        SessionContext context = SessionContext.of(request);
        SessionRecord session = context.session();
        return new SessionView(session.userId(),
                new Profile(session.displayName(), language(session.locale()), "system"),
                List.copyOf(new TreeSet<>(permissions.effective(session.groups()))),
                context.csrfToken(),
                new Timing(clock.instant(), session.lastAccess().plus(settings.idleTimeout()),
                        session.createdAt().plus(settings.absoluteTimeout())));
    }

    /** The language of the profile: {@code de} or {@code en}, else null so the client falls back to its own rules. */
    static String language(String locale) {
        if (locale == null) {
            return null;
        }
        String base = locale.toLowerCase(java.util.Locale.ROOT).split("[-_]")[0];
        return "de".equals(base) || "en".equals(base) ? base : null;
    }

    /**
     * User activity (pointer or keyboard, throttled by the UI): resets the idle timer and renews the tokens if due.
     * A temporarily unreachable identity provider does not fail the touch.
     */
    @PostMapping("/core/api/v1/session/touch")
    ResponseEntity<Void> touch(HttpServletRequest request) {
        SessionRecord session = SessionContext.of(request).session();
        sessions.touch(session, true);
        try {
            refresher.freshAccessToken(session.idHash());
        } catch (AuthUnavailableException e) {
            // The session stays valid; the gateway answers AUTH_UNAVAILABLE once a module token is needed.
        }
        return ResponseEntity.noContent().build();
    }
}
