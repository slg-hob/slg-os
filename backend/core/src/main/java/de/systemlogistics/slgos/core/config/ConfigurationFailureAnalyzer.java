package de.systemlogistics.slgos.core.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a configuration problem into a short German and English operator message instead of a stack trace. */
public class ConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<ConfigurationProblemException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationProblemException cause) {
        StringBuilder description = new StringBuilder("Ungültige Konfiguration / Invalid configuration:\n");
        for (ConfigurationProblemException.ConfigProblem p : cause.problems()) {
            description.append("  - ").append(p.variable()).append(": ").append(p.german()).append(" / ")
                    .append(p.english()).append('\n');
        }
        return new FailureAnalysis(description.toString(),
                "Setzen Sie die genannten Variablen (siehe README, Tabelle der Variablen). / "
                        + "Set the listed variables (see the README table of variables).",
                cause);
    }
}
