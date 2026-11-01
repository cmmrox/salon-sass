package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The JSON {@code null} the public booking rules answer before the salon first saves them. Named in the
 * contract only; the controller writes a literal {@code null}.
 */
@Schema(
        name = "NoBookingRules",
        types = {"null"},
        description = "No rules saved yet.")
public record NoBookingRules() {}
