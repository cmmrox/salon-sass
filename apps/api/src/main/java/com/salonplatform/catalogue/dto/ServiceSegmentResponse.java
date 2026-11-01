package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.ServiceSegment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/** One part of a service's time, in order. */
@Schema(
        name = "CatalogueServiceSegment",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind", "minutes"})
public record ServiceSegmentResponse(
        @Schema(
                allowableValues = {"active", "processing", "buffer"},
                description = "`active` occupies the professional; `buffer` follows the service and is not shown to"
                        + " customers. R1 makes no `processing` segment.")
        String kind,

        @Schema(minimum = "1") int minutes) {

    public static ServiceSegmentResponse from(ServiceSegment segment) {
        return new ServiceSegmentResponse(segment.kind().name().toLowerCase(Locale.ROOT), segment.minutes());
    }
}
