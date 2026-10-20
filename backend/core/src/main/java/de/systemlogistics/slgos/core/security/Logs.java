package de.systemlogistics.slgos.core.security;

import java.sql.SQLException;

/** Log text for exceptions that may carry data: class and SQL state only, never driver messages with row contents. */
public final class Logs {

    private Logs() {}

    public static String safe(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return error.getClass().getSimpleName() + " (SQLState " + sql.getSQLState() + ")";
            }
            cause = cause.getCause();
        }
        return error.getClass().getSimpleName();
    }
}
