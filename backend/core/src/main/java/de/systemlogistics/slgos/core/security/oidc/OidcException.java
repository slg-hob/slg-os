package de.systemlogistics.slgos.core.security.oidc;

/** A failed exchange with the identity provider or an invalid token. Messages contain no token values. */
public class OidcException extends RuntimeException {

    /** What happened, and therefore what the caller may assume about the request. */
    public enum Kind {
        /** The request did not reach Keycloak or Keycloak answered with a server error; nothing was consumed. */
        UNAVAILABLE,
        /** The request was sent but the response is lost (timeout, reset); a refresh token may be consumed. */
        LOST_RESPONSE,
        /** Keycloak refused the request (invalid grant, invalid client). */
        REJECTED,
        /** A token failed validation (signature, issuer, audience, expiry, nonce ...). */
        INVALID_TOKEN
    }

    private final Kind kind;

    public OidcException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public OidcException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
