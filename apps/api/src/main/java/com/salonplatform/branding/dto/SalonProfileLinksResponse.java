package com.salonplatform.branding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The salon's social and web links, each an https address or null. */
@Schema(
        name = "SalonProfileLinks",
        requiredProperties = {"instagram", "facebook", "website"})
public record SalonProfileLinksResponse(
        @Schema(
                types = {"string", "null"},
                maxLength = 200,
                pattern = "^https://\\S+$",
                example = "https://instagram.com/yoursalon")
        String instagram,

        @Schema(
                types = {"string", "null"},
                maxLength = 200,
                pattern = "^https://\\S+$")
        String facebook,

        @Schema(
                types = {"string", "null"},
                maxLength = 200,
                pattern = "^https://\\S+$")
        String website) {}
