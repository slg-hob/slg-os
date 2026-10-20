package de.systemlogistics.slgos.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModuleDescriptorTest {

    private final ModuleDescriptor descriptor = new ModuleDescriptor();

    @Test
    void usesTheModuleIdFromTheManifestRules() {
        assertThat(descriptor.id()).matches("[a-z][a-z0-9-]{2,31}");
    }

    @Test
    void declaresTheSdkContractVersion() {
        assertThat(descriptor.contractVersion()).isEqualTo("1.0");
    }
}
