package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A new choice for one holiday, after the year's acceptance. */
@Schema(
        name = "SalonHolidayDayChoice",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"choice"})
public record HolidayDayChoiceRequest(
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
        throw UnknownField.refused(field);
    }
}
