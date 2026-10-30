package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(requiredProperties = {"id", "method", "reference", "providerEventId", "amount", "receivedOn", "paidAt"})
public record PaymentView(
        UUID id,

        @Schema(allowableValues = {"card", "bank_transfer"}) String method,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reference,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String providerEventId,

        long amount,

        @Schema(
                types = {"string", "null"},
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate receivedOn,

        Instant paidAt) {}
