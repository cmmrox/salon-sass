package com.salonplatform.shared.infrastructure;

import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Adds the facts about the runtime that the stack depends on to {@code /actuator/info}: the Java
 * major version, and whether requests are really handled on virtual threads (the setting is on and
 * the thread answering this request is virtual).
 */
@Component
class RuntimeInfoContributor implements InfoContributor {

    private final Environment environment;

    RuntimeInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        boolean configured = environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        builder.withDetail("runtime", Map.of(
                "javaMajorVersion", Runtime.version().feature(),
                "virtualThreads", configured && Thread.currentThread().isVirtual()));
    }
}
