package de.systemlogistics.slgos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsAndCidrTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    Path dir;

    @Test
    void readStripsWhitespaceAndFailsWithoutLeakingTheValue() throws IOException {
        Path file = Files.writeString(dir.resolve("s"), "  topsecret \n");
        assertThat(Secrets.read(file.toString())).isEqualTo("topsecret");
        assertThatThrownBy(() -> Secrets.read(dir.resolve("none").toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("none");
    }

    @Test
    void parseKeysAcceptsCommentsAndSeveralKeys() {
        Map<String, byte[]> keys = Secrets.parseKeys("# rotation\nk1=" + KEY + "\n\nk2.b=" + KEY + "\n");
        assertThat(keys).containsOnlyKeys("k1", "k2.b");
        assertThat(keys.get("k1")).hasSize(32);
    }

    @Test
    void parseKeysRejectsBadInput() {
        assertThatThrownBy(() -> Secrets.parseKeys("")).hasMessageContaining("no key");
        assertThatThrownBy(() -> Secrets.parseKeys("k1")).hasMessageContaining("id=base64");
        assertThatThrownBy(() -> Secrets.parseKeys("=" + KEY)).hasMessageContaining("id=base64");
        assertThatThrownBy(() -> Secrets.parseKeys("bad id=" + KEY)).hasMessageContaining("key id");
        assertThatThrownBy(() -> Secrets.parseKeys("k1=%%%")).hasMessageContaining("base64");
        assertThatThrownBy(() -> Secrets.parseKeys("k1=" + Base64.getEncoder().encodeToString(new byte[31])))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> Secrets.parseKeys("k1=" + KEY + "\nk1=" + KEY)).hasMessageContaining("duplicate");
    }

    @Test
    void cidrMatchesInsideAndOutsideTheRange() {
        Cidr net = Cidr.parse("172.20.0.0/16");
        assertThat(net.contains("172.20.5.9")).isTrue();
        assertThat(net.contains("172.21.0.1")).isFalse();
        assertThat(Cidr.parse("10.1.2.3").contains("10.1.2.3")).isTrue();
        assertThat(Cidr.parse("10.1.2.3").contains("10.1.2.4")).isFalse();
        assertThat(Cidr.parse("192.168.1.0/25").contains("192.168.1.127")).isTrue();
        assertThat(Cidr.parse("192.168.1.0/25").contains("192.168.1.128")).isFalse();
        assertThat(Cidr.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
    }

    @Test
    void cidrHandlesIpv6AndTheOtherFamily() {
        Cidr v6 = Cidr.parse("fd00::/8");
        assertThat(v6.contains("fd12::1")).isTrue();
        assertThat(v6.contains("fe80::1")).isFalse();
        assertThat(v6.contains("10.0.0.1")).isFalse();
        assertThat(Cidr.parse("10.0.0.0/8").contains("fd12::1")).isFalse();
    }

    @Test
    void cidrNeverResolvesNamesAndRejectsGarbage() {
        assertThat(Cidr.parse("10.0.0.0/8").contains("localhost")).isFalse();
        assertThat(Cidr.parse("10.0.0.0/8").contains(null)).isFalse();
        assertThatThrownBy(() -> Cidr.parse("example.com/8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Cidr.isIpLiteral("")).isFalse();
    }
}
