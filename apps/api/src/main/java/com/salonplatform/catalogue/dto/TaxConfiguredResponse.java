package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.TaxSettingsService.Configured;
import io.swagger.v3.oas.annotations.media.Schema;

/** The names and rates configuration offers the salon's country now, which the next save copies. */
@Schema(
        name = "CatalogueTaxConfigured",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "vatName",
            "vatRatePercent",
            "registrationLabel",
            "serviceChargeName",
            "serviceChargeMaxPercent"
        })
public record TaxConfiguredResponse(
        @Schema(description = "Such as \"VAT\".") String vatName,
        @Schema(minimum = "0", maximum = "100") int vatRatePercent,

        @Schema(description = "Such as \"VAT registration number\".")
        String registrationLabel,

        @Schema(description = "Such as \"Service charge\".") String serviceChargeName,

        @Schema(minimum = "1", maximum = "100", description = "The highest service charge; the lowest is 1.")
        int serviceChargeMaxPercent) {

    static TaxConfiguredResponse from(Configured configured) {
        return new TaxConfiguredResponse(
                configured.vatName(),
                configured.vatRatePercent(),
                configured.registrationLabel(),
                configured.serviceChargeName(),
                configured.serviceChargeMaxPercent());
    }
}
