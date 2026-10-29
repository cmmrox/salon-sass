package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record StaffView(
        UUID id,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String mobile,

        String role,
        boolean active,
        boolean professionalGrant,
        boolean mfaEnabled,
        long version) {}
