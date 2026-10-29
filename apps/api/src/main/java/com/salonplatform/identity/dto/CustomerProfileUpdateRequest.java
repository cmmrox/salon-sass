package com.salonplatform.identity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CustomerProfileUpdateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank String name,

        @Schema(
                types = {"string", "null"},
                format = "email")
        String email,

        @Schema(
                types = {"string", "null"},
                format = "date")
        LocalDate birthday,

        @NotBlank String preferredLanguage,
        @NotNull @Valid NotificationPreferencesRequest notificationPreferences,
        @NotNull Boolean marketingConsent) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
