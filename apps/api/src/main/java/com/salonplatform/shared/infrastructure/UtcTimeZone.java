package com.salonplatform.shared.infrastructure;

import java.util.TimeZone;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;

/**
 * The API runs in UTC whatever the machine's time zone is (BKG-15): set before anything else starts,
 * so that the database driver, which tells PostgreSQL the JVM's zone when it connects, talks to the
 * database in UTC, and nothing that formats a time picks up the machine's zone by accident.
 * Registered in {@code META-INF/spring.factories}, so it holds for every way the application starts,
 * the developer tests included.
 */
public class UtcTimeZone implements ApplicationListener<ApplicationStartingEvent> {

    public static final String UTC = "UTC";

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        apply();
    }

    public static void apply() {
        TimeZone.setDefault(TimeZone.getTimeZone(UTC));
    }
}
