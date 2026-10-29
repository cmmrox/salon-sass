package com.salonplatform.shared.web;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

/**
 * Describes the running service. The version and the commit are written by the build
 * ({@code META-INF/build-info.properties} and {@code git.properties}); neither is a property
 * someone edits. When the application runs without them, for example straight from an IDE, each
 * is reported as {@value #UNKNOWN}.
 */
@Service
public class MetaService {

    public static final String UNKNOWN = "unknown";

    private final String service;
    private final String version;
    private final String commit;
    private final Clock clock;

    public MetaService(
            @Value("${spring.application.name}") String service,
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties,
            Clock clock) {
        this.service = service;
        this.version = valueOrUnknown(buildProperties.getIfAvailable(), BuildProperties::getVersion);
        this.commit = valueOrUnknown(gitProperties.getIfAvailable(), GitProperties::getCommitId);
        this.clock = clock;
    }

    public ServiceMeta describe() {
        return new ServiceMeta(service, version, commit, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    private static <T> String valueOrUnknown(T source, java.util.function.Function<T, String> value) {
        if (source == null) {
            return UNKNOWN;
        }
        String found = value.apply(source);
        return found == null || found.isBlank() ? UNKNOWN : found;
    }
}
