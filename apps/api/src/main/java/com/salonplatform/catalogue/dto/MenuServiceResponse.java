package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.MenuItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A service customers can book online, as the menu shows it. */
@Schema(
        name = "MenuService",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "id",
            "name",
            "description",
            "priceMinor",
            "currency",
            "durationMinutes",
            "photoUrl",
            "termsVersionId"
        })
public record MenuServiceResponse(
        @Schema(format = "uuid") UUID id,
        String name,

        @Schema(
                types = {"string", "null"},
                description = "Null when it has none.")
        String description,

        @Schema(description = "The price in minor units of `currency`, tax included.", minimum = "1")
        long priceMinor,

        @Schema(pattern = "^[A-Z]{3}$") String currency,

        @Schema(description = "The customer's time in minutes; the buffer after it is not included.", minimum = "5")
        int durationMinutes,

        @Schema(
                types = {"string", "null"},
                description = "Where its photo is served; null when it has none.")
        String photoUrl,

        @Schema(format = "uuid", description = "The current terms version; a booking made now keeps it.")
        UUID termsVersionId) {

    public static MenuServiceResponse from(MenuItem item) {
        return new MenuServiceResponse(
                item.id(),
                item.name(),
                item.description(),
                item.priceMinor(),
                item.currency(),
                item.durationMinutes(),
                PhotoUrls.of(item.photoId()),
                item.termsVersionId());
    }
}
