package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.BookingRulesService.RescheduleInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** How customers may reschedule: not online (0), once, twice, or any number of times (null). */
@Schema(
        name = "RescheduleInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"limit", "untilHours"})
public record RescheduleRequest(
        @Schema(
                types = {"integer", "null"},
                description = "0 is not online, 1 once, 2 twice; null is any number of times.")
        Integer limit,

        @Schema(
                types = {"integer", "null"},
                description = "Up to how many hours before the appointment: 2, 6, 12 or 24; null when not online.")
        Integer untilHours) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("rules.reschedule." + field);
    }

    RescheduleInput input() {
        return new RescheduleInput(limit, untilHours);
    }
}
