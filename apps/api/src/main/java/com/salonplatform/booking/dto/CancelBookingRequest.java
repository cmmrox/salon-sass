package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.BookingChangeService.CancelInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** Why a booking is cancelled. */
@Schema(
        name = "CancelBooking",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"reason"})
public record CancelBookingRequest(
        @Schema(allowableValues = {"customer_asked", "salon_unable", "booked_by_mistake", "other"})
        String reason,

        @Schema(
                types = {"string", "null"},
                description = "Up to 200 characters, trimmed; blank is none.")
        String note) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public CancelInput input() {
        return new CancelInput(reason, note);
    }
}
