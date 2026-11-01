package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** A profile to save as the salon's draft, made from the draft's {@code version}. */
@Schema(
        name = "SaveSalonProfileDraft",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "profile"})
public record SaveSalonProfileDraftRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Valid SalonProfileRequest profile) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
