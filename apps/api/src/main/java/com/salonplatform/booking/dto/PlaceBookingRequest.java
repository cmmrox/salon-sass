package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.HoldService.HoldInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** A hold on one professional's time for one service, for a customer, from one channel. */
@Schema(
        name = "PlaceBooking",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"memberId", "serviceId", "date", "time", "customer", "channel"})
public record PlaceBookingRequest(
        @Schema(format = "uuid", description = "The team member who performs it.")
        String memberId,

        @Schema(format = "uuid") String serviceId,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "Required, and one of the service's own, when the service has variants; else absent.")
        String variantId,

        @Schema(format = "date", description = "The salon's local date: today to a year ahead.")
        String date,

        @Schema(description = "`HH:MM`, the salon's local time, on a five-minute step.")
        String time,

        BookingCustomerRequest customer,

        @Schema(
                description = "Where the booking came from.",
                allowableValues = {"phone", "whatsapp", "front_desk", "walk_in", "instagram", "google", "staff_web"})
        String channel) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public HoldInput input() {
        return new HoldInput(
                memberId,
                serviceId,
                variantId,
                date,
                time,
                customer == null ? null : customer.name(),
                customer == null ? null : customer.mobile(),
                channel);
    }
}
