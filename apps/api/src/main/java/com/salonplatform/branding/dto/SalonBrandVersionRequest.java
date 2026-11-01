package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** The brand draft's version a reset, publication or discard is made from. */
@Schema(
        name = "SalonBrandVersion",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version"})
public record SalonBrandVersionRequest(
        @NotNull @PositiveOrZero Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
