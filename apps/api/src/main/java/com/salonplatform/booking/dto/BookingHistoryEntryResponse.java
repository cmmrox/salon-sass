package com.salonplatform.booking.dto;

import com.salonplatform.booking.service.BookingReadService.HistoryView;
import com.salonplatform.shared.time.SalonTime;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;

/** One change of a booking's state: when, from what to what, who, and why for a cancellation. */
@Schema(
        name = "BookingHistoryEntry",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"at", "from", "to", "by", "reason", "note"})
public record BookingHistoryEntryResponse(
        @Schema(format = "date-time") String at,

        @Schema(
                types = {"string", "null"},
                description = "The state before; null for the placing of the hold.",
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
        String from,

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
        String to,

        BookingActorResponse by,

        @Schema(
                types = {"string", "null"},
                allowableValues = {"customer_asked", "salon_unable", "booked_by_mistake", "other"})
        String reason,

        @Schema(types = {"string", "null"}) String note) {

    static BookingHistoryEntryResponse from(HistoryView entry, ZoneId zone) {
        return new BookingHistoryEntryResponse(
                SalonTime.formatLocal(entry.at(), zone),
                entry.from() == null ? null : entry.from().value(),
                entry.to().value(),
                entry.byStaff() == null
                        ? new BookingActorResponse("system", null)
                        : new BookingActorResponse("staff", entry.byLabel()),
                entry.reason() == null ? null : entry.reason().value(),
                entry.note());
    }
}
