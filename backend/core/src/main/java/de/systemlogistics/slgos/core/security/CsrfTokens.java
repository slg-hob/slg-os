package de.systemlogistics.slgos.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-session CSRF token, derived as HMAC(server key, session id hash); nothing extra is stored (PRD-0003 SK-3 AC8).
 * Comparison is constant time.
 */
public class CsrfTokens {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public CsrfTokens(byte[] serverKey) {
        if (serverKey.length < 32) {
            throw new IllegalArgumentException("CSRF key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(serverKey, ALGORITHM);
    }

    /** The token of the session with the given id hash. */
    public String derive(byte[] sessionIdHash) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign("csrf", sessionIdHash));
    }

    /** True when the presented value is the token of that session. */
    public boolean matches(byte[] sessionIdHash, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(derive(sessionIdHash).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC over a domain label and the data; the label keeps CSRF tokens and signed cookies apart. */
    public byte[] sign(String domain, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(("slg-" + domain + "-v1").getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
