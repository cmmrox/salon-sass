package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A day to add to a year's draft. */
@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"date", "name", "kind"})
public record HolidayDayRequest(
        @Schema(format = "date", description = "`YYYY-MM-DD`, inside the year, not already in the draft.")
        String date,

        @Schema(maxLength = 120, description = "1 to 120 characters after trimming.")
        String name,

        @Schema(allowableValues = {"poya", "public", "bank", "mercantile"})
        String kind) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
