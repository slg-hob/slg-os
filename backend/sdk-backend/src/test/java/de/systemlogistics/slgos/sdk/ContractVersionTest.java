package de.systemlogistics.slgos.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ContractVersionTest {

    @Test
    void currentVersionIsSupported() {
        assertThat(ContractVersion.isSupported(ContractVersion.CURRENT)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "2.0", "0.9", "1", "1.0.0", "x.y", " 1.0", "1.-1", "99999.0"})
    void otherVersionsAreRejected(String declared) {
        assertThat(ContractVersion.isSupported(declared)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingVersionIsRejected(String declared) {
        assertThat(ContractVersion.isSupported(declared)).isFalse();
    }
}
