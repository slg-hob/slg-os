package de.systemlogistics.slgos.core.testsupport;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the whole core against a real PostgreSQL and the fake Keycloak. One environment and one application context are
 * shared by all subclasses; tests use unique users so they do not depend on each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestBase.TestClock.class)
public abstract class IntegrationTestBase {

    protected static final TestEnvironment ENV = TestEnvironment.create();

    static {
        // System properties exist from the start, so the configuration check (which runs before the context) sees them too.
        ENV.properties().forEach(System::setProperty);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        Clock testClock() {
            return ENV.clock;
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;
}
