package com.salonplatform.shared.infrastructure;

import com.salonplatform.shared.tenancy.PlatformHostname;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The platform domain salons are served under: {@code salon.platform-domain}. */
@Configuration(proxyBeanMethods = false)
class TenancyConfiguration {

    @Bean
    PlatformHostname platformHostname(@Value("${salon.platform-domain}") String platformDomain) {
        return new PlatformHostname(platformDomain);
    }
}
