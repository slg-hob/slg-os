package de.systemlogistics.slgos.core.security.oidc;

/** Result of a token endpoint call. {@code toString} hides all values. */
public record OidcTokens(String accessToken, String refreshToken, String idToken, long expiresInSeconds) {

    @Override
    public String toString() {
        return "OidcTokens[redacted]";
    }
}
