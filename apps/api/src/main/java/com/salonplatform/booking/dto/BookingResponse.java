package com.salonplatform.booking.dto;

import com.salonplatform.booking.domain.BookingReference;
import com.salonplatform.booking.domain.CustomerMobile;
import com.salonplatform.booking.service.BookingReadService.BookingView;
import com.salonplatform.shared.time.SalonTime;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A hold or a booking. Instants are offset date-times in the salon's time zone. */
@Schema(
        name = "Booking",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "id",
            "reference",
            "state",
            "startsAt",
            "endsAt",
            "holdExpiresAt",
            "member",
            "service",
            "customer",
            "channel",
            "priceMinor",
            "currency",
            "createdAt"
        })
public record BookingResponse(
        UUID id,

        @Schema(description = "Six characters, shown with a hyphen: `K7M-4QX`.", pattern = "^[A-Z2-9]{3}-[A-Z2-9]{3}$")
        String reference,

        @Schema(
                allowableValues = {
                    "held",
                    "pending_payment",
                    "confirmed",
                    "arrived",
                    "in_service",
                    "completed",
                    "cancelled",
                    "no_show"
                })
        String state,

        @Schema(format = "date-time") String startsAt,

        @Schema(format = "date-time", description = "The end of its last segment, its buffer included.")
        String endsAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                description = "When the hold runs out, while it is a hold; else null.")
        String holdExpiresAt,

        BookingMemberResponse member,
        BookingServiceResponse service,
        BookingCustomerResponse customer,

        @Schema(
                allowableValues = {
                    "customer_app",
                    "staff_web",
                    "shared_app_link",
                    "instagram",
                    "google",
                    "whatsapp",
                    "phone",
                    "front_desk",
                    "walk_in"
                })
        String channel,

        @Schema(minimum = "0", description = "The price it was booked at, in minor units of `currency`.")
        long priceMinor,

        @Schema(pattern = "^[A-Z]{3}$") String currency,
        @Schema(format = "date-time") String createdAt) {

    public static BookingResponse from(BookingView view) {
        return new BookingResponse(
                view.id(),
                BookingReference.shown(view.reference()),
                view.state().value(),
                SalonTime.formatLocal(view.startsAt(), view.zone()),
                SalonTime.formatLocal(view.endsAt(), view.zone()),
                view.holdExpiresAt() == null ? null : SalonTime.formatLocal(view.holdExpiresAt(), view.zone()),
                new BookingMemberResponse(view.memberId(), view.memberName()),
                new BookingServiceResponse(view.serviceId(), view.serviceName(), view.variantId(), view.variantName()),
                new BookingCustomerResponse(view.customerName(), CustomerMobile.national(view.customerMobile())),
                view.channel().value(),
                view.priceMinor(),
                view.currency(),
                SalonTime.formatLocal(view.createdAt(), view.zone()));
    }
}
