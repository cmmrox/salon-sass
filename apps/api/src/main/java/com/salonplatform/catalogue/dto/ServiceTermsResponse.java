package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.domain.Ranges;
import com.salonplatform.catalogue.domain.Segments;
import com.salonplatform.catalogue.service.SalonServices.TermsView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * A service's current terms: one immutable version, which a booking keeps (CAT-04). With variants (CAT-03) the
 * version's own price and time are the lowest price and the shortest time, and each variant has its own.
 */
@Schema(
        name = "CatalogueServiceTerms",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "versionId",
            "number",
            "priceMinor",
            "currency",
            "segments",
            "durationMinutes",
            "bufferMinutes",
            "variantSetName",
            "variants",
            "priceMinMinor",
            "priceMaxMinor",
            "durationMinMinutes",
            "durationMaxMinutes"
        })
public record ServiceTermsResponse(
        @Schema(format = "uuid", description = "The terms version; a booking made now keeps it.")
        UUID versionId,

        @Schema(description = "The version's number for this service, from 1.", minimum = "1")
        long number,

        @Schema(description = "The price in minor units of `currency`; with variants, the lowest.", minimum = "1")
        long priceMinor,

        @Schema(description = "ISO 4217.", pattern = "^[A-Z]{3}$")
        String currency,

        @ArraySchema(minItems = 1) List<ServiceSegmentResponse> segments,

        @Schema(
                description = "The service's time: every segment but the buffer; with variants, the shortest.",
                minimum = "5")
        int durationMinutes,

        @Schema(description = "The buffer after it: 0 for none.", minimum = "0")
        int bufferMinutes,

        @Schema(
                types = {"string", "null"},
                minLength = 2,
                maxLength = 30,
                description = "The name of the set of variants, such as \"Hair length\"; null without variants.")
        String variantSetName,

        @ArraySchema(
                maxItems = 10,
                arraySchema = @Schema(description = "The variants in order; empty without variants."))
        List<ServiceVariantResponse> variants,

        @Schema(description = "The lowest price.", minimum = "1")
        long priceMinMinor,

        @Schema(description = "The highest price.", minimum = "1")
        long priceMaxMinor,

        @Schema(description = "The shortest time.", minimum = "5")
        int durationMinMinutes,

        @Schema(description = "The longest time.", minimum = "5")
        int durationMaxMinutes) {

    public static ServiceTermsResponse from(TermsView terms) {
        Ranges ranges = Ranges.of(terms.priceMinor(), terms.segments(), terms.variants());
        return new ServiceTermsResponse(
                terms.versionId(),
                terms.number(),
                terms.priceMinor(),
                terms.currency(),
                terms.segments().stream().map(ServiceSegmentResponse::from).toList(),
                Segments.duration(terms.segments()),
                Segments.buffer(terms.segments()),
                terms.variantSetName(),
                terms.variants().stream().map(ServiceVariantResponse::from).toList(),
                ranges.priceMinMinor(),
                ranges.priceMaxMinor(),
                ranges.durationMinMinutes(),
                ranges.durationMaxMinutes());
    }
}
