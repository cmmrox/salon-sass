package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A rename of a category, made from the version the caller read. */
@Schema(
        name = "RenameCatalogueCategory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "version"})
public record RenameCategoryRequest(
        @Schema(description = "2 to 30 characters after trimming, unique in the salon regardless of case.")
        String name,

        @Schema(description = "The category's version, as read.", minimum = "0")
        Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
