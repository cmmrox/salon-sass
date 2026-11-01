package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.CategoryService.OrderedCategories;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The salon's categories in its order: at most 50. */
@Schema(
        name = "CatalogueCategoryList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"listVersion", "items"})
public record CategoryListResponse(
        @Schema(
                description = "The list's version, which a move is made from; raised by every add, delete and move.",
                minimum = "0")
        long listVersion,

        @ArraySchema(maxItems = 50) List<CategoryResponse> items) {

    public static CategoryListResponse from(OrderedCategories list) {
        return new CategoryListResponse(
                list.listVersion(),
                list.items().stream().map(CategoryResponse::from).toList());
    }
}
