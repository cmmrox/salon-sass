package com.salonplatform.shared.web.impl;

import com.salonplatform.shared.web.MetaService;
import com.salonplatform.shared.web.ServiceMeta;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

@Service
class MetaServiceImpl implements MetaService {

    private final String service;
    private final String version;
    private final String commit;
    private final Clock clock;

    MetaServiceImpl(
            @Value("${spring.application.name}") String service,
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties,
            Clock clock) {
        this.service = service;
        this.version = valueOrUnknown(buildProperties.getIfAvailable(), BuildProperties::getVersion);
        this.commit = valueOrUnknown(gitProperties.getIfAvailable(), GitProperties::getCommitId);
        this.clock = clock;
    }

    @Override
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
