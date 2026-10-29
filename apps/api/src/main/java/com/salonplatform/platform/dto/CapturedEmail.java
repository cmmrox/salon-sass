package com.salonplatform.platform.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CapturedEmail(
        UUID messageId,
        UUID salonId,
        String recipient,
        String kind,
        String subject,
        String body,
        List<String> links,
        Instant deliveredAt) {}
