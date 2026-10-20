package de.systemlogistics.slgos.core.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes problem-details responses (RFC 9457) with a machine-readable code and the correlation id; no detail text. */
@Component
public class ProblemWriter {

    private final JsonMapper mapper;

    public ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        write(response, code, code.status());
    }

    /** Writes the problem with the status of the framework exception that caused it (for example 405 with BAD_REQUEST). */
    public void write(HttpServletResponse response, ErrorCode code, int status) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.title());
        body.put("status", status);
        body.put("code", code.name());
        body.put("correlationId", CorrelationId.current());
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
