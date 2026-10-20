package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.security.CsrfPolicy;
import de.systemlogistics.slgos.core.security.CsrfTokens;
import de.systemlogistics.slgos.core.security.ErrorCode;
import de.systemlogistics.slgos.core.security.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF check for every state-changing request of a session (PRD-0003 SK-4 AC1): the {@code X-Slg-Csrf} header must be the
 * session's token and {@code Origin} must be the shell origin. GET and HEAD never change state and pass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class CsrfFilter extends OncePerRequestFilter {

    private final CsrfPolicy policy;
    private final CsrfTokens tokens;
    private final ProblemWriter problems;

    public CsrfFilter(CsrfPolicy policy, CsrfTokens tokens, ProblemWriter problems) {
        this.policy = policy;
        this.tokens = tokens;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SessionFilter.requiresSession(request.getRequestURI()) || CsrfPolicy.isSafe(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SessionContext context = SessionContext.of(request);
        if (!policy.accepts(request, tokens, context.session().idHash())) {
            problems.write(response, ErrorCode.CSRF);
            return;
        }
        chain.doFilter(request, response);
    }
}
