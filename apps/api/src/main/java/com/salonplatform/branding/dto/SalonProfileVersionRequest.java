package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** The draft's version a publication or discard is made from. */
@Schema(
        name = "SalonProfileVersion",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version"})
public record SalonProfileVersionRequest(
        @NotNull @PositiveOrZero Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
