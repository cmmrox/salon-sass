package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.Menu;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The salon's bookable menu: its categories in its order, each with its services bookable online. */
@Schema(
        name = "Menu",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"currency", "categories"})
public record MenuResponse(
        @Schema(description = "The salon's currency, ISO 4217.", pattern = "^[A-Z]{3}$")
        String currency,

        @Schema(description = "A category with nothing bookable online is left out.")
        List<MenuCategoryResponse> categories) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.currency(),
                menu.categories().stream().map(MenuCategoryResponse::from).toList());
    }
}
