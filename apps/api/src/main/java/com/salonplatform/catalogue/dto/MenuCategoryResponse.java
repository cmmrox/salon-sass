package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.MenuService.MenuCategory;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** A category of the menu with its services that are bookable online, by name. */
@Schema(
        name = "MenuCategory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "services"})
public record MenuCategoryResponse(
        @Schema(format = "uuid") UUID id,
        String name,
        @ArraySchema(minItems = 1) List<MenuServiceResponse> services) {

    public static MenuCategoryResponse from(MenuCategory category) {
        return new MenuCategoryResponse(
                category.id(),
                category.name(),
                category.services().stream().map(MenuServiceResponse::from).toList());
    }
}
