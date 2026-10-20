package de.systemlogistics.slgos.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.systemlogistics.slgos.core.db.MigrationRunner;
import de.systemlogistics.slgos.core.db.MigrationState;
import de.systemlogistics.slgos.core.security.CsrfPolicy;
import de.systemlogistics.slgos.core.security.CsrfTokens;
import de.systemlogistics.slgos.core.security.oidc.IdTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.JwtVerifier;
import de.systemlogistics.slgos.core.security.oidc.LogoutTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import de.systemlogistics.slgos.core.security.oidc.OidcEndpoints;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** Wires the core from {@link SlgProperties}. The settings were validated before this class loads. */
@Configuration
@EnableConfigurationProperties(SlgProperties.class)
@EnableScheduling
public class CoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Application data source: the role without DDL rights. The pool revalidates connections and fails fast. */
    @Bean(destroyMethod = "close")
    DataSource dataSource(SlgProperties p) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("slg-app");
        config.setJdbcUrl(p.db().url());
        config.setUsername(p.db().appUser());
        config.setPassword(Secrets.read(p.db().appPasswordFile()));
        config.setMaximumPoolSize(p.db().poolSize());
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setKeepaliveTime(30_000);
        config.setMaxLifetime(600_000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    MigrationState migrationState() {
        return new MigrationState();
    }

    /** Runs the migrations as the migrator role while the context starts; a failure is recorded, not thrown. */
    @Bean(initMethod = "run")
    MigrationRunner migrationRunner(SlgProperties p, MigrationState state) {
        PGSimpleDataSource migrator = new PGSimpleDataSource();
        migrator.setUrl(p.db().url());
        migrator.setUser(p.db().migratorUser());
        migrator.setPassword(Secrets.read(p.db().migratorPasswordFile()));
        migrator.setConnectTimeout(5);
        return new MigrationRunner(migrator, state);
    }

    @Bean
    CsrfTokens csrfTokens(SlgProperties p) {
        return new CsrfTokens(Secrets.read(p.session().csrfKeyFile()).getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    CsrfPolicy csrfPolicy(SlgProperties p) {
        return new CsrfPolicy(p.shellBaseUrl());
    }

    @Bean
    OidcClient oidcClient(SlgProperties p, JsonMapper mapper) {
        return new OidcClient(new OidcEndpoints(p.oidc().issuer()), p.oidc().clientId(),
                Secrets.read(p.oidc().clientSecretFile()), p.oidc().httpTimeout(), mapper);
    }

    @Bean
    JwtVerifier jwtVerifier(OidcClient oidc, Clock clock) {
        return new JwtVerifier(oidc::fetchJwks, clock);
    }

    @Bean
    IdTokenValidator idTokenValidator(JwtVerifier verifier, SlgProperties p, Clock clock) {
        return new IdTokenValidator(verifier, new OidcEndpoints(p.oidc().issuer()).issuer(), p.oidc().clientId(), clock);
    }

    @Bean
    LogoutTokenValidator logoutTokenValidator(JwtVerifier verifier, SlgProperties p, Clock clock) {
        return new LogoutTokenValidator(verifier, new OidcEndpoints(p.oidc().issuer()).issuer(), p.oidc().clientId(), clock);
    }
}
