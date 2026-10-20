package de.systemlogistics.slgos.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestPathsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/app/x|/app/x", "/%61pp/x|/app/x", "/app;jsessionid=1/x|/app/x", "/app/x;a=b|/app/x", "//app//x|/app/x",
            "/app/./x|/app/x", "/app/y/../x|/app/x", "/../../app/x|/app/x", "/core/%69nternal/a|/core/internal/a",
            "/app/x/|/app/x/", "/|/", "/core/%2561pi|/core/%61pi", "/a%20b|/a b"})
    void normalisesToTheDecodedCanonicalPath(String raw, String expected) {
        assertThat(RequestPaths.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "app/x", "/app/%", "/app/%zz", "/app\\x", "/app/%5cx", "/app/%00"})
    void undecodableOrSuspiciousPathsMatchNothing(String raw) {
        assertThat(RequestPaths.normalize(raw)).isEqualTo(RequestPaths.INVALID);
    }

    @Test
    void aNullUriIsInvalid() {
        assertThat(RequestPaths.normalize(null)).isEqualTo(RequestPaths.INVALID);
    }

    @Test
    void theRequestVariantUsesTheRawUri() {
        assertThat(RequestPaths.of(new MockHttpServletRequest("GET", "/%61pp;x/y"))).isEqualTo("/app/y");
    }

    @Test
    void logTextNamesTheClassAndSqlStateButNeverTheMessage() {
        Throwable wrapped = new RuntimeException("outer secret", new SQLException("Failing row contains (secret-bytes)", "23505"));
        assertThat(Logs.safe(wrapped)).isEqualTo("RuntimeException (SQLState 23505)").doesNotContain("secret");
        assertThat(Logs.safe(new IllegalStateException("token=abc"))).isEqualTo("IllegalStateException");
    }
}
