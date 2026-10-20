package de.systemlogistics.slgos.core.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the tokens of a session with AES-256-GCM (PRD-0003 SK-3 AC6). The key id is stored with the ciphertext so keys
 * can rotate: new data uses the active key, old data stays readable while its key remains in the key file. The session id
 * hash, the key id and the purpose are authenticated data, so a ciphertext cannot be moved to another session or used as a
 * different token. Only classes of this package use the vault (ArchUnit rule).
 */
class TokenVault {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, byte[]> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    TokenVault(Map<String, byte[]> keys, String activeKeyId) {
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("active key id is not in the key set");
        }
        keys.values().forEach(k -> {
            if (k.length != 32) {
                throw new IllegalArgumentException("keys must be 32 bytes");
            }
        });
        this.keys = Map.copyOf(keys);
        this.activeKeyId = activeKeyId;
    }

    /** The id of the key used for new data; the caller stores it next to the ciphertext. */
    String activeKeyId() {
        return activeKeyId;
    }

    /** Encrypts with the active key. */
    byte[] seal(String plaintext, String purpose, byte[] sessionIdHash) {
        String keyId = activeKeyId;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(keyId), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyId, purpose, sessionIdHash));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_LENGTH + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /** Decrypts; fails with {@link IllegalStateException} if the key is unknown or the data was altered or moved. */
    String open(String keyId, byte[] ciphertext, String purpose, byte[] sessionIdHash) {
        byte[] key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("unknown vault key id");
        }
        if (ciphertext.length < IV_LENGTH + TAG_BITS / 8) {
            throw new IllegalStateException("ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LENGTH));
            cipher.updateAAD(aad(keyId, purpose, sessionIdHash));
            return new String(cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    private static byte[] aad(String keyId, String purpose, byte[] sessionIdHash) {
        byte[] id = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] p = purpose.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(sessionIdHash.length + 1 + id.length + 1 + p.length)
                .put(sessionIdHash).put((byte) 0).put(id).put((byte) 0).put(p).array();
    }
}
