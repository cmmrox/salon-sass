package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.BookingRulesService.RetentionInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a no-show keeps: {@code deposit}, or {@code share}. */
@Schema(
        name = "NoShowInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record NoShowRequest(
        @Schema(allowableValues = {"deposit", "share"}) String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `share`: the share of the price kept.")
        Integer percent) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("rules.noShow." + field);
    }

    RetentionInput input() {
        return new RetentionInput(kind, percent);
    }
}
