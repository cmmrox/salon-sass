package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the salon is registered for VAT, and its registration number. */
@Schema(
        name = "CatalogueTaxVatValues",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"registered", "registrationNumber"})
public record TaxVatValuesResponse(
        boolean registered,

        @Schema(
                types = {"string", "null"},
                pattern = "^[A-Za-z0-9-]{1,20}$",
                description = "1 to 20 letters, digits and hyphens while registered; null otherwise.")
        String registrationNumber) {}
