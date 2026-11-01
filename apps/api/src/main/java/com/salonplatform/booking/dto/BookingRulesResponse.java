package com.salonplatform.booking.dto;

import com.salonplatform.booking.BookingTerms;
import io.swagger.v3.oas.annotations.media.Schema;

/** The booking rules of one version. */
@Schema(
        name = "BookingRules",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "slotIntervalMinutes",
            "minimumNoticeMinutes",
            "bookingHorizonDays",
            "deposit",
            "freeCancellationHours",
            "lateCancellation",
            "noShow",
            "reschedule"
        })
public record BookingRulesResponse(
        @Schema(description = "How often start times are offered: 10, 15, 20 or 30 minutes.")
        int slotIntervalMinutes,

        @Schema(
                description = "How long before an appointment it must be booked: 0 (any time before), 30, 60, 120,"
                        + " 240 or 1440 minutes.")
        int minimumNoticeMinutes,

        @Schema(description = "How far ahead customers can book: 14, 30, 60, 90 or 180 days.")
        int bookingHorizonDays,

        DepositRuleResponse deposit,

        @Schema(
                description = "Until how many hours before the appointment cancelling keeps nothing: 2, 6, 12, 24"
                        + " or 48.")
        int freeCancellationHours,

        RetentionResponse lateCancellation,

        @Schema(description = "`deposit` or `share`, never `nothing`.")
        RetentionResponse noShow,

        RescheduleResponse reschedule) {

    static BookingRulesResponse from(BookingTerms terms) {
        return new BookingRulesResponse(
                terms.slotIntervalMinutes(),
                terms.minimumNoticeMinutes(),
                terms.bookingHorizonDays(),
                DepositRuleResponse.from(terms.deposit()),
                terms.freeCancellationHours(),
                RetentionResponse.from(terms.lateCancellation()),
                RetentionResponse.from(terms.noShow()),
                RescheduleResponse.from(terms.reschedule()));
    }
}
