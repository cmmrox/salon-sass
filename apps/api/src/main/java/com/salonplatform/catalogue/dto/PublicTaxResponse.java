package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.TaxSettingsVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The salon's current tax as customers may know it (F05-S03 AC-6): the version's names and rates, never the VAT
 * registration number.
 */
@Schema(
        name = "CatalogueTax",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"versionId", "vat", "serviceCharge"})
public record PublicTaxResponse(
        @Schema(format = "uuid", description = "The current version, which a booking or a sale made now keeps.")
        UUID versionId,

        @Schema(
                types = {"object", "null"},
                implementation = PublicVatResponse.class,
                description = "VAT, which prices include; null while the salon is not registered for it.")
        PublicVatResponse vat,

        @Schema(
                types = {"object", "null"},
                implementation = PublicServiceChargeResponse.class,
                description = "The service charge, which service prices include; null while the salon charges none.")
        PublicServiceChargeResponse serviceCharge) {

    public static PublicTaxResponse from(TaxSettingsVersion version) {
        TaxSettingsVersion.Vat vat = version.vat();
        TaxSettingsVersion.ServiceCharge charge = version.serviceCharge();
        return new PublicTaxResponse(
                version.versionId(),
                vat.registered() ? new PublicVatResponse(vat.name(), vat.ratePercent()) : null,
                charge.on()
                        ? new PublicServiceChargeResponse(charge.name(), charge.percent(), charge.onProducts())
                        : null);
    }
}
