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

/** Health and metrics on the internal management port (PRD-0003 SK-16 AC1, AC4, SK-20 AC2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorIT extends IntegrationTestBase {

    @Autowired
    Environment environment;

    @Autowired
    OidcAvailability availability;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> management(String path) throws Exception {
        String port = environment.getProperty("local.management.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessAndReadinessAreUpAfterAMigratedStart() throws Exception {
        assertThat(management("/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
        HttpResponse<String> readiness = management("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessIsDegradedButStillServedWhileKeycloakIsUnreachable() throws Exception {
        availability.report(false);
        try {
            HttpResponse<String> readiness = management("/actuator/health/readiness");
            assertThat(readiness.statusCode()).as("DEGRADED maps to 200, the core keeps serving").isEqualTo(200);
            assertThat(readiness.body()).contains("\"status\":\"DEGRADED\"");
        } finally {
            availability.report(true);
        }
        assertThat(management("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusExposesTheSessionMetrics() throws Exception {
        String body = management("/actuator/prometheus").body();
        assertThat(body).contains("slg_session_active", "slg_token_refresh_failures_total");
    }

    @Test
    void healthDetailsAreNotExposed() throws Exception {
        assertThat(management("/actuator/health").body()).doesNotContain("jdbc", "components", "details");
    }

    @Test
    void theMainPortServesNoActuatorEndpoints() throws Exception {
        String port = environment.getProperty("local.server.port");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("\"status\":\"UP\"");
    }

    @Test
    void indicatorsReportMigrationAndSignInStates() {
        de.systemlogistics.slgos.core.db.MigrationState state = new de.systemlogistics.slgos.core.db.MigrationState();
        MigrationHealthIndicator indicator = new MigrationHealthIndicator(state);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        state.migrated(1);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        state.failed("Boom");
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(indicator.health().getDetails()).containsEntry("migration", "failed");
    }
}
