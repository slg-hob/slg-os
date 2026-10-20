package de.systemlogistics.slgos.core.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Validates the settings before any bean exists, so a missing or invalid setting stops the start with a message that names
 * the variable in German and English (PRD-0003 SK-16 AC5). Registered in {@code META-INF/spring.factories}; it runs after the
 * configuration files were loaded.
 */
public class ConfigurationCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        SlgProperties properties = Binder.get(event.getEnvironment()).bindOrCreate("slg", SlgProperties.class);
        ConfigValidator.require(properties);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
