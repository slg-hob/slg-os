package de.systemlogistics.slgos.core.session;

import de.systemlogistics.slgos.core.config.Secrets;
import de.systemlogistics.slgos.core.config.SlgProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the token vault inside the session package, the only place that may use it. */
@Configuration
class SessionConfiguration {

    @Bean
    TokenVault tokenVault(SlgProperties p) {
        return new TokenVault(Secrets.parseKeys(Secrets.read(p.session().vaultKeysFile())),
                p.session().vaultActiveKeyId());
    }
}
