package com.salonplatform.shared.local;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The application's clock in the {@code local} profile: real time until it is set, then the set
 * instant running on from there, until it is returned to real time. Everything that reads the time
 * (due messages, leases, the breaker's wait, jobs, the server time of {@code meta}) reads it.
 */
@Component
@Primary
@Profile("local")
public class TestClock extends Clock {

    private final Clock real = Clock.systemUTC();

    /** Null while the clock shows real time. */
    private volatile Duration offset;

    @Override
    public Instant instant() {
        Duration current = offset;
        return current == null ? real.instant() : real.instant().plus(current);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(Clock.system(zone), offset == null ? Duration.ZERO : offset);
    }

    /** From now on the clock reads this instant, running on from there. */
    public void set(Instant at) {
        offset = Duration.between(real.instant(), at);
    }

    /** Back to real time. */
    public void reset() {
        offset = null;
    }

    /** Whether the clock has been set. */
    public boolean isSet() {
        return offset != null;
    }
}
