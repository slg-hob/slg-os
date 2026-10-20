package de.systemlogistics.slgos.core.session;

import jakarta.servlet.http.HttpServletRequest;

/** The authenticated session of the current request, attached by {@link SessionFilter}. */
public record SessionContext(SessionRecord session, String csrfToken) {

    static final String ATTRIBUTE = SessionContext.class.getName();

    /** The context of the request; throws if the request did not pass the session filter. */
    public static SessionContext of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof SessionContext context) {
            return context;
        }
        throw new IllegalStateException("request has no session context");
    }
}
