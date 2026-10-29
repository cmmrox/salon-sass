package com.salonplatform.shared.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How recurring jobs are scheduled ({@code salon.jobs.*}).
 *
 * @param enabled whether this instance runs the scheduler
 * @param pollInterval how often the scheduler asks whether a job is due
 * @param hold how long a claimed job stays held if the instance running it stops without saying it
 *        has finished
 * @param outboxCleanup how often delivered messages past their retention are removed
 */
@ConfigurationProperties("salon.jobs")
public record JobSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10m") Duration hold,
        @DefaultValue("1h") Duration outboxCleanup) {

    public JobSettings {
        for (Duration value : new Duration[] {pollInterval, hold, outboxCleanup}) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("every salon.jobs duration must be positive");
            }
        }
    }
}
