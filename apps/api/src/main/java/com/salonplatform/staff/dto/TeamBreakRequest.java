package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A break as sent. */
@Schema(
        name = "TeamBreakInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"starts", "finishes"})
public record TeamBreakRequest(
        @Schema(description = "`HH:MM` on a quarter hour.") String starts,

        @Schema(description = "`HH:MM` on a quarter hour, after `starts`.")
        String finishes) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("breaks." + field);
    }
}
