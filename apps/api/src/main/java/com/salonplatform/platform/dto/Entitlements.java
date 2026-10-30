package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"plan", "limits", "features", "usage", "subscription"})
public record Entitlements(
        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        PlanRef plan,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        Limits limits,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        Features features,

        Usage usage,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        SubscriptionState subscription) {}
