package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"state", "trialEndsAt", "readOnlyAt", "pastDueSince", "notice"})
public record SubscriptionState(
        @Schema(allowableValues = {"trialing", "active", "past_due", "read_only"})
        String state,

        Instant trialEndsAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant readOnlyAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant pastDueSince,

        @Schema(
                types = {"string", "null"},
                allowableValues = {"trial_ending", "past_due", "read_only"},
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The workspace banner to show, decided by the API at the time of the request")
        String notice) {}
