package com.salonplatform.shared.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import static org.assertj.core.api.Assertions.assertThat;

class MetaServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T02:17:00.123456789Z"), ZoneOffset.UTC);

    @Test
    void reportsTheNameTheBuildsVersionAndCommitAndTheClocksTime() {
        MetaService service = new MetaService("salon-api", provider(build("1.2.3")),
                provider(git("0123456789abcdef0123456789abcdef01234567")), CLOCK);

        ServiceMeta meta = service.describe();

        assertThat(meta.service()).isEqualTo("salon-api");
        assertThat(meta.version()).isEqualTo("1.2.3");
        assertThat(meta.commit()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(meta.time()).isEqualTo(Instant.parse("2026-10-12T02:17:00.123Z"));
    }

    @Test
    void reportsUnknownWhenTheBuildWroteNoVersionOrCommit() {
        MetaService service = new MetaService("salon-api", provider(null), provider(null), CLOCK);

        ServiceMeta meta = service.describe();

        assertThat(meta.version()).isEqualTo("unknown");
        assertThat(meta.commit()).isEqualTo("unknown");
    }

    @Test
    void reportsUnknownWhenTheBuildHadNoRepository() {
        MetaService service = new MetaService("salon-api", provider(build("1.2.3")), provider(new GitProperties(new Properties())),
                CLOCK);

        assertThat(service.describe().commit()).isEqualTo("unknown");
    }

    private static BuildProperties build(String version) {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        return new BuildProperties(properties);
    }

    private static GitProperties git(String commit) {
        Properties properties = new Properties();
        properties.setProperty("commit.id", commit);
        return new GitProperties(properties);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}
