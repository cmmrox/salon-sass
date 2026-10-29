package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"monthlyAmount", "paymentMethod"})
public record SalonSubscriptionRequest(
        @Schema(minimum = "0", maximum = "100000000") Long monthlyAmount,

        @Schema(allowableValues = {"card", "bank_transfer"}) String paymentMethod) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("subscription." + field);
    }
}
