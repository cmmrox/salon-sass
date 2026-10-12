package com.salonplatform;

import com.salonplatform.shared.infrastructure.UtcTimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SalonApiApplication {

    public static void main(String[] args) {
        // Before anything reads the time zone; UtcTimeZone does the same for every other start.
        UtcTimeZone.apply();
        SpringApplication.run(SalonApiApplication.class, args);
    }
}
