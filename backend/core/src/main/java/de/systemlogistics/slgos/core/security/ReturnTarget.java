package de.systemlogistics.slgos.core.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Validates the post-login return target (PRD-0003 SK-3 AC2). Only shell paths below {@code /app/<module>} qualify; the
 * value is decoded once, so encoded, double-encoded, backslash and {@code //} tricks all end at {@code /}.
 */
public final class ReturnTarget {

    public static final String DEFAULT = "/";
    private static final Pattern ALLOWED = Pattern.compile("^/app/[a-z0-9-]+(/[A-Za-z0-9._~/-]*)?$");

    private ReturnTarget() {}

    /** The safe target for the raw value, or {@code /} if the value is not acceptable. */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 512) {
            return DEFAULT;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
        if (decoded.contains("\\") || decoded.contains("//") || !ALLOWED.matcher(decoded).matches()) {
            return DEFAULT;
        }
        for (String segment : decoded.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return DEFAULT;
            }
        }
        return decoded;
    }
}
