package de.systemlogistics.slgos.core.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All settings of the core. They come from environment variables (SLG_*) and secret files only (PRD-0003 SK-16 AC5).
 * Secrets are never values here, only paths of files (Docker secrets).
 */
@ConfigurationProperties("slg")
public record SlgProperties(
        @DefaultValue("dev") String env,
        String shellBaseUrl,
        @DefaultValue({"core-backend", "localhost"}) List<String> internalHosts,
        List<String> trustedProxyCidrs,
        String bootstrapAdminGroup,
        @DefaultValue Oidc oidc,
        @DefaultValue Session session,
        @DefaultValue Db db) {

    public SlgProperties {
        internalHosts = internalHosts == null ? List.of() : List.copyOf(internalHosts);
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }

    public boolean production() {
        return "production".equals(env);
    }

    /** OpenID Connect client settings. The issuer is the public URL; the core reaches Keycloak through the same URL. */
    public record Oidc(
            String issuer,
            @DefaultValue("slg-core") String clientId,
            String clientSecretFile,
            @DefaultValue("3s") Duration httpTimeout) {}

    /** Session lifetime and token vault settings (PRD-0003 SK-3). */
    public record Session(
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("10h") Duration absoluteTimeout,
            @DefaultValue("10") int maxPerUser,
            @DefaultValue("60s") Duration renewMargin,
            @DefaultValue("5m") Duration stepUpMaxAge,
            String vaultKeysFile,
            String vaultActiveKeyId,
            String csrfKeyFile) {}

    /** Two database roles: the application role has no DDL rights, the migrator role is used by Flyway only. */
    public record Db(
            String url,
            String appUser,
            String appPasswordFile,
            String migratorUser,
            String migratorPasswordFile,
            @DefaultValue("10") int poolSize) {}
}
