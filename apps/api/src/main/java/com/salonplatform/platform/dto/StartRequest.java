package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"reason"})
public record StartRequest(
        @Schema(minLength = 10, maxLength = 500) String reason,

        @Schema(minimum = "5", maximum = "120", defaultValue = "30")
        Integer durationMinutes) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
