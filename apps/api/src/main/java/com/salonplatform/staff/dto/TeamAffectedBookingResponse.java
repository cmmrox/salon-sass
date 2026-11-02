package com.salonplatform.staff.dto;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.domain.LocalTimes;
import io.swagger.v3.oas.annotations.media.Schema;

/** A booking a change to a person's schedule leaves outside their working time. It is kept; the salon deals with it. */
@Schema(
        name = "TeamAffectedBooking",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "time", "customerName", "serviceName"})
public record TeamAffectedBookingResponse(
        @Schema(format = "date", description = "Its local date.")
        String date,

        @Schema(pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$", description = "When it starts, local `HH:MM`.")
        String time,

        String customerName,
        String serviceName) {

    public static TeamAffectedBookingResponse from(AffectedBooking booking) {
        return new TeamAffectedBookingResponse(
                booking.date().toString(),
                LocalTimes.text(booking.time()),
                booking.customerName(),
                booking.serviceName());
    }
}
