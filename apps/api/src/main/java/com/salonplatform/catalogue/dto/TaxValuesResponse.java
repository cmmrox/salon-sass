package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.TaxSettingsVersion;
import io.swagger.v3.oas.annotations.media.Schema;

/** A version's settings in the shape a save sends them. */
@Schema(
        name = "CatalogueTaxValues",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"vat", "serviceCharge"})
public record TaxValuesResponse(TaxVatValuesResponse vat, TaxServiceChargeValuesResponse serviceCharge) {

    static TaxValuesResponse from(TaxSettingsVersion version) {
        TaxSettingsVersion.Vat vat = version.vat();
        TaxSettingsVersion.ServiceCharge charge = version.serviceCharge();
        return new TaxValuesResponse(
                new TaxVatValuesResponse(vat.registered(), vat.registrationNumber()),
                new TaxServiceChargeValuesResponse(
                        charge.on(), charge.on() ? charge.percent() : null, charge.onProducts()));
    }
}
