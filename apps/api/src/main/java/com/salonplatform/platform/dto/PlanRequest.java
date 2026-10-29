package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "key",
            "name",
            "staffSeats",
            "branches",
            "messageAllowance",
            "customDomain",
            "dedicatedApp"
        })
public record PlanRequest(
        @Schema(minLength = 3, maxLength = 30) String key,
        @Schema(maxLength = 120) String name,
        @Schema(minimum = "1", maximum = "1000000") Integer staffSeats,
        @Schema(minimum = "1", maximum = "1000000") Integer branches,
        @Schema(minimum = "0", maximum = "1000000") Integer messageAllowance,
        Boolean customDomain,
        Boolean dedicatedApp) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
