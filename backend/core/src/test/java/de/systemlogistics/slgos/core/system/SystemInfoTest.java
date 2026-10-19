package de.systemlogistics.slgos.core.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemInfoTest {

    private final SystemInfo info = new SystemInfo();

    @Test
    void reportsTheSdkContractVersion() {
        assertThat(info.contractVersion()).isEqualTo("1.0");
    }

    @Test
    void acceptsOnlyTheCurrentMajor() {
        assertThat(info.accepts("1.0")).isTrue();
        assertThat(info.accepts("2.0")).isFalse();
        assertThat(info.accepts(null)).isFalse();
    }
}
