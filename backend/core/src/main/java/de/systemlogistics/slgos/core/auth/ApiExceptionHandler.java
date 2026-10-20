package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.security.ErrorCode;
import de.systemlogistics.slgos.core.security.ProblemWriter;
import de.systemlogistics.slgos.core.session.AuthUnavailableException;
import de.systemlogistics.slgos.core.session.SessionGoneException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps failures of the core to problem-details with machine-readable codes; no detail text leaves the server. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemWriter problems;

    ApiExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(SessionGoneException.class)
    void sessionGone(HttpServletResponse response) throws IOException {
        problems.write(response, ErrorCode.SESSION_EXPIRED);
    }

    @ExceptionHandler(AuthUnavailableException.class)
    void authUnavailable(HttpServletResponse response) throws IOException {
        problems.write(response, ErrorCode.AUTH_UNAVAILABLE);
    }

    @ExceptionHandler(DataAccessException.class)
    void database(DataAccessException e, HttpServletResponse response) throws IOException {
        LOG.error("database access failed", e);
        problems.write(response, ErrorCode.SESSION_STORE_DOWN);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    void notFound(HttpServletResponse response) throws IOException {
        problems.write(response, ErrorCode.NOT_FOUND);
    }
}
