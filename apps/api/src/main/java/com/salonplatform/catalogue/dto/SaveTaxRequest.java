package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** A save of the tax settings, made from the version the caller read. */
@Schema(
        name = "SaveCatalogueTax",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "vat", "serviceCharge"})
public record SaveTaxRequest(
        @Schema(minimum = "0", description = "The current version's number, as read: 0 before the first save.")
        Long version,

        TaxVatRequest vat,
        TaxServiceChargeRequest serviceCharge) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
