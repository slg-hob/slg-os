package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.security.ErrorCode;
import de.systemlogistics.slgos.core.security.Logs;
import de.systemlogistics.slgos.core.security.ProblemWriter;
import de.systemlogistics.slgos.core.session.AuthUnavailableException;
import de.systemlogistics.slgos.core.session.SessionGoneException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    void database(Exception e, HttpServletResponse response) throws IOException {
        LOG.error("database access failed: {}", Logs.safe(e));
        problems.write(response, ErrorCode.SESSION_STORE_DOWN);
    }

    /**
     * Framework errors (no handler, wrong method or media type, bad parameter) keep their status and get a code; anything
     * else becomes a generic problem without detail, and the correlation id leads to the log.
     */
    @ExceptionHandler(Exception.class)
    void unexpected(Exception e, HttpServletResponse response) throws IOException {
        if (e instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            problems.write(response, status == 404 ? ErrorCode.NOT_FOUND : ErrorCode.BAD_REQUEST, status);
            return;
        }
        LOG.error("unhandled error: {}", Logs.safe(e), e);
        problems.write(response, ErrorCode.INTERNAL_ERROR);
    }
}
