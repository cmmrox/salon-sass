package com.salonplatform.staff.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One weekday as sent: working between two quarter-hour times with breaks, or not working with no times. */
@Schema(
        name = "TeamWorkdayInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"day", "working"})
public record TeamWorkdayRequest(
        @Schema(allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
        String day,

        Boolean working,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour when working; null when not.")
        String starts,

        @Schema(
                types = {"string", "null"},
                description = "`HH:MM` on a quarter hour after `starts` when working; null when not.")
        String finishes,

        @Schema(
                description = "Up to five, inside the hours, not overlapping, and leaving some of the day. A day not"
                        + " worked keeps none, whatever is sent.")
        List<TeamBreakRequest> breaks) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("weekly." + field);
    }
}
