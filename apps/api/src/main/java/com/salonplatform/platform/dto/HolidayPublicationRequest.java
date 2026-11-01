package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** The year's version a publication is made from. */
@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version"})
public record HolidayPublicationRequest(
        @Schema(minimum = "0") Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
