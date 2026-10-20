package de.systemlogistics.slgos.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReturnTargetTest {

    @ParameterizedTest
    @ValueSource(strings = {"/app/reference", "/app/reference/notes/1", "/app/lead-radar/a.b_c~d", "/app/x1/"})
    void acceptsShellPaths(String target) {
        assertThat(ReturnTarget.sanitize(target)).isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/app/x", "//evil.example/app/x", "/\\evil.example", "/app/x/../../etc", "/app/../x",
            "/app/x/%2e%2e/y", "/app/x/%252e%252e/y", "/app//x", "/app/x//y", "/app/x\\y", "/other", "/", "app/x",
            "/app/UPPER", "/app/x?next=http://evil", "/app/x#frag", "/app/x%00", "/app/x%0d%0aSet-Cookie:a=b",
            "javascript:alert(1)", "/app/x y", "/app/x/./y", "%2f%2fevil.example", "/app/%", "/app/x%zz"
    })
    void rejectsEverythingElse(String target) {
        assertThat(ReturnTarget.sanitize(target)).isEqualTo("/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void emptyBecomesTheDefault(String target) {
        assertThat(ReturnTarget.sanitize(target)).isEqualTo(ReturnTarget.DEFAULT);
    }

    @ParameterizedTest
    @CsvSource({"/app/x%2Fy,/app/x/y", "/app/a%2Db,/app/a-b"})
    void decodesExactlyOnce(String raw, String expected) {
        assertThat(ReturnTarget.sanitize(raw)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void rejectsOverlongTargets() {
        assertThat(ReturnTarget.sanitize("/app/" + "a".repeat(600))).isEqualTo("/");
    }
}
