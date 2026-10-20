package de.systemlogistics.slgos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.security.ProblemWriter;
import de.systemlogistics.slgos.core.session.AuthUnavailableException;
import de.systemlogistics.slgos.core.session.SessionGoneException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new ProblemWriter(JsonMapper.builder().build()));

    @Test
    void sessionAndIdentityProviderFailuresHaveTheirCodes() throws Exception {
        MockHttpServletResponse gone = new MockHttpServletResponse();
        handler.sessionGone(gone);
        assertThat(gone.getStatus()).isEqualTo(401);
        assertThat(gone.getContentAsString()).contains("SESSION_EXPIRED");
        MockHttpServletResponse unavailable = new MockHttpServletResponse();
        handler.authUnavailable(unavailable);
        assertThat(unavailable.getStatus()).isEqualTo(401);
        assertThat(unavailable.getContentAsString()).contains("AUTH_UNAVAILABLE");
        assertThat(new SessionGoneException("x").getMessage()).isEqualTo("x");
        assertThat(new AuthUnavailableException("x", new RuntimeException()).getCause()).isNotNull();
    }

    @Test
    void databaseFailuresAnswer503WithoutDriverText() throws Exception {
        for (Exception e : new Exception[] {new DataAccessResourceFailureException("password=hunter2"),
                new CannotCreateTransactionException("jdbc:postgresql://secret-host/db")}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.database(e, response);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("SESSION_STORE_DOWN").doesNotContain("hunter2", "secret-host");
        }
    }

    @Test
    void anythingElseIsAGenericInternalErrorWithoutDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.unexpected(new IllegalStateException("token=abc"), response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("token=abc");
    }

    @Test
    void frameworkErrorsKeepTheirStatus() throws Exception {
        MockHttpServletResponse notFound = new MockHttpServletResponse();
        handler.unexpected(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND), notFound);
        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(notFound.getContentAsString()).contains("NOT_FOUND");
        MockHttpServletResponse badMethod = new MockHttpServletResponse();
        handler.unexpected(new org.springframework.web.HttpRequestMethodNotSupportedException("PUT"), badMethod);
        assertThat(badMethod.getStatus()).isEqualTo(405);
        assertThat(badMethod.getContentAsString()).contains("BAD_REQUEST");
    }

    @Test
    void theProfileLanguageIsGermanEnglishOrAbsent() {
        assertThat(SessionController.language("de-DE")).isEqualTo("de");
        assertThat(SessionController.language("EN_gb")).isEqualTo("en");
        assertThat(SessionController.language("fr")).isNull();
        assertThat(SessionController.language(null)).isNull();
    }
}
