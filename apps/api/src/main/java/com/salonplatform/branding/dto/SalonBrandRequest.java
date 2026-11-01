package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A brand as sent for the draft: the response's shape, with each image given by its id. Text is trimmed;
 * an empty tagline means none. The rules and their field errors are in the operation's description.
 */
@Schema(
        name = "SalonBrandInput",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "colour"})
public record SalonBrandRequest(
        @Schema(description = "1 to 60 characters after trimming.")
        String name,

        @Schema(
                types = {"string", "null"},
                description = "Up to 80 characters; empty or null for none.")
        String tagline,

        @Schema(description = "`#RRGGBB`, in either case; kept in upper case.")
        String colour,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "A logo image of this salon, or null for none.")
        String logo,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "A cover image of this salon, or null for none.")
        String cover) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("brand." + field);
    }
}
