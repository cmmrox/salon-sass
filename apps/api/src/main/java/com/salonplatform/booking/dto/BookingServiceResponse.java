package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The service and its variant, with their names as they were when the hold was placed. */
@Schema(
        name = "BookingService",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "variantId", "variantName"})
public record BookingServiceResponse(
        UUID id,
        String name,

        @Schema(
                types = {"string", "null"},
                format = "uuid")
        UUID variantId,

        @Schema(types = {"string", "null"}) String variantName) {}
