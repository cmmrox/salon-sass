package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.Menu;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** The salon's bookable menu: its categories in its order, each with its services bookable online, and Popular. */
@Schema(
        name = "Menu",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"currency", "categories", "popular"})
public record MenuResponse(
        @Schema(description = "The salon's currency, ISO 4217.", pattern = "^[A-Z]{3}$")
        String currency,

        @Schema(description = "A category with nothing bookable online is left out.")
        List<MenuCategoryResponse> categories,

        @ArraySchema(
                maxItems = 6,
                arraySchema =
                        @Schema(
                                description = "The Popular services' ids in order, each one of the menu's: the salon's"
                                        + " choice, or the most booked of the last 90 days."),
                schema = @Schema(format = "uuid"))
        List<UUID> popular) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.currency(),
                menu.categories().stream().map(MenuCategoryResponse::from).toList(),
                menu.popular());
    }
}
