package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether a service is chosen for Popular, saved from the version the caller read. */
@Schema(
        name = "CatalogueServicePopular",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"popular", "version"})
public record ServicePopularRequest(
        Boolean popular,

        @Schema(description = "The service's version, as read.", minimum = "0")
        Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
