package de.systemlogistics.slgos.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.system.SystemInfo;
import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CoreApplicationIT extends IntegrationTestBase {

    @Autowired
    private SystemInfo systemInfo;

    @Test
    void contextLoadsAndWiresTheSdk() {
        assertThat(systemInfo.accepts("1.0")).isTrue();
    }
}
