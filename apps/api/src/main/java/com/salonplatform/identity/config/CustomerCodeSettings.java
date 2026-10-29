package com.salonplatform.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Production defaults; lower test limits require explicit configuration. */
@ConfigurationProperties("salon.identity.codes")
public record CustomerCodeSettings(int numberLimit, int deviceLimit, int addressLimit, int dailyLimit) {
    public CustomerCodeSettings {
        if (numberLimit < 1 || deviceLimit < 1 || addressLimit < 1 || dailyLimit < 1)
            throw new IllegalArgumentException("Code request limits must be positive");
    }
}
