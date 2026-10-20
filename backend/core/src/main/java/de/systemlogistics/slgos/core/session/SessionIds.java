package de.systemlogistics.slgos.core.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Session identifiers: 128 random bits in the cookie, only their SHA-256 hash is stored (PRD-0003 SK-3 AC2). */
public final class SessionIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{22}");

    private SessionIds() {}

    public static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** True if the cookie value has the shape of an id, so malformed values never reach the database. */
    public static boolean isWellFormed(String id) {
        return id != null && SHAPE.matcher(id).matches();
    }

    public static byte[] hash(String id) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
