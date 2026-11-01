package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.ProductService.ProductView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** One of the salon's products. */
@Schema(
        name = "CatalogueProduct",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name", "priceMinor", "currency", "chargesVat", "soldAtCheckout", "version"})
public record ProductResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(minLength = 2, maxLength = 40) String name,

        @Schema(minimum = "1", format = "int64", description = "Tax-inclusive, in minor units of `currency`.")
        long priceMinor,

        @Schema(pattern = "^[A-Z]{3}$", description = "The ISO 4217 currency of its price.")
        String currency,

        @Schema(description = "Whether VAT applies to it while the salon is registered for VAT; kept while VAT is off.")
        boolean chargesVat,

        @Schema(description = "Whether the desk offers it at checkout.")
        boolean soldAtCheckout,

        @Schema(description = "Its version, which a save or delete is made from.", minimum = "0")
        long version) {

    public static ProductResponse from(ProductView view) {
        return new ProductResponse(
                view.id(),
                view.name(),
                view.priceMinor(),
                view.currency(),
                view.chargesVat(),
                view.soldAtCheckout(),
                view.version());
    }
}
