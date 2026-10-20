package de.systemlogistics.slgos.core.security;

/**
 * Machine-readable error codes of the core API (problem-details {@code code}). The shell maps each code to an i18n key
 * {@code errors.<CODE>} and never shows {@code title} or {@code detail} (PRD-0003 SK-11 AC6).
 */
public enum ErrorCode {
    BAD_REQUEST(400, "Bad Request"),
    SESSION_EXPIRED(401, "Unauthorized"),
    AUTH_UNAVAILABLE(401, "Unauthorized"),
    CSRF(403, "Forbidden"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    MISDIRECTED_HOST(421, "Misdirected Request"),
    RATE_LIMITED(429, "Too Many Requests"),
    INTERNAL_ERROR(500, "Internal Server Error"),
    SESSION_STORE_DOWN(503, "Service Unavailable");

    private final int status;
    private final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
