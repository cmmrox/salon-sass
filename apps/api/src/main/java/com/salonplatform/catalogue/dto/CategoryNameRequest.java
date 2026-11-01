package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A new category: its name. */
@Schema(
        name = "NewCatalogueCategory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name"})
public record CategoryNameRequest(
        @Schema(description = "2 to 30 characters after trimming, unique in the salon regardless of case.")
        String name) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
