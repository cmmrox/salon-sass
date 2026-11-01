package com.salonplatform.branding.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

/** The salon's links as sent: each an https address of up to 200 characters, or null or empty for none. */
@Schema(name = "SalonProfileLinksInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SalonProfileLinksRequest(
        @Schema(types = {"string", "null"}) String instagram,
        @Schema(types = {"string", "null"}) String facebook,
        @Schema(types = {"string", "null"}) String website) {

    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("profile.links." + field);
    }
}
