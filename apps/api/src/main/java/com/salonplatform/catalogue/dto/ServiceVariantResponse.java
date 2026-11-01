package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.catalogue.domain.Segments;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** One variant of a service's terms version, in order. */
@Schema(
        name = "CatalogueServiceVariant",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "priceMinor", "segments", "durationMinutes"})
public record ServiceVariantResponse(
        @Schema(format = "uuid", description = "The variant's stable id, the same in every version while it exists.")
        UUID id,

        @Schema(minLength = 1, maxLength = 30) String name,

        @Schema(description = "Its price in minor units of the terms' currency.", minimum = "1")
        long priceMinor,

        @ArraySchema(minItems = 1) List<ServiceSegmentResponse> segments,

        @Schema(description = "Its time: every segment but the buffer.", minimum = "5")
        int durationMinutes) {

    static ServiceVariantResponse from(ServiceVariant variant) {
        return new ServiceVariantResponse(
                variant.variantId(),
                variant.name(),
                variant.priceMinor(),
                variant.segments().stream().map(ServiceSegmentResponse::from).toList(),
                Segments.duration(variant.segments()));
    }
}
