package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** A map position as sent: latitude -90 to 90 and longitude -180 to 180, rounded to six decimal places. */
@Schema(
        name = "SalonProfileLocationInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"latitude", "longitude"})
public record SalonProfileLocationRequest(
        @Schema(type = "number", minimum = "-90", maximum = "90")
        BigDecimal latitude,

        @Schema(type = "number", minimum = "-180", maximum = "180")
        BigDecimal longitude) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
