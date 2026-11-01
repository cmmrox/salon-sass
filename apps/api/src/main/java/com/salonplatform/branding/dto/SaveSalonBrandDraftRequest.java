package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** A brand to save as the salon's draft, made from the draft's {@code version}. */
@Schema(
        name = "SaveSalonBrandDraft",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "brand"})
public record SaveSalonBrandDraftRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Valid SalonBrandRequest brand) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
