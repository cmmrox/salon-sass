package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "expiresAt", "entryCode", "entryUrl", "codeExpiresAt"})
public record SupportEntry(
        UUID id,
        Instant expiresAt,

        @Schema(description = "Single-use; exchanged once on the salon's host within 60 seconds")
        String entryCode,

        @Schema(description = "The salon's workspace support entry page with the code in its fragment")
        String entryUrl,

        Instant codeExpiresAt) {}
