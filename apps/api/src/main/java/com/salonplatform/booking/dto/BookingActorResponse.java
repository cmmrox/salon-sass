package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who made a change: a member of staff, named as the staff list names them, or the system (an expiry). */
@Schema(
        name = "BookingActor",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind", "label"})
public record BookingActorResponse(
        @Schema(allowableValues = {"staff", "system"}) String kind,

        @Schema(
                types = {"string", "null"},
                description = "The staff account's email, else mobile number; null for the system or an account no"
                        + " longer known.")
        String label) {}
