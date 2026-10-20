package de.systemlogistics.slgos.core.health;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * A failing migration keeps readiness DOWN while the process lives and liveness stays UP, so an older container keeps
 * serving (PRD-0003 SK-20 AC2). The broken scripts do not match the applied history, so Flyway refuses before changing anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "slg.db.migration-location=classpath:db/broken")
class FailedMigrationReadinessIT extends IntegrationTestBase {

    @Autowired
    Environment environment;

    private HttpResponse<String> management(String path) throws Exception {
        String port = environment.getProperty("local.management.port");
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessIsDownButLivenessIsUp() throws Exception {
        HttpResponse<String> readiness = management("/actuator/health/readiness");
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(management("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }
}
