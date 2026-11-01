package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A day to close. */
@Schema(
        name = "AddSalonClosure",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date"})
public record AddClosureRequest(
        @Schema(format = "date", description = "The salon's local date: today to a year ahead.")
        String date,

        @Schema(
                types = {"string", "null"},
                maxLength = 60,
                description = "Only the team sees it; up to 60 characters, empty or null for none.")
        String reason) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
