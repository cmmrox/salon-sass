package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.TaxSettingsService.VatInput;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the salon is registered for VAT, and its registration number while it is. */
@Schema(
        name = "CatalogueTaxVatInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"registered"})
public record TaxVatRequest(
        Boolean registered,

        @Schema(
                types = {"string", "null"},
                description = "Required while registered: 1 to 20 letters, digits and hyphens. Absent or null"
                        + " otherwise.")
        String registrationNumber) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("vat." + field);
    }

    public VatInput input() {
        return new VatInput(registered, registrationNumber);
    }
}
