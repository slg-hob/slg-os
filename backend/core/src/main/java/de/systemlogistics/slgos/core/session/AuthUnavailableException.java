package de.systemlogistics.slgos.core.session;

/** The identity provider is unavailable and no valid access token exists; the session is kept. Maps to 401 AUTH_UNAVAILABLE. */
public class AuthUnavailableException extends RuntimeException {

    public AuthUnavailableException(String message) {
        super(message);
    }

    public AuthUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
