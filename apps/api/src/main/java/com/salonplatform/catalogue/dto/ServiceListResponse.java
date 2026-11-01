package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.SalonServices.ServiceList;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Every service of the salon, by name: at most 300, with the salon's currency. */
@Schema(
        name = "CatalogueServiceList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "currency"})
public record ServiceListResponse(
        @ArraySchema(maxItems = 300) List<ServiceResponse> items,

        @Schema(
                description = "The salon's currency, ISO 4217: what a created or saved service's amounts are entered"
                        + " and kept in, also before its first service.",
                pattern = "^[A-Z]{3}$")
        String currency) {

    public static ServiceListResponse from(ServiceList list) {
        return new ServiceListResponse(
                list.items().stream().map(ServiceResponse::from).toList(), list.currency());
    }
}
