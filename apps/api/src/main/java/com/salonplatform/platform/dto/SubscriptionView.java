package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
        requiredProperties = {
            "version",
            "state",
            "plan",
            "monthlyAmount",
            "currency",
            "paymentMethod",
            "trialEndsAt",
            "currentPeriod",
            "nextInvoiceAt",
            "readOnlyAt"
        })
public record SubscriptionView(
        long version,

        @Schema(allowableValues = {"trialing", "active", "past_due", "read_only"})
        String state,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        PlanSummary plan,

        long monthlyAmount,
        String currency,

        @Schema(allowableValues = {"card", "bank_transfer"}) String paymentMethod,

        Instant trialEndsAt,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        Period currentPeriod,

        Instant nextInvoiceAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant readOnlyAt) {}
