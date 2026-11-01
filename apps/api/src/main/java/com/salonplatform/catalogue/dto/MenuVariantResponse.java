package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.MenuVariant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** One variant of a bookable service, as customers see it. */
@Schema(
        name = "MenuServiceVariant",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "priceMinor", "durationMinutes"})
public record MenuVariantResponse(
        @Schema(format = "uuid", description = "The variant's stable id; a booking names it.")
        UUID id,

        String name,

        @Schema(description = "Its price in minor units of the service's currency, tax included.", minimum = "1")
        long priceMinor,

        @Schema(description = "The customer's time in minutes; the buffer after it is not included.", minimum = "5")
        int durationMinutes) {

    static MenuVariantResponse from(MenuVariant variant) {
        return new MenuVariantResponse(variant.id(), variant.name(), variant.priceMinor(), variant.durationMinutes());
    }
}
