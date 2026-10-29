package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"readyAt", "deleteAt", "sizeBytes", "sha256"})
public record LinkView(Instant readyAt, Instant deleteAt, long sizeBytes, String sha256) {}
