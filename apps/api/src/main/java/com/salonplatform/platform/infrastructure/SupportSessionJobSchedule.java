package com.salonplatform.platform.infrastructure;

import com.salonplatform.platform.application.SupportSessions;
import com.salonplatform.shared.jobs.PlatformJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The support-session expiry job (F03-S04): each minute it records the end of every session whose
 * expiry has passed, once. A session stops working at its expiry whether or not the job has run; the
 * job only writes its end into the audit trails.
 */
@Configuration(proxyBeanMethods = false)
class SupportSessionJobSchedule {

    @Bean
    PlatformJob supportSessionExpiryJob(SupportSessions sessions) {
        return new PlatformJob() {
            @Override
            public String name() {
                return "support-session-expiry";
            }

            @Override
            public Duration every() {
                return Duration.ofMinutes(1);
            }

            @Override
            public void run(Instant now) {
                sessions.expire(now);
            }
        };
    }
}
