package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.SalonServices.VariantInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** One variant of a service, in the order customers see it. */
@Schema(
        name = "CatalogueVariantInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "priceMinor", "durationMinutes"})
public record VariantRequest(
        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "Absent or null for a new variant; otherwise one of the current terms version's"
                        + " variants, which keeps its id.")
        UUID id,

        @Schema(description = "Up to 30 characters after trimming, unique in the set regardless of case.")
        String name,

        @Schema(description = "Minor units of the salon's currency, a whole number of units: Rs. 100 to Rs. 500,000.")
        Long priceMinor,

        @Schema(description = "5 to 480 minutes, in steps of 5.")
        Integer durationMinutes) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("variants.items." + field);
    }

    VariantInput input() {
        return new VariantInput(id, name, priceMinor, durationMinutes);
    }
}
