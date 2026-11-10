package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.BookingRulesService.RulesInput;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The booking rules as a save sends them: every rule, each one of the salon's choices. The hold length and the
 * "No preference" strategy (F07-S02) may be left out, and then keep the current version's.
 */
@Schema(
        name = "BookingRulesInput",
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
public record BookingRulesRequest(
        @Schema(description = "How often start times are offered: 10, 15, 20 or 30 minutes.")
        Integer slotIntervalMinutes,

        @Schema(
                description = "How long before an appointment it must be booked: 0 (any time before), 30, 60, 120,"
                        + " 240 or 1440 minutes.")
        Integer minimumNoticeMinutes,

        @Schema(
                description = "How far ahead customers can book: 14, 30, 60, 90 or 180 days (2 weeks, or 1, 2, 3"
                        + " or 6 months).")
        Integer bookingHorizonDays,

        DepositRuleRequest deposit,

        @Schema(
                description = "Until how many hours before the appointment cancelling keeps nothing: 2, 6, 12,"
                        + " 24 or 48.")
        Integer freeCancellationHours,

        LateCancellationRequest lateCancellation,
        NoShowRequest noShow,
        RescheduleRequest reschedule,

        @Schema(
                description = "How long a hold lasts: 5, 10, 15, 20 or 30 minutes. Left out, the current version's (10"
                        + " before any is chosen).")
        Integer holdMinutes,

        @Schema(
                allowableValues = {"least_booked", "most_booked"},
                description = "Who a \"No preference\" booking goes to: of the professionals who perform the service"
                        + " and are free, the one with the least (or the most) booked that day. Left out, the current"
                        + " version's (`least_booked` before any is chosen).")
        String noPreference) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("rules." + field);
    }

    public RulesInput input() {
        return new RulesInput(
                slotIntervalMinutes,
                minimumNoticeMinutes,
                bookingHorizonDays,
                deposit == null ? null : deposit.input(),
                freeCancellationHours,
                lateCancellation == null ? null : lateCancellation.input(),
                noShow == null ? null : noShow.input(),
                reschedule == null ? null : reschedule.input(),
                holdMinutes,
                noPreference);
    }
}
