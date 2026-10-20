package de.systemlogistics.slgos.core.config;

import java.util.List;

/** Thrown at start when mandatory settings are missing or invalid; the failure analyzer prints the operator message. */
public class ConfigurationProblemException extends RuntimeException {

    private final transient List<ConfigProblem> problems;

    public ConfigurationProblemException(List<ConfigProblem> problems) {
        super("Ungültige Konfiguration / Invalid configuration: " + problems.stream()
                .map(p -> p.variable() + " (" + p.german() + " / " + p.english() + ")")
                .collect(java.util.stream.Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<ConfigProblem> problems() {
        return problems;
    }

    /** One invalid setting, with the operator text in both languages. */
    public record ConfigProblem(String variable, String german, String english) {}
}
