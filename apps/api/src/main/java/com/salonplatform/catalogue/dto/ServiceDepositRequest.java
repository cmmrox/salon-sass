package com.salonplatform.catalogue.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.catalogue.service.SalonServices.DepositInput;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A service's deposit as sent (BKG-09): {@code salon_rule}, {@code none}, {@code percentage} with {@code percent},
 * or {@code amount} with {@code amountMinor}. A field of another kind is refused.
 */
@Schema(
        name = "CatalogueServiceDepositInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record ServiceDepositRequest(
        @Schema(allowableValues = {"salon_rule", "none", "percentage", "amount"})
        String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `percentage`: the share of the price booked.")
        Integer percent,

        @Schema(
                minimum = "1",
                format = "int64",
                description = "With `amount`: minor units, a whole number of units: Rs. 100 to Rs. 100,000 and not"
                        + " more than the service's lowest price.")
        Long amountMinor) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("deposit." + field);
    }

    DepositInput input() {
        return new DepositInput(kind, percent, amountMinor);
    }
}
