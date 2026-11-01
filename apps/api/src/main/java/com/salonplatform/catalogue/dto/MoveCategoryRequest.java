package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A move of a category one place up or down, made from the list version the caller read. */
@Schema(
        name = "MoveCatalogueCategory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"direction", "listVersion"})
public record MoveCategoryRequest(
        @Schema(allowableValues = {"up", "down"}) String direction,

        @Schema(description = "The category list's version, as read.", minimum = "0")
        Long listVersion) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
