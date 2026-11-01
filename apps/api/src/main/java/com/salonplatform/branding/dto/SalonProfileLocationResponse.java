package com.salonplatform.branding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A map position in decimal degrees, with at most six decimal places. */
@Schema(
        name = "SalonProfileLocation",
        requiredProperties = {"latitude", "longitude"})
public record SalonProfileLocationResponse(
        @Schema(minimum = "-90", maximum = "90", example = "6.9061")
        double latitude,

        @Schema(minimum = "-180", maximum = "180", example = "79.8611")
        double longitude) {}
