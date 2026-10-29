package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"monthlyAmount", "paymentMethod"})
public record StartSubscriptionRequest(
        @Schema(minimum = "0", maximum = "100000000") Long monthlyAmount,

        @Schema(allowableValues = {"card", "bank_transfer"}) String paymentMethod,

        @Schema(
                description = "Required, and only allowed, when the salon has no currency of its own",
                minLength = 3,
                maxLength = 3)
        String currency) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
