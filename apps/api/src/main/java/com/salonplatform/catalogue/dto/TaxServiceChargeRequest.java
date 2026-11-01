package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.TaxSettingsService.ServiceChargeInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the salon charges a service charge, its rate and whether products carry it too. */
@Schema(
        name = "CatalogueTaxServiceChargeInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"on", "onProducts"})
public record TaxServiceChargeRequest(
        Boolean on,

        @Schema(
                types = {"integer", "null"},
                description = "Required while on: a whole percentage from 1 to the configured maximum (20). Absent or"
                        + " null otherwise.")
        Integer percent,

        @Schema(description = "Whether products carry it too; must be false while it is off.")
        Boolean onProducts) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("serviceCharge." + field);
    }

    public ServiceChargeInput input() {
        return new ServiceChargeInput(on, percent, onProducts);
    }
}
