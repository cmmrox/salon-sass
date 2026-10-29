package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
        requiredProperties = {
            "id",
            "number",
            "periodStart",
            "periodEnd",
            "amount",
            "currency",
            "status",
            "issuedAt",
            "dueAt",
            "paidAt"
        })
public record InvoiceSummary(
        UUID id,
        String number,
        Instant periodStart,
        Instant periodEnd,
        long amount,
        String currency,
        @Schema(allowableValues = {"open", "paid"}) String status,
        Instant issuedAt,
        Instant dueAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant paidAt) {}
