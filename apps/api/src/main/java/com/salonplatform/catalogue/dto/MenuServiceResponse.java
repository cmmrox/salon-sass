package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.MenuItem;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
            "termsVersionId",
            "variantSetName",
            "variants",
            "priceMinMinor",
            "priceMaxMinor",
            "durationMinMinutes",
            "durationMaxMinutes",
            "deposit"
        })
public record MenuServiceResponse(
        @Schema(format = "uuid") UUID id,
        String name,

        @Schema(
                types = {"string", "null"},
                description = "Null when it has none.")
        String description,

        @Schema(
                description = "The price in minor units of `currency`, tax included; with variants, the lowest.",
                minimum = "1")
        long priceMinor,

        @Schema(pattern = "^[A-Z]{3}$") String currency,

        @Schema(
                description = "The customer's time in minutes, the buffer after it not included; with variants, the"
                        + " shortest.",
                minimum = "5")
        int durationMinutes,

        @Schema(
                types = {"string", "null"},
                description = "Where its photo is served; null when it has none.")
        String photoUrl,

        @Schema(format = "uuid", description = "The current terms version; a booking made now keeps it.")
        UUID termsVersionId,

        @Schema(
                types = {"string", "null"},
                description = "The name of the set of variants customers choose from; null without variants.")
        String variantSetName,

        @ArraySchema(
                maxItems = 10,
                arraySchema = @Schema(description = "The variants in order; empty without variants."))
        List<MenuVariantResponse> variants,

        @Schema(description = "The lowest price: show \"From\" when it is below the highest.", minimum = "1")
        long priceMinMinor,

        @Schema(description = "The highest price.", minimum = "1")
        long priceMaxMinor,

        @Schema(description = "The shortest customer's time.", minimum = "5")
        int durationMinMinutes,

        @Schema(description = "The longest customer's time.", minimum = "5")
        int durationMaxMinutes,

        @Schema(description = "Its deposit; `salon_rule` means the salon's booking rules decide.")
        ServiceDepositResponse deposit) {

    public static MenuServiceResponse from(MenuItem item) {
        return new MenuServiceResponse(
                item.id(),
                item.name(),
                item.description(),
                item.priceMinor(),
                item.currency(),
                item.durationMinutes(),
                PhotoUrls.of(item.photoId()),
                item.termsVersionId(),
                item.variantSetName(),
                item.variants().stream().map(MenuVariantResponse::from).toList(),
                item.priceMinMinor(),
                item.priceMaxMinor(),
                item.durationMinMinutes(),
                item.durationMaxMinutes(),
                ServiceDepositResponse.from(item.deposit()));
    }
}
