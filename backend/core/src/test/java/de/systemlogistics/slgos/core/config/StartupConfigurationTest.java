package de.systemlogistics.slgos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.systemlogistics.slgos.core.CoreApplication;
import de.systemlogistics.slgos.core.config.ConfigurationProblemException.ConfigProblem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** The core refuses to start with missing settings and says which ones (PRD-0003 SK-16 AC5). */
class StartupConfigurationTest {

    @Test
    void startWithoutSettingsFailsBeforeAnyBeanExistsAndNamesTheVariables() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE)
                .properties("management.server.port=0").run())
                .isInstanceOf(ConfigurationProblemException.class)
                .hasMessageContaining("SLG_SHELL_BASE_URL").hasMessageContaining("SLG_DB_URL")
                .hasMessageContaining("SLG_OIDC_ISSUER").hasMessageContaining("fehlt").hasMessageContaining("is missing");
    }

    @Test
    void theOperatorMessageIsGermanAndEnglishAndContainsNoStackTrace() {
        ConfigurationProblemException problem = new ConfigurationProblemException(List.of(
                new ConfigProblem("SLG_DB_URL", "fehlt", "is missing"),
                new ConfigProblem("SLG_ENV", "ungültig", "invalid")));
        FailureAnalysis analysis = new ConfigurationFailureAnalyzer().analyze(problem);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("Ungültige Konfiguration / Invalid configuration", "SLG_DB_URL: fehlt / is missing",
                "SLG_ENV: ungültig / invalid");
        assertThat(analysis.getAction()).contains("Setzen Sie", "Set the listed variables");
    }
}
