package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.CategoryService.CategoryView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** One of the salon's categories. */
@Schema(
        name = "CatalogueCategory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "position", "serviceCount", "version"})
public record CategoryResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(minLength = 2, maxLength = 30) String name,

        @Schema(description = "Its place in the salon's order: a lower position comes first.", minimum = "1")
        int position,

        @Schema(description = "How many services it has.", minimum = "0")
        long serviceCount,

        @Schema(description = "Its version, which a rename or delete is made from.", minimum = "0")
        long version) {

    public static CategoryResponse from(CategoryView view) {
        return new CategoryResponse(view.id(), view.name(), view.position(), view.serviceCount(), view.version());
    }
}
