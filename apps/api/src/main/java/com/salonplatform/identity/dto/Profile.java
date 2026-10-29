package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(
        requiredProperties = {
            "id",
            "version",
            "name",
            "mobile",
            "email",
            "birthday",
            "preferredLanguage",
            "notificationPreferences",
            "marketingConsent"
        })
public record Profile(
        UUID id,
        long version,
        String name,
        String mobile,

        @Schema(types = {"string", "null"}) String email,

        @Schema(
                types = {"string", "null"},
                format = "date")
        LocalDate birthday,

        String preferredLanguage,
        Preferences notificationPreferences,
        boolean marketingConsent) {}
