package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.SalonServices.VariantsInput;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A service's one set of variants (F05-S02): its name, and its variants in order. */
@Schema(
        name = "CatalogueVariantsInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "items"})
public record VariantsRequest(
        @Schema(description = "2 to 30 characters after trimming, such as \"Hair length\".")
        String name,

        @ArraySchema(minItems = 2, maxItems = 10) List<VariantRequest> items) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("variants." + field);
    }

    VariantsInput input() {
        return new VariantsInput(
                name,
                items == null
                        ? null
                        : items.stream()
                                .map(item -> item == null ? null : item.input())
                                .toList());
    }
}
