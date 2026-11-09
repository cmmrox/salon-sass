package com.salonplatform.booking.config;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingSettings.class)
class BookingConfiguration {

    /**
     * Where booking references are drawn from (BKG-14, decision 108(d)): unpredictable, so a reference reveals nothing
     * about a salon's bookings. The application's only {@link RandomGenerator} bean; a test replaces it to make a
     * collision happen.
     */
    @Bean
    RandomGenerator bookingReferenceRandom() {
        return new SecureRandom();
    }
}
