package de.systemlogistics.slgos.core.security.oidc;

/** Keycloak endpoint URLs derived from the public issuer URL (no discovery round trip, so a missing Keycloak never blocks start). */
public record OidcEndpoints(String issuer) {

    public OidcEndpoints {
        issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    public String authorization() {
        return issuer + "/protocol/openid-connect/auth";
    }

    public String token() {
        return issuer + "/protocol/openid-connect/token";
    }

    public String jwks() {
        return issuer + "/protocol/openid-connect/certs";
    }

    public String logout() {
        return issuer + "/protocol/openid-connect/logout";
    }
}
