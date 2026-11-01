package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.catalogue.ServiceDeposit;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A service's deposit: {@code {kind: "salon_rule"}} (the salon's booking rules decide), {@code {kind: "none"}},
 * {@code {kind: "percentage", percent}} or {@code {kind: "amount", amountMinor}}. Only the kind's own field is
 * present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "CatalogueServiceDeposit",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record ServiceDepositResponse(
        @Schema(allowableValues = {"salon_rule", "none", "percentage", "amount"})
        String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `percentage`: the share of the price booked.")
        Integer percent,

        @Schema(minimum = "1", format = "int64", description = "With `amount`: minor units of the service's currency.")
        Long amountMinor) {

    public static ServiceDepositResponse from(ServiceDeposit deposit) {
        return switch (deposit) {
            case ServiceDeposit.SalonRule _ -> new ServiceDepositResponse("salon_rule", null, null);
            case ServiceDeposit.None _ -> new ServiceDepositResponse("none", null, null);
            case ServiceDeposit.Percentage percentage ->
                new ServiceDepositResponse("percentage", percentage.percent(), null);
            case ServiceDeposit.Amount amount -> new ServiceDepositResponse("amount", null, amount.amountMinor());
        };
    }
}
