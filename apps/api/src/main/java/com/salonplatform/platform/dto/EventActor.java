package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"kind", "id", "displayName"})
public record EventActor(
        @Schema(allowableValues = {"operator", "system"}) String kind,

        String id,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName) {}
