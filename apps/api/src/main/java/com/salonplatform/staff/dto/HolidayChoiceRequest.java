package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A choice for one holiday, as part of an acceptance. */
@Schema(
        name = "SalonHolidayChoiceInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "choice"})
public record HolidayChoiceRequest(
        @Schema(format = "date") String date,

        @Schema(allowableValues = {"closed", "open", "special"})
        String choice,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour for special hours; else null.")
        String opens,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` after `opens` for special hours; else null.")
        String closes) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("choices." + field);
    }
}
