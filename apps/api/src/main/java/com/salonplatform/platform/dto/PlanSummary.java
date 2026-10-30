package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"id", "key", "name"})
public record PlanSummary(UUID id, String key, String name) {}
