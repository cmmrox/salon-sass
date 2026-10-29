package com.salonplatform.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerCodeSettings.class)
class CustomerCodeConfiguration {}
