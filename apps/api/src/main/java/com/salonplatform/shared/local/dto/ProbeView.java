package com.salonplatform.shared.local.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A probe: its message's state, the calls its provider received, and its audit entries. */
public record ProbeView(
        UUID id,
        String status,
        int attempts,
        int providerCalls,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        String createdAtLocal,
        Instant sentAt,
        List<AuditView> audit) {}
