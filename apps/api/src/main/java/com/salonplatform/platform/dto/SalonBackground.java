package com.salonplatform.platform.dto;

import java.util.UUID;

public record SalonBackground(UUID salonId, String status, String accessState, OutboxCounts outbox, JobCounts jobs) {}
