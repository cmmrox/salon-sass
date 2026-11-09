package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** Who the booking is for. */
@Schema(
        name = "BookingCustomerInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "mobile"})
public record BookingCustomerRequest(
        @Schema(description = "2 to 60 characters, trimmed.")
        String name,

        @Schema(description = "A mobile number of the salon's country, in any common form: `077 123 4567`.")
        String mobile) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("customer." + field);
    }
}
