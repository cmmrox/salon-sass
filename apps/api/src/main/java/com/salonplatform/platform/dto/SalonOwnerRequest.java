package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"name", "email", "password"})
public record SalonOwnerRequest(
        @Schema(maxLength = 120) String name,
        @Schema(maxLength = 254, format = "email") String email,

        @Schema(minLength = 15, maxLength = 128, format = "password")
        String password) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused("owner." + field);
    }
}
