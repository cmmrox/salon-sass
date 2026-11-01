package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** A save of the booking rules, made from the version the caller read. */
@Schema(
        name = "SaveBookingRules",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "rules"})
public record SaveBookingRulesRequest(
        @Schema(description = "The current version's number, as read: 0 before the first save.")
        @NotNull
        @PositiveOrZero
        Long version,

        BookingRulesRequest rules) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
