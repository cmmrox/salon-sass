package com.salonplatform.shared.local.dto;

import java.time.Instant;
import java.util.Map;

/** One audit entry of a probe. */
public record AuditView(
        String action, Instant occurredAt, String actorKind, Map<String, Object> after, String traceId) {}
