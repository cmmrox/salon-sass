package com.salonplatform.booking.dto;

import com.salonplatform.booking.Rescheduling;
import io.swagger.v3.oas.annotations.media.Schema;

/** How customers may reschedule. Both fields are always present. */
@Schema(
        name = "RescheduleRule",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"limit", "untilHours"})
public record RescheduleResponse(
        @Schema(
                types = {"integer", "null"},
                description = "0 is not online, 1 once, 2 twice; null is any number of times.")
        Integer limit,

        @Schema(
                types = {"integer", "null"},
                description = "Up to how many hours before the appointment: 2, 6, 12 or 24; null when not online.")
        Integer untilHours) {

    static RescheduleResponse from(Rescheduling reschedule) {
        return switch (reschedule) {
            case Rescheduling.NotOnline _ -> new RescheduleResponse(0, null);
            case Rescheduling.Limited limited -> new RescheduleResponse(limited.times(), limited.untilHours());
            case Rescheduling.Unlimited unlimited -> new RescheduleResponse(null, unlimited.untilHours());
        };
    }
}
