package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** How the Popular list is made, saved from the version the caller read. */
@Schema(
        name = "CataloguePopularSettingInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"mode", "version"})
public record PopularSettingRequest(
        @Schema(allowableValues = {"chosen", "most_booked"}) String mode,

        @Schema(description = "The setting's version, as read; 0 before its first change.", minimum = "0")
        Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
