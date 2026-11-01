package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A draft day's new name and kind; its date is the path's. */
@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "kind"})
public record HolidayDayChangeRequest(
        @Schema(maxLength = 120, description = "1 to 120 characters after trimming.")
        String name,

        @Schema(allowableValues = {"poya", "public", "bank", "mercantile"})
        String kind) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
