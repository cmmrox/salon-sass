package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.SalonServices.ServiceInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Every field of a service, saved from the version the caller read. */
@Schema(
        name = "SaveCatalogueService",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "name",
            "categoryId",
            "priceMinor",
            "durationMinutes",
            "bufferMinutes",
            "bookableOnline",
            "version"
        })
public record UpdateServiceRequest(
        @Schema(description = "2 to 40 characters after trimming, unique in the salon regardless of case.")
        String name,

        @Schema(format = "uuid", description = "One of the salon's categories.")
        UUID categoryId,

        @Schema(
                types = {"string", "null"},
                description = "Up to 90 characters; empty or null for none.")
        String description,

        @Schema(description = "Minor units of the salon's currency, a whole number of units.")
        Long priceMinor,

        @Schema(description = "5 to 480 minutes, in steps of 5.")
        Integer durationMinutes,

        @Schema(description = "0 to 60 minutes after the service, in steps of 5.")
        Integer bufferMinutes,

        Boolean bookableOnline,

        @Schema(description = "The service's version, as read.", minimum = "0")
        Long version) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public ServiceInput input() {
        return new ServiceInput(
                name, categoryId, description, priceMinor, durationMinutes, bufferMinutes, bookableOnline);
    }
}
