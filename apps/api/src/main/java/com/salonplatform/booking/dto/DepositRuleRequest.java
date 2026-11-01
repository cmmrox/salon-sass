package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.booking.service.BookingRulesService.DepositInput;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The deposit as sent: {@code none}; {@code percentage} with {@code percent} and {@code capMinor}; or
 * {@code amount} with {@code amountMinor}. A field of another kind is refused. Amounts are minor units of the
 * salon's currency and must be whole units (whole rupees).
 */
@Schema(
        name = "DepositRuleInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record DepositRuleRequest(
        @Schema(allowableValues = {"none", "percentage", "amount"})
        String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `percentage`: the share of the price.")
        Integer percent,

        @Schema(
                minimum = "1",
                maximum = "9007199254740991",
                format = "int64",
                description = "With `percentage`: the most the deposit can be, in minor units; at least Rs. 500"
                        + " (50000 for LKR), in whole rupees.")
        Long capMinor,

        @Schema(
                minimum = "1",
                maximum = "9007199254740991",
                format = "int64",
                description = "With `amount`: the deposit, in minor units; Rs. 100 to Rs. 100,000 (10000 to"
                        + " 10000000 for LKR), in whole rupees.")
        Long amountMinor) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("rules.deposit." + field);
    }

    DepositInput input() {
        return new DepositInput(kind, percent, capMinor, amountMinor);
    }
}
