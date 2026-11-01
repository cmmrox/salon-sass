package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.booking.DepositRule;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The deposit: {@code {kind: "none"}}, {@code {kind: "percentage", percent, capMinor}} or
 * {@code {kind: "amount", amountMinor}}. Only the kind's own fields are present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "DepositRule",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record DepositRuleResponse(
        @Schema(allowableValues = {"none", "percentage", "amount"})
        String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `percentage`.")
        Integer percent,

        @Schema(
                minimum = "1",
                maximum = "9007199254740991",
                format = "int64",
                description = "With `percentage`: the most the deposit can be, in minor units.")
        Long capMinor,

        @Schema(
                minimum = "1",
                maximum = "9007199254740991",
                format = "int64",
                description = "With `amount`: the deposit, in minor units.")
        Long amountMinor) {

    static DepositRuleResponse from(DepositRule rule) {
        return switch (rule) {
            case DepositRule.None _ -> new DepositRuleResponse("none", null, null, null);
            case DepositRule.Percentage percentage ->
                new DepositRuleResponse("percentage", percentage.percent(), percentage.capMinor(), null);
            case DepositRule.Amount amount -> new DepositRuleResponse("amount", null, null, amount.amountMinor());
        };
    }
}
