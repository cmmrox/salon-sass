package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.domain.Segments;
import com.salonplatform.catalogue.service.SalonServices.TermsView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** A service's current terms: one immutable version, which a booking keeps (CAT-04). */
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
            "bufferMinutes"
        })
public record ServiceTermsResponse(
        @Schema(format = "uuid", description = "The terms version; a booking made now keeps it.")
        UUID versionId,

        @Schema(description = "The version's number for this service, from 1.", minimum = "1")
        long number,

        @Schema(description = "The price in minor units of `currency`.", minimum = "1")
        long priceMinor,

        @Schema(description = "ISO 4217.", pattern = "^[A-Z]{3}$")
        String currency,

        @ArraySchema(minItems = 1) List<ServiceSegmentResponse> segments,

        @Schema(description = "The service's time: every segment but the buffer.", minimum = "5")
        int durationMinutes,

        @Schema(description = "The buffer after it: 0 for none.", minimum = "0")
        int bufferMinutes) {

    public static ServiceTermsResponse from(TermsView terms) {
        return new ServiceTermsResponse(
                terms.versionId(),
                terms.number(),
                terms.priceMinor(),
                terms.currency(),
                terms.segments().stream().map(ServiceSegmentResponse::from).toList(),
                Segments.duration(terms.segments()),
                Segments.buffer(terms.segments()));
    }
}
