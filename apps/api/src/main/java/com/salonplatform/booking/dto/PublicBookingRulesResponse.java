package com.salonplatform.booking.dto;

import com.salonplatform.booking.BookingRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The salon's current booking rules, as customers see them before paying. */
@Schema(
        name = "PublicBookingRules",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"versionId", "currency", "rules"})
public record PublicBookingRulesResponse(
        @Schema(description = "The current version, which a booking made now keeps.")
        UUID versionId,

        @Schema(pattern = "^[A-Z]{3}$", description = "The ISO 4217 currency of the amounts.")
        String currency,

        BookingRulesResponse rules) {

    public static PublicBookingRulesResponse from(BookingRules rules) {
        return new PublicBookingRulesResponse(
                rules.versionId(), rules.currency(), BookingRulesResponse.from(rules.terms()));
    }
}
