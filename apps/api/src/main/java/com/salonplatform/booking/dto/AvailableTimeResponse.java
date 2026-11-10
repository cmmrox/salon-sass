package com.salonplatform.booking.dto;

import com.salonplatform.booking.Availability.FreeTime;
import com.salonplatform.shared.time.SalonTime;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** One start time that can be held, and who would take it. */
@Schema(
        name = "AvailableTime",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"time", "startsAt", "member"})
public record AvailableTimeResponse(
        @Schema(pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$", description = "The salon's local start time, `HH:MM`.")
        String time,

        @Schema(format = "date-time", description = "The start, an offset date-time in the salon's zone.")
        String startsAt,

        AvailableMemberResponse member) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    static AvailableTimeResponse from(FreeTime time, ZoneId zone) {
        return new AvailableTimeResponse(
                CLOCK.format(time.time()),
                SalonTime.formatLocal(time.startsAt(), zone),
                new AvailableMemberResponse(time.memberId(), time.memberName()));
    }
}
