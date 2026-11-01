package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** One weekday as sent: open between two quarter-hour times, or closed with no times. */
@Schema(
        name = "OpeningWeekdayInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"day", "open"})
public record OpeningWeekdayRequest(
        @Schema(allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
        String day,

        Boolean open,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour when open; null when closed.")
        String opens,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour after `opens`; null when closed.")
        String closes) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("weekly." + field);
    }
}
