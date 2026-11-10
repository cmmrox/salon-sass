package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who the booking is for. */
@Schema(
        name = "BookingCustomer",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "mobile"})
public record BookingCustomerResponse(
        String name,

        @Schema(description = "In the national format: `077 123 4567`.")
        String mobile) {}
