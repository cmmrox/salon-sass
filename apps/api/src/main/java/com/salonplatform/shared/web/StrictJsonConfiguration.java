package com.salonplatform.shared.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Jackson's scalar flag alone still coerces numbers/booleans to text. Enforce the declared
 * contract.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictTextFields() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> {
            for (CoercionInputShape shape : new CoercionInputShape[] {
                CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
            }) config.setCoercion(shape, CoercionAction.Fail);
        });
    }
}
