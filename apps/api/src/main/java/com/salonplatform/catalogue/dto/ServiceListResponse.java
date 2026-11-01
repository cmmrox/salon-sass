package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Every service of the salon, by name: at most 300. */
@Schema(
        name = "CatalogueServiceList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items"})
public record ServiceListResponse(
        @ArraySchema(maxItems = 300) List<ServiceResponse> items) {

    public static ServiceListResponse from(List<ServiceView> views) {
        return new ServiceListResponse(views.stream().map(ServiceResponse::from).toList());
    }
}
