package com.salonplatform.booking.dto;

import com.salonplatform.booking.service.BookingRulesService.RulesSettings;
import io.swagger.v3.oas.annotations.media.Schema;

/** The booking rules as the salon's settings staff keep them. */
@Schema(
        name = "BookingRulesSettings",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "rules", "currency"})
public record BookingRulesSettingsResponse(
        @Schema(description = "The current version's number, which a save is made from: 0 before the first save.")
        long version,

        @Schema(
                types = {"object", "null"},
                description = "The current rules; null before the first save.")
        BookingRulesResponse rules,

        @Schema(
                pattern = "^[A-Z]{3}$",
                description = "The ISO 4217 currency amounts are in: the current version's, or the salon's before"
                        + " the first save (LKR when it has none).")
        String currency) {

    public static BookingRulesSettingsResponse from(RulesSettings settings) {
        return new BookingRulesSettingsResponse(
                settings.version(),
                settings.rules() == null
                        ? null
                        : BookingRulesResponse.from(settings.rules().terms()),
                settings.currency());
    }
}
