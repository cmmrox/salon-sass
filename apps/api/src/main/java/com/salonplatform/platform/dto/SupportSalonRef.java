package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"id", "slug", "name", "hostname"})
public record SupportSalonRef(
        UUID id,
        String slug,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        String hostname) {}
