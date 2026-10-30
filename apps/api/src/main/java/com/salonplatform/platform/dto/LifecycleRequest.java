package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "reason"})
public record LifecycleRequest(
        @Schema(minimum = "0") Long version,
        @Schema(minLength = 10, maxLength = 500) String reason) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
