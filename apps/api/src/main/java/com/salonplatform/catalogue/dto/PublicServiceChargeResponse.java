package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The service charge as the current version holds it. */
@Schema(
        name = "CatalogueTaxServiceCharge",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "percent", "onProducts"})
public record PublicServiceChargeResponse(
        @Schema(description = "What it is called, such as \"Service charge\".")
        String name,

        @Schema(minimum = "1", maximum = "100", description = "Its rate, a whole percentage.")
        int percent,

        @Schema(description = "Whether product prices include it too.")
        boolean onProducts) {}
