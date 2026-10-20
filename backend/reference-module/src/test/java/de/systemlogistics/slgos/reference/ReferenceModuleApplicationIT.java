package de.systemlogistics.slgos.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReferenceModuleApplicationIT {

    @Autowired
    private ModuleDescriptor descriptor;

    @Test
    void contextLoads() {
        assertThat(descriptor.id()).isEqualTo("reference");
    }
}
