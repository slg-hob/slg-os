package de.systemlogistics.slgos.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The canonical path of a request: path parameters ({@code ;x=1}) removed, percent-encoding decoded once, repeated slashes
 * collapsed and {@code .} and {@code ..} segments resolved. Access decisions must use this path and never the raw request
 * URI, because the servlet container and Spring MVC match on the decoded path ({@code /%61pp} reaches {@code /app}).
 */
public final class RequestPaths {

    /** Returned for paths that cannot be decoded; it matches no allowlist entry and no route. */
    public static final String INVALID = "/\u0000invalid";

    private RequestPaths() {}

    public static String of(HttpServletRequest request) {
        return normalize(request.getRequestURI());
    }

    public static String normalize(String rawUri) {
        if (rawUri == null || rawUri.isEmpty() || rawUri.charAt(0) != '/') {
            return INVALID;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawUri.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return INVALID;
        }
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\u0000') >= 0) {
            return INVALID;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decoded.split("/", -1)) {
            int semicolon = segment.indexOf(';');
            String clean = semicolon < 0 ? segment : segment.substring(0, semicolon);
            if (clean.isEmpty() || clean.equals(".")) {
                continue;
            }
            if (clean.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(clean);
        }
        boolean trailingSlash = decoded.endsWith("/") && !segments.isEmpty();
        return "/" + String.join("/", segments) + (trailingSlash ? "/" : "");
    }
}
