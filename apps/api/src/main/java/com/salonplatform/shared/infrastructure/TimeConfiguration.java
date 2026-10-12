package com.salonplatform.shared.infrastructure;

import java.time.Clock;

import com.salonplatform.shared.time.SalonTime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock the application reads the time from, and the service that converts it into a
 * salon's time. In the {@code local} profile a test clock that QA can set takes the clock's place.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SalonTime salonTime(Clock clock) {
        return new SalonTime(clock);
    }
}
