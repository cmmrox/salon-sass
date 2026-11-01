package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.ProductService.ProductView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Every product of the salon, by name regardless of case: at most 300 (decision 094's whole capped list). */
@Schema(
        name = "CatalogueProductList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items"})
public record ProductListResponse(
        @ArraySchema(maxItems = 300) List<ProductResponse> items) {

    public static ProductListResponse from(List<ProductView> views) {
        return new ProductListResponse(views.stream().map(ProductResponse::from).toList());
    }
}
