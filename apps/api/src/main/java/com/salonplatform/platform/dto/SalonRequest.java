package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {
            "name",
            "slug",
            "country",
            "currency",
            "timeZone",
            "defaultLanguage",
            "planId",
            "owner",
            "subscription"
        })
public record SalonRequest(
        @Schema(maxLength = 120) String name,
        @Schema(minLength = 3, maxLength = 30) String slug,
        @Schema(minLength = 2, maxLength = 2) String country,
        @Schema(minLength = 3, maxLength = 3) String currency,
        String timeZone,
        @Schema(allowableValues = {"en", "si", "ta"}) String defaultLanguage,

        @Schema(
                types = {"string", "null"},
                maxLength = 254)
        String contactEmail,

        @Schema(
                types = {"string", "null"},
                maxLength = 32)
        String contactPhone,

        @Schema(
                types = {"string", "null"},
                maxLength = 500)
        String address,

        UUID planId,
        SalonOwnerRequest owner,
        SalonSubscriptionRequest subscription) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
