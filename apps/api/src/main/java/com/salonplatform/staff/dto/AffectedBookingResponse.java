package com.salonplatform.staff.dto;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.domain.LocalTimes;
import io.swagger.v3.oas.annotations.media.Schema;

/** A booking a closure or an hours change affects. It is kept; the salon deals with it. */
@Schema(
        name = "AffectedBooking",
        requiredProperties = {"time", "customerName", "serviceName"})
public record AffectedBookingResponse(
        @Schema(pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$", description = "When it starts, local `HH:MM`.")
        String time,

        String customerName,
        String serviceName) {

    public static AffectedBookingResponse from(AffectedBooking booking) {
        return new AffectedBookingResponse(
                LocalTimes.text(booking.time()), booking.customerName(), booking.serviceName());
    }
}
