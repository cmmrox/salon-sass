package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(
        requiredProperties = {
            "id",
            "number",
            "salonId",
            "planKey",
            "planName",
            "periodStart",
            "periodEnd",
            "amount",
            "currency",
            "status",
            "issuedAt",
            "dueAt",
            "paidAt",
            "payments"
        })
public record InvoiceDetail(
        UUID id,
        String number,
        UUID salonId,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String planKey,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String planName,

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
        Instant paidAt,

        List<PaymentView> payments) {}
