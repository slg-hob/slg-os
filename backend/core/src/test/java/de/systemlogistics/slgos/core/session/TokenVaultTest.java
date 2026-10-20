package de.systemlogistics.slgos.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenVaultTest {

    private static final byte[] KEY_1 = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_2 = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SESSION = SessionIds.hash("session-one-00000000");
    private static final byte[] OTHER_SESSION = SessionIds.hash("session-two-00000000");

    private final TokenVault vault = new TokenVault(Map.of("k1", KEY_1), "k1");

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        byte[] sealed = vault.seal("the-refresh-token", "refresh", SESSION);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("the-refresh-token");
        assertThat(vault.open("k1", sealed, "refresh", SESSION)).isEqualTo("the-refresh-token");
    }

    @Test
    void everySealingUsesAFreshNonce() {
        assertThat(vault.seal("same", "refresh", SESSION)).isNotEqualTo(vault.seal("same", "refresh", SESSION));
    }

    @Test
    void aCiphertextCannotMoveToAnotherSessionOrPurpose() {
        byte[] sealed = vault.seal("token", "refresh", SESSION);
        assertThatThrownBy(() -> vault.open("k1", sealed, "refresh", OTHER_SESSION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.open("k1", sealed, "access", SESSION)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedOrTruncatedDataIsRefused() {
        byte[] sealed = vault.seal("token", "refresh", SESSION);
        byte[] flipped = sealed.clone();
        flipped[flipped.length - 1] ^= 1;
        assertThatThrownBy(() -> vault.open("k1", flipped, "refresh", SESSION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.open("k1", Arrays.copyOf(sealed, 10), "refresh", SESSION))
                .hasMessageContaining("too short");
    }

    @Test
    void rotationKeepsOldDataReadableAndSealsNewDataWithTheNewKey() {
        byte[] old = vault.seal("old-token", "id", SESSION);
        TokenVault rotated = new TokenVault(Map.of("k1", KEY_1, "k2", KEY_2), "k2");
        assertThat(rotated.activeKeyId()).isEqualTo("k2");
        assertThat(rotated.open("k1", old, "id", SESSION)).as("written with the retired key").isEqualTo("old-token");
        byte[] fresh = rotated.seal("new-token", "id", SESSION);
        assertThat(rotated.open("k2", fresh, "id", SESSION)).isEqualTo("new-token");
        assertThatThrownBy(() -> vault.open("k2", fresh, "id", SESSION)).as("old config lacks the new key")
                .hasMessageContaining("unknown vault key");
        assertThatThrownBy(() -> rotated.open("k1", fresh, "id", SESSION)).as("wrong key id for the data")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnInvalidKeySet() {
        assertThatThrownBy(() -> new TokenVault(Map.of("k1", KEY_1), "missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenVault(Map.of("k1", new byte[16]), "k1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionIdsHaveTheExpectedShape() {
        String id = SessionIds.newId();
        assertThat(id).hasSize(22).matches("[A-Za-z0-9_-]+").isNotEqualTo(SessionIds.newId());
        assertThat(SessionIds.isWellFormed(id)).isTrue();
        for (String bad : new String[] {null, "", "short", id + "x", "!".repeat(22), id.substring(1) + "\n"}) {
            assertThat(SessionIds.isWellFormed(bad)).as(String.valueOf(bad)).isFalse();
        }
        assertThat(SessionIds.hash(id)).hasSize(32).isEqualTo(SessionIds.hash(id)).isNotEqualTo(SessionIds.hash(id + "a"));
    }
}
