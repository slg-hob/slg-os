package de.systemlogistics.slgos.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Baseline response headers of the core. The proxy adds the full set of PRD-0001 NFR-1(b) on top. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        String path = RequestPaths.of(request);
        if (path.startsWith("/core/auth/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=3600");
        } else if (path.startsWith("/core/") || path.startsWith("/app/") || path.equals("/app")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
