package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.ProductService.ProductInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** A new product. */
@Schema(
        name = "NewCatalogueProduct",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "priceMinor", "chargesVat", "soldAtCheckout"})
public record ProductRequest(
        @Schema(description = "2 to 40 characters after trimming, unique in the salon regardless of case.")
        String name,

        @Schema(
                format = "int64",
                description = "Tax-inclusive, in minor units of the salon's currency, a whole number of units: Rs. 1"
                        + " to Rs. 500,000 (100 to 50000000), scaled for other currencies.")
        Long priceMinor,

        @Schema(description = "Whether VAT applies to it while the salon is registered for VAT.")
        Boolean chargesVat,

        @Schema(description = "Whether the desk offers it at checkout.")
        Boolean soldAtCheckout) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }

    public ProductInput input() {
        return new ProductInput(name, priceMinor, chargesVat, soldAtCheckout);
    }
}
