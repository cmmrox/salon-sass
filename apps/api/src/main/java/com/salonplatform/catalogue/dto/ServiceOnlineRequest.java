package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether a service is bookable online, saved from the version the caller read. */
@Schema(
        name = "CatalogueServiceOnline",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"bookableOnline", "version"})
public record ServiceOnlineRequest(
        Boolean bookableOnline,

        @Schema(description = "The service's version, as read.", minimum = "0")
        Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
