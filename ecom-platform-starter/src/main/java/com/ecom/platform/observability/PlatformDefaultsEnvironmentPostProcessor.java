package com.ecom.platform.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds {@code ecom-platform-defaults.yml} as the LOWEST-priority property source, so every service
 * gets the same actuator/probe/tracing/kafka/flyway defaults and can still override any of them.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        var resource = new ClassPathResource("ecom-platform-defaults.yml");
        try {
            new YamlPropertySourceLoader().load("ecomPlatformDefaults", resource)
                    .forEach(ps -> env.getPropertySources().addLast(ps));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
