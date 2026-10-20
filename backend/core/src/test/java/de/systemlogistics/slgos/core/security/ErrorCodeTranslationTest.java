package de.systemlogistics.slgos.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every core error code has a German and an English text in the shell resources (PRD-0003 SK-2 AC1, SK-11 AC6). */
class ErrorCodeTranslationTest {

    private static JsonNode errors(String language) throws IOException {
        Path file = Path.of("..", "..", "frontend", "shell", "src", "locales", language, "shell.json");
        return JsonMapper.builder().build().readTree(Files.readString(file)).path("errors");
    }

    @Test
    void everyCodeHasATextInBothLanguages() throws IOException {
        for (String language : new String[] {"de", "en"}) {
            JsonNode errors = errors(language);
            for (ErrorCode code : ErrorCode.values()) {
                assertThat(errors.path(code.name()).asString("")).as(language + " " + code).isNotBlank();
            }
        }
    }

    @Test
    void thereAreNoTextsForCodesThatDoNotExist() throws IOException {
        Set<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());
        for (String language : new String[] {"de", "en"}) {
            Set<String> keys = errors(language).propertyNames().stream().collect(Collectors.toSet());
            assertThat(keys).as(language).isEqualTo(codes);
        }
    }

    @Test
    void germanAndEnglishTextsDiffer() throws IOException {
        for (ErrorCode code : ErrorCode.values()) {
            if (code != ErrorCode.CSRF) {
                assertThat(errors("de").path(code.name()).asString()).isNotEqualTo(errors("en").path(code.name()).asString());
            }
        }
    }
}
