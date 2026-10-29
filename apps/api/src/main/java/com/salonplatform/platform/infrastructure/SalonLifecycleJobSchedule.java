package com.salonplatform.platform.infrastructure;

import com.salonplatform.platform.application.SalonDeletion;
import com.salonplatform.platform.application.SalonExports;
import com.salonplatform.shared.jobs.PlatformJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The export and retention jobs' schedules (F03-S03): each one pass every minute through the shared
 * scheduler, at most once per occurrence across every instance. Both are idempotent and resumable, so
 * their frequency decides only how soon an export is ready after closure and how soon a salon is
 * deleted after its deletion instant has come.
 */
@Configuration(proxyBeanMethods = false)
class SalonLifecycleJobSchedule {

    @Bean
    PlatformJob salonExportJob(SalonExports exports) {
        return job("salon-export", exports::run);
    }

    @Bean
    PlatformJob salonDeletionJob(SalonDeletion deletion) {
        return job("salon-deletion", deletion::run);
    }

    private static PlatformJob job(String name, java.util.function.Consumer<Instant> run) {
        return new PlatformJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration every() {
                return Duration.ofMinutes(1);
            }

            @Override
            public void run(Instant now) {
                run.accept(now);
            }
        };
    }
}
