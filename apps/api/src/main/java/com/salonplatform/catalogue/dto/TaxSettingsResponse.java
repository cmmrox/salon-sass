package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.TaxSettingsService.Settings;
import io.swagger.v3.oas.annotations.media.Schema;

/** The tax settings as the salon's settings staff keep them, with what configuration offers now. */
@Schema(
        name = "CatalogueTaxSettings",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "settings", "configured"})
public record TaxSettingsResponse(
        @Schema(
                minimum = "0",
                description = "The current version's number, which a save is made from: 0 before the first save.")
        long version,

        @Schema(
                types = {"object", "null"},
                implementation = TaxValuesResponse.class,
                description = "The current version's settings, as a save sends them; null before the first save.")
        TaxValuesResponse settings,

        TaxConfiguredResponse configured) {

    public static TaxSettingsResponse from(Settings settings) {
        return new TaxSettingsResponse(
                settings.version(),
                settings.current() == null ? null : TaxValuesResponse.from(settings.current()),
                TaxConfiguredResponse.from(settings.configured()));
    }
}
