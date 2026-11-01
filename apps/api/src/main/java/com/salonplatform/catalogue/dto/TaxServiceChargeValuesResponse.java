package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the salon charges a service charge, its rate and whether products carry it too. */
@Schema(
        name = "CatalogueTaxServiceChargeValues",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"on", "percent", "onProducts"})
public record TaxServiceChargeValuesResponse(
        boolean on,

        @Schema(
                types = {"integer", "null"},
                minimum = "1",
                maximum = "100",
                description = "A whole percentage from 1 to the configured maximum while on; null otherwise.")
        Integer percent,

        @Schema(description = "Whether products carry it too; false while it is off.")
        boolean onProducts) {}
