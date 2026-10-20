package de.systemlogistics.slgos.core.security;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** The correlation id of the current request, kept in the logging context. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private CorrelationId() {}

    public static String create() {
        return UUID.randomUUID().toString();
    }

    /** An inbound id is used only if it has the expected shape, so no log or header injection is possible. */
    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    /** The id of the current request, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
