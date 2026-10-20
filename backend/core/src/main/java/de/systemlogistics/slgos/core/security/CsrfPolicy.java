package de.systemlogistics.slgos.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Request checks against cross-site request forgery (PRD-0003 SK-4 AC1): every state-changing request needs the CSRF
 * header and an {@code Origin} equal to the shell origin; {@code Sec-Fetch-Site} must be {@code same-origin} where present.
 */
public class CsrfPolicy {

    public static final String HEADER = "X-Slg-Csrf";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    private final String shellOrigin;

    public CsrfPolicy(String shellBaseUrl) {
        URI uri = URI.create(shellBaseUrl);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || port == 443 && "https".equalsIgnoreCase(uri.getScheme())
                || port == 80 && "http".equalsIgnoreCase(uri.getScheme());
        this.shellOrigin = (uri.getScheme() + "://" + uri.getHost() + (defaultPort ? "" : ":" + port)).toLowerCase(Locale.ROOT);
    }

    public String shellOrigin() {
        return shellOrigin;
    }

    /** GET and HEAD never change state and need no token. The comparison is exact: {@code get} is not {@code GET}. */
    public static boolean isSafe(String method) {
        return SAFE_METHODS.contains(method);
    }

    /**
     * True when the request passes the origin checks and carries the expected token.
     */
    public boolean accepts(HttpServletRequest request, CsrfTokens tokens, byte[] sessionIdHash) {
        String origin = request.getHeader("Origin");
        if (origin == null || !shellOrigin.equals(origin.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !"same-origin".equals(fetchSite)) {
            return false;
        }
        return tokens.matches(sessionIdHash, request.getHeader(HEADER));
    }
}
