package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "confirmSlug", "reason"})
public record ClosureRequest(
        @Schema(minimum = "0") Long version,

        @Schema(description = "The salon's slug, typed exactly, to confirm an irreversible closure")
        String confirmSlug,

        @Schema(minLength = 10, maxLength = 500) String reason) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
