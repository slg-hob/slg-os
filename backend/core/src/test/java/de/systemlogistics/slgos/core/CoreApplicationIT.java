package de.systemlogistics.slgos.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.system.SystemInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CoreApplicationIT {

    @Autowired
    private SystemInfo systemInfo;

    @Test
    void contextLoadsAndWiresTheSdk() {
        assertThat(systemInfo.accepts("1.0")).isTrue();
    }
}
