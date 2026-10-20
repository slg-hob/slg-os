package de.systemlogistics.slgos.core.session;

/** The session does not exist (any more); the caller answers 401 SESSION_EXPIRED. */
public class SessionGoneException extends RuntimeException {

    public SessionGoneException(String message) {
        super(message);
    }
}
