package de.systemlogistics.slgos.core.security;

import de.systemlogistics.slgos.core.config.SlgProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts only the configured host names (PRD-0003 SK-3 AC2): unknown {@code Host} values get 421. The internal path tree
 * {@code /core/internal/**} answers only for the internal host names and is 404 on the public host.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HostFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/core/internal/";

    private final String shellHost;
    private final List<String> internalHosts;
    private final ProblemWriter problems;

    public HostFilter(SlgProperties properties, ProblemWriter problems) {
        this.shellHost = URI.create(properties.shellBaseUrl()).getHost().toLowerCase(Locale.ROOT);
        this.internalHosts = properties.internalHosts().stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = hostName(request.getHeader("Host"));
        boolean shell = shellHost.equals(host);
        boolean internal = internalHosts.contains(host);
        String path = RequestPaths.of(request);
        boolean internalPath = path.equals("/core/internal") || path.startsWith(INTERNAL_PREFIX);
        if (!shell && !internal) {
            problems.write(response, ErrorCode.MISDIRECTED_HOST);
            return;
        }
        if (internalPath && !internal) {
            problems.write(response, ErrorCode.NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The host name of a Host header value, lower case and without port; empty if absent or malformed. */
    static String hostName(String header) {
        if (header == null) {
            return "";
        }
        String value = header.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? "" : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }
}
