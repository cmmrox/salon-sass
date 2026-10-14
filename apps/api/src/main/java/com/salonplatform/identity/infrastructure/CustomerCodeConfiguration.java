package com.salonplatform.identity.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerCodeSettings.class)
class CustomerCodeConfiguration {}
