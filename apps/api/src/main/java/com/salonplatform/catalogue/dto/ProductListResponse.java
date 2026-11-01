package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.ProductService.ProductList;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Every product of the salon, by name regardless of case: at most 300 (decision 094's whole capped list), with the
 * salon's currency.
 */
@Schema(
        name = "CatalogueProductList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "currency"})
public record ProductListResponse(
        @ArraySchema(maxItems = 300) List<ProductResponse> items,

        @Schema(
                description = "The salon's currency, ISO 4217: what an added or saved product's price is entered and"
                        + " kept in, also before its first product.",
                pattern = "^[A-Z]{3}$")
        String currency) {

    public static ProductListResponse from(ProductList list) {
        return new ProductListResponse(
                list.items().stream().map(ProductResponse::from).toList(), list.currency());
    }
}
