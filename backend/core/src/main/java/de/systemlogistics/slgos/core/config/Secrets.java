package de.systemlogistics.slgos.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads secrets from files (Docker secrets). Values are never logged. */
public final class Secrets {

    private Secrets() {}

    /** Reads a secret file and strips surrounding whitespace. */
    public static String read(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("secret file not readable: " + file, e);
        }
    }

    /**
     * Parses a key file with one {@code id=base64key} entry per line (blank lines and {@code #} comments are ignored).
     * Every key must be exactly 32 bytes (AES-256).
     */
    public static Map<String, byte[]> parseKeys(String content) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                throw new IllegalArgumentException("key line must look like id=base64");
            }
            String id = line.substring(0, eq).strip();
            if (!id.matches("[A-Za-z0-9._-]{1,32}")) {
                throw new IllegalArgumentException("invalid key id");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(line.substring(eq + 1).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("key is not valid base64", e);
            }
            if (key.length != 32) {
                throw new IllegalArgumentException("key must be 32 bytes");
            }
            if (keys.put(id, key) != null) {
                throw new IllegalArgumentException("duplicate key id");
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("no key in file");
        }
        return keys;
    }
}
