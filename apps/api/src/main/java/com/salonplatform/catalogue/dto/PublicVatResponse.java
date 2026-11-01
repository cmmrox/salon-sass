package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** VAT as the current version holds it: its name and rate as configured when it was saved. */
@Schema(
        name = "CatalogueTaxVat",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "ratePercent"})
public record PublicVatResponse(
        @Schema(description = "What it is called, such as \"VAT\".")
        String name,

        @Schema(minimum = "0", maximum = "100", description = "Its rate, a whole percentage.")
        int ratePercent) {}
