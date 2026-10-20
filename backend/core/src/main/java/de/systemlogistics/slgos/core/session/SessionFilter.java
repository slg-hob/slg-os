package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.security.CsrfTokens;
import de.systemlogistics.slgos.core.security.Logs;
import de.systemlogistics.slgos.core.security.ErrorCode;
import de.systemlogistics.slgos.core.security.ProblemWriter;
import de.systemlogistics.slgos.core.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a live session for every path except the allowlist in {@link #requiresSession(String)}.
 * No session: {@code /app/**} redirects to the login, everything else answers 401 SESSION_EXPIRED. The session store being
 * down answers 503 SESSION_STORE_DOWN and never falls back to an unauthenticated path (PRD-0003 SK-5 AC5). Requests marked
 * {@code X-Slg-Background} do not count as activity (SK-3 AC4).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class SessionFilter extends OncePerRequestFilter {

    public static final String BACKGROUND_HEADER = "X-Slg-Background";
    private static final Logger LOG = LoggerFactory.getLogger(SessionFilter.class);

    private final SessionService sessions;
    private final CsrfTokens csrfTokens;
    private final ProblemWriter problems;
    private final String shellBaseUrl;

    public SessionFilter(SessionService sessions, CsrfTokens csrfTokens, ProblemWriter problems, SlgProperties properties) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.problems = problems;
        this.shellBaseUrl = properties.shellBaseUrl().replaceAll("/+$", "");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !requiresSession(RequestPaths.of(request));
    }

    /**
     * Deny by default: every canonical path needs a session except this allowlist (sign-in pages and assets, the internal
     * tree that authenticates by token, the favicon). Evaluated on {@link RequestPaths}, never on the raw URI.
     */
    static boolean requiresSession(String path) {
        boolean open = path.equals("/core/auth/login") || path.equals("/core/auth/callback")
                || path.equals("/core/auth/signed-out") || path.equals("/core/auth/popup-done")
                || path.startsWith("/core/auth/assets/") || path.equals("/core/internal")
                || path.startsWith("/core/internal/") || path.equals("/favicon.ico");
        return !open;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String cookie = SessionCookie.read(request);
        Optional<SessionRecord> session;
        try {
            session = cookie == null ? Optional.empty() : sessions.resolve(cookie);
            if (session.isPresent() && request.getHeader(BACKGROUND_HEADER) == null) {
                sessions.touch(session.get(), false);
            }
        } catch (DataAccessException | TransactionException e) {
            LOG.error("session store unavailable: {}", Logs.safe(e));
            problems.write(response, ErrorCode.SESSION_STORE_DOWN);
            return;
        }
        if (session.isEmpty()) {
            if (cookie != null) {
                response.addHeader(HttpHeaders.SET_COOKIE, SessionCookie.clear());
            }
            if (isPageRequest(request)) {
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader(HttpHeaders.LOCATION, shellBaseUrl + "/core/auth/login?return="
                        + URLEncoder.encode(RequestPaths.of(request), StandardCharsets.UTF_8));
            } else {
                problems.write(response, ErrorCode.SESSION_EXPIRED);
            }
            return;
        }
        SessionRecord record = session.get();
        request.setAttribute(SessionContext.ATTRIBUTE, new SessionContext(record, csrfTokens.derive(record.idHash())));
        chain.doFilter(request, response);
    }

    private static boolean isPageRequest(HttpServletRequest request) {
        String path = RequestPaths.of(request);
        return "GET".equals(request.getMethod()) && (path.equals("/app") || path.startsWith("/app/"));
    }
}
